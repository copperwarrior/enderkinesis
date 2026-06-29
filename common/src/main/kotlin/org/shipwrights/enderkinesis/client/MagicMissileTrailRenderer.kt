package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.entity.MagicMissileEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Diagnostic pass 2 — face glow + wireframe outline. Beam still off. If triangle
 * artifacts appear here but not in pass 1 (face glow only), the wireframe is the
 * culprit; if they don't appear, the wireframe is also clean and the beam was the
 * source.
 *
 * Single constant basis derived once per render call from the projectile's current
 * direction and reused at every drop, so every face quad is planar (basis identical
 * at both segment ends) and adjacent segments tile exactly at joints (basis identical
 * at every drop).
 *
 * Ship-friendly drops preserved — on-ship drops store shipyard coords + shipId and
 * resolve to current world coords each frame via the ship's current
 * `renderTransform.shipToWorld`.
 */
object MagicMissileTrailRenderer {

    private val trails = HashMap<Int, ArrayDeque<Drop>>()

    /** One trail snapshot. `pos` is in **worldspace** — captured as-is from the entity
     *  position (VS2 keeps projectiles in worldspace). No ship-frame transformation is
     *  applied at capture or render; the trail just sits where the missile actually
     *  flew through the world. */
    private data class Drop(val pos: Vec3, val createdAtTick: Long)

    private const val DROP_SPACING: Double = 0.4
    private const val LIFETIME_TICKS: Long = 12
    private const val BOX_HALF_EXTENT: Double = 0.06

    /** Outline colour — bright pink-tinted white. Pushed close enough to white that
     *  the wireframe spars read as a luminous outline picked out in pink, instead of
     *  a flat pink line. */
    private const val OUTLINE_R: Int = 250
    private const val OUTLINE_G: Int = 215
    private const val OUTLINE_B: Int = 240

    /** Face-glow colour — saturated magenta-pink. Carried at the same hue as the
     *  outline but more saturated so the additive interior fill reads distinctly
     *  pink against the lighter outline. */
    private const val GLOW_R: Int = 220
    private const val GLOW_G: Int = 130
    private const val GLOW_B: Int = 195

    /** Wireframe per-vertex alpha scale relative to the raw age α (0..255). */
    private const val WIREFRAME_ALPHA_SCALE: Float = 0.80f

    /** Face-glow α scale relative to per-drop age α. Below the wireframe scale so the
     *  spars stay the visual lead and the glow reads as a soft interior fill. */
    private const val FACE_GLOW_ALPHA_SCALE: Float = 0.25f

    private const val MAX_DROPS: Int = 80

    private var scratchRightX = DoubleArray(MAX_DROPS)
    private var scratchRightY = DoubleArray(MAX_DROPS)
    private var scratchRightZ = DoubleArray(MAX_DROPS)
    private var scratchUpX = DoubleArray(MAX_DROPS)
    private var scratchUpY = DoubleArray(MAX_DROPS)
    private var scratchUpZ = DoubleArray(MAX_DROPS)
    private var scratchWorldX = DoubleArray(MAX_DROPS)
    private var scratchWorldY = DoubleArray(MAX_DROPS)
    private var scratchWorldZ = DoubleArray(MAX_DROPS)

    fun renderAll(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double,
        partialTicks: Float,
    ) {
        val level = Minecraft.getInstance().level ?: return
        val gameTime = level.gameTime

        val liveIds = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            if (entity is MagicMissileEntity && !entity.isRemoved) liveIds.add(entity.id)
        }
        trails.keys.retainAll(liveIds)

        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()

        for (entity in level.entitiesForRendering()) {
            if (entity !is MagicMissileEntity || entity.isRemoved) continue
            updateTrail(entity, gameTime, partialTicks, level)
            renderTrail(entity, gameTime, partialTicks, bufferSource,
                matrix, normal, cameraX, cameraY, cameraZ)
        }
    }

    private fun updateTrail(
        entity: MagicMissileEntity,
        gameTime: Long,
        partialTicks: Float,
        level: ClientLevel,
    ) {
        // Skip drops while the missile sits inside any ship's chunk claim — VS2 reports
        // entity coords in shipyard frame while the entity is inside ship chunks, and
        // those coords aren't valid worldspace for the trail.
        if (level.getShipManagingPos(entity.blockPosition()) != null) return

        // Skip the very first ticks. VS2's ship-attached-entity initialization briefly
        // "applies the ship's current shipToWorld" to the entity for the first frame or
        // two after spawn, before the entity transitions to free-flying world frame.
        // While that's happening, the entity's interpolated position bounces between
        // the world spawn position and the ship-attached projection — and if the ship
        // is moving, that projection lands well forward of the spawn, then snaps back
        // when VS2 releases the entity. Recording those bouncing positions paints a
        // forward-then-back spike in the trail. Two ticks (~0.1 s at 20 Hz) is enough
        // headroom for VS2 to settle, costs ~17 % of the trail length at the very
        // start of flight, and is invisible to the player.
        if (entity.tickCount < 2) return

        // The previous-tick position (`xOld/yOld/zOld`) can be in shipyard frame even
        // when the current position is already in worldspace — that's the entity's
        // transition tick out of a ship's chunk claim. Lerping through that boundary
        // lands the rendered position mid-shipyard-to-world, which is nowhere useful.
        // Detect via `xOld`'s block-pos resolving to a ship; if it does, skip the lerp.
        val oldBlockPos = BlockPos.containing(entity.xOld, entity.yOld, entity.zOld)
        val oldShip = level.getShipManagingPos(oldBlockPos)

        val rawCur = if (oldShip != null) {
            // xOld is shipyard — drop the lerp, take the current world frame as-is.
            Vec3(entity.x, entity.y + 0.15, entity.z)
        } else {
            Vec3(
                Mth.lerp(partialTicks.toDouble(), entity.xOld, entity.x),
                Mth.lerp(partialTicks.toDouble(), entity.yOld, entity.y) + 0.15,
                Mth.lerp(partialTicks.toDouble(), entity.zOld, entity.z),
            )
        }
        val drop = Drop(rawCur, gameTime)

        val trail = trails.getOrPut(entity.id) { ArrayDeque() }
        val lastDrop = trail.lastOrNull()
        if (lastDrop == null || lastDrop.pos.distanceTo(rawCur) >= DROP_SPACING) {
            trail.addLast(drop)
            while (trail.size > MAX_DROPS) trail.removeFirst()
        }
        while (trail.isNotEmpty() && (gameTime - trail.first().createdAtTick) > LIFETIME_TICKS) {
            trail.removeFirst()
        }
    }

    private fun renderTrail(
        entity: MagicMissileEntity,
        gameTime: Long,
        partialTicks: Float,
        bufferSource: MultiBufferSource,
        matrix: Matrix4f,
        normal: Matrix3f,
        cameraX: Double, cameraY: Double, cameraZ: Double,
    ) {
        val trail = trails[entity.id] ?: return
        val n = trail.size
        if (n < 2) return

        val drops = trail.toTypedArray()

        if (n > scratchRightX.size) {
            scratchRightX = DoubleArray(n); scratchRightY = DoubleArray(n); scratchRightZ = DoubleArray(n)
            scratchUpX = DoubleArray(n); scratchUpY = DoubleArray(n); scratchUpZ = DoubleArray(n)
            scratchWorldX = DoubleArray(n); scratchWorldY = DoubleArray(n); scratchWorldZ = DoubleArray(n)
        }

        for (i in 0 until n) {
            scratchWorldX[i] = drops[i].pos.x
            scratchWorldY[i] = drops[i].pos.y
            scratchWorldZ[i] = drops[i].pos.z
        }

        // Single constant basis from the projectile's current direction.
        run {
            val dx = scratchWorldX[n - 1] - scratchWorldX[n - 2]
            val dy = scratchWorldY[n - 1] - scratchWorldY[n - 2]
            val dz = scratchWorldZ[n - 1] - scratchWorldZ[n - 2]
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            val dirX: Double; val dirY: Double; val dirZ: Double
            if (len < 1e-6) { dirX = 1.0; dirY = 0.0; dirZ = 0.0 }
            else { dirX = dx / len; dirY = dy / len; dirZ = dz / len }

            val rUpX: Double; val rUpY: Double; val rUpZ: Double
            if (abs(dirY) < 0.95) { rUpX = 0.0; rUpY = 1.0; rUpZ = 0.0 }
            else { rUpX = 1.0; rUpY = 0.0; rUpZ = 0.0 }
            val rx0 = dirY * rUpZ - dirZ * rUpY
            val ry0 = dirZ * rUpX - dirX * rUpZ
            val rz0 = dirX * rUpY - dirY * rUpX
            val rLen = sqrt(rx0 * rx0 + ry0 * ry0 + rz0 * rz0)
            val rx = rx0 / rLen; val ry = ry0 / rLen; val rz = rz0 / rLen
            val ux = ry * dirZ - rz * dirY
            val uy = rz * dirX - rx * dirZ
            val uz = rx * dirY - ry * dirX
            for (i in 0 until n) {
                scratchRightX[i] = rx; scratchRightY[i] = ry; scratchRightZ[i] = rz
                scratchUpX[i] = ux; scratchUpY[i] = uy; scratchUpZ[i] = uz
            }
        }

        val he = BOX_HALF_EXTENT

        // **Pass 1 — face glow.** All face glow writes finish before any line write
        // begins, so the buffer-source's fallback BufferBuilder stays in FACE_GLOW
        // format until pass 1 completes. Then pass 2's `getBuffer(lines)` flushes the
        // face glow batch and switches state cleanly. Interleaving the two formats
        // crashes BufferBuilder.endVertex with "Not filled all elements" because both
        // consumers actually reference the same fallback builder and only one format
        // can be active at a time.
        val glowConsumer = bufferSource.getBuffer(AegisRenderType.FACE_GLOW)
        for (i in 0 until n - 1) {
            val a = drops[i]; val b = drops[i + 1]
            val ageA = (gameTime - a.createdAtTick).toFloat() + partialTicks
            val ageB = (gameTime - b.createdAtTick).toFloat() + partialTicks
            val ageFracA = (1f - ageA / LIFETIME_TICKS.toFloat()).coerceIn(0f, 1f)
            val ageFracB = (1f - ageB / LIFETIME_TICKS.toFloat()).coerceIn(0f, 1f)
            val alphaA = (ageFracA * 255f).toInt()
            val alphaB = (ageFracB * 255f).toInt()
            if (alphaA == 0 && alphaB == 0) continue
            val glowAlphaA = (alphaA * FACE_GLOW_ALPHA_SCALE).toInt().coerceIn(0, 255)
            val glowAlphaB = (alphaB * FACE_GLOW_ALPHA_SCALE).toInt().coerceIn(0, 255)
            if (glowAlphaA == 0 && glowAlphaB == 0) continue

            val ax = (scratchWorldX[i] - cameraX).toFloat()
            val ay = (scratchWorldY[i] - cameraY).toFloat()
            val az = (scratchWorldZ[i] - cameraZ).toFloat()
            val bx = (scratchWorldX[i + 1] - cameraX).toFloat()
            val by = (scratchWorldY[i + 1] - cameraY).toFloat()
            val bz = (scratchWorldZ[i + 1] - cameraZ).toFloat()

            val rax = (scratchRightX[i] * he).toFloat()
            val ray = (scratchRightY[i] * he).toFloat()
            val raz = (scratchRightZ[i] * he).toFloat()
            val uax = (scratchUpX[i] * he).toFloat()
            val uay = (scratchUpY[i] * he).toFloat()
            val uaz = (scratchUpZ[i] * he).toFloat()
            val rbx = (scratchRightX[i + 1] * he).toFloat()
            val rby = (scratchRightY[i + 1] * he).toFloat()
            val rbz = (scratchRightZ[i + 1] * he).toFloat()
            val ubx = (scratchUpX[i + 1] * he).toFloat()
            val uby = (scratchUpY[i + 1] * he).toFloat()
            val ubz = (scratchUpZ[i + 1] * he).toFloat()

            val cx0 = ax - rax - uax; val cy0 = ay - ray - uay; val cz0 = az - raz - uaz
            val cx1 = ax + rax - uax; val cy1 = ay + ray - uay; val cz1 = az + raz - uaz
            val cx2 = ax - rax + uax; val cy2 = ay - ray + uay; val cz2 = az - raz + uaz
            val cx3 = ax + rax + uax; val cy3 = ay + ray + uay; val cz3 = az + raz + uaz
            val cx4 = bx - rbx - ubx; val cy4 = by - rby - uby; val cz4 = bz - rbz - ubz
            val cx5 = bx + rbx - ubx; val cy5 = by + rby - uby; val cz5 = bz + rbz - ubz
            val cx6 = bx - rbx + ubx; val cy6 = by - rby + uby; val cz6 = bz - rbz + ubz
            val cx7 = bx + rbx + ubx; val cy7 = by + rby + uby; val cz7 = bz + rbz + ubz

            // Face -u (bottom): corners 0, 1 at A → 5, 4 at B.
            emitFaceGlow(glowConsumer, matrix,
                cx0, cy0, cz0, glowAlphaA,
                cx1, cy1, cz1, glowAlphaA,
                cx5, cy5, cz5, glowAlphaB,
                cx4, cy4, cz4, glowAlphaB)
            // Face +u (top): corners 2, 3 at A → 7, 6 at B.
            emitFaceGlow(glowConsumer, matrix,
                cx2, cy2, cz2, glowAlphaA,
                cx3, cy3, cz3, glowAlphaA,
                cx7, cy7, cz7, glowAlphaB,
                cx6, cy6, cz6, glowAlphaB)
            // Face -r (left): corners 0, 2 at A → 6, 4 at B.
            emitFaceGlow(glowConsumer, matrix,
                cx0, cy0, cz0, glowAlphaA,
                cx2, cy2, cz2, glowAlphaA,
                cx6, cy6, cz6, glowAlphaB,
                cx4, cy4, cz4, glowAlphaB)
            // Face +r (right): corners 1, 3 at A → 7, 5 at B.
            emitFaceGlow(glowConsumer, matrix,
                cx1, cy1, cz1, glowAlphaA,
                cx3, cy3, cz3, glowAlphaA,
                cx7, cy7, cz7, glowAlphaB,
                cx5, cy5, cz5, glowAlphaB)
        }

        // **Pass 2 — wireframe outline.** `getBuffer(lines)` ends the face glow batch
        // (draws all accumulated face glow quads) and switches the builder to LINES
        // state. Then all 4 spars per segment write cleanly.
        val lineConsumer = bufferSource.getBuffer(RenderType.lines())
        for (i in 0 until n - 1) {
            val a = drops[i]; val b = drops[i + 1]
            val ageA = (gameTime - a.createdAtTick).toFloat() + partialTicks
            val ageB = (gameTime - b.createdAtTick).toFloat() + partialTicks
            val ageFracA = (1f - ageA / LIFETIME_TICKS.toFloat()).coerceIn(0f, 1f)
            val ageFracB = (1f - ageB / LIFETIME_TICKS.toFloat()).coerceIn(0f, 1f)
            val alphaA = (ageFracA * 255f).toInt()
            val alphaB = (ageFracB * 255f).toInt()
            if (alphaA == 0 && alphaB == 0) continue

            val ax = (scratchWorldX[i] - cameraX).toFloat()
            val ay = (scratchWorldY[i] - cameraY).toFloat()
            val az = (scratchWorldZ[i] - cameraZ).toFloat()
            val bx = (scratchWorldX[i + 1] - cameraX).toFloat()
            val by = (scratchWorldY[i + 1] - cameraY).toFloat()
            val bz = (scratchWorldZ[i + 1] - cameraZ).toFloat()

            val rax = (scratchRightX[i] * he).toFloat()
            val ray = (scratchRightY[i] * he).toFloat()
            val raz = (scratchRightZ[i] * he).toFloat()
            val uax = (scratchUpX[i] * he).toFloat()
            val uay = (scratchUpY[i] * he).toFloat()
            val uaz = (scratchUpZ[i] * he).toFloat()
            val rbx = (scratchRightX[i + 1] * he).toFloat()
            val rby = (scratchRightY[i + 1] * he).toFloat()
            val rbz = (scratchRightZ[i + 1] * he).toFloat()
            val ubx = (scratchUpX[i + 1] * he).toFloat()
            val uby = (scratchUpY[i + 1] * he).toFloat()
            val ubz = (scratchUpZ[i + 1] * he).toFloat()

            val cx0 = ax - rax - uax; val cy0 = ay - ray - uay; val cz0 = az - raz - uaz
            val cx1 = ax + rax - uax; val cy1 = ay + ray - uay; val cz1 = az + raz - uaz
            val cx2 = ax - rax + uax; val cy2 = ay - ray + uay; val cz2 = az - raz + uaz
            val cx3 = ax + rax + uax; val cy3 = ay + ray + uay; val cz3 = az + raz + uaz
            val cx4 = bx - rbx - ubx; val cy4 = by - rby - uby; val cz4 = bz - rbz - ubz
            val cx5 = bx + rbx - ubx; val cy5 = by + rby - uby; val cz5 = bz + rbz - ubz
            val cx6 = bx - rbx + ubx; val cy6 = by - rby + uby; val cz6 = bz - rbz + ubz
            val cx7 = bx + rbx + ubx; val cy7 = by + rby + uby; val cz7 = bz + rbz + ubz

            val lineAlphaA = (alphaA * WIREFRAME_ALPHA_SCALE).toInt().coerceIn(0, 255)
            val lineAlphaB = (alphaB * WIREFRAME_ALPHA_SCALE).toInt().coerceIn(0, 255)
            emitEdge(lineConsumer, matrix, normal, cx0, cy0, cz0, cx4, cy4, cz4, lineAlphaA, lineAlphaB)
            emitEdge(lineConsumer, matrix, normal, cx1, cy1, cz1, cx5, cy5, cz5, lineAlphaA, lineAlphaB)
            emitEdge(lineConsumer, matrix, normal, cx2, cy2, cz2, cx6, cy6, cz6, lineAlphaA, lineAlphaB)
            emitEdge(lineConsumer, matrix, normal, cx3, cy3, cz3, cx7, cy7, cz7, lineAlphaA, lineAlphaB)
        }
    }

    private fun emitFaceGlow(
        consumer: VertexConsumer, matrix: Matrix4f,
        x0: Float, y0: Float, z0: Float, a0: Int,
        x1: Float, y1: Float, z1: Float, a1: Int,
        x2: Float, y2: Float, z2: Float, a2: Int,
        x3: Float, y3: Float, z3: Float, a3: Int,
    ) {
        consumer.vertex(matrix, x0, y0, z0).color(GLOW_R, GLOW_G, GLOW_B, a0).endVertex()
        consumer.vertex(matrix, x1, y1, z1).color(GLOW_R, GLOW_G, GLOW_B, a1).endVertex()
        consumer.vertex(matrix, x2, y2, z2).color(GLOW_R, GLOW_G, GLOW_B, a2).endVertex()
        consumer.vertex(matrix, x3, y3, z3).color(GLOW_R, GLOW_G, GLOW_B, a3).endVertex()
    }

    private fun emitEdge(
        consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        alphaA: Int, alphaB: Int,
    ) {
        val ex = bx - ax; val ey = by - ay; val ez = bz - az
        val invLen = 1f / sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(1e-4f)
        val nx = ex * invLen; val ny = ey * invLen; val nz = ez * invLen
        consumer.vertex(matrix, ax, ay, az)
            .color(OUTLINE_R, OUTLINE_G, OUTLINE_B, alphaA)
            .normal(normal, nx, ny, nz)
            .endVertex()
        consumer.vertex(matrix, bx, by, bz)
            .color(OUTLINE_R, OUTLINE_G, OUTLINE_B, alphaB)
            .normal(normal, nx, ny, nz)
            .endVertex()
    }

}
