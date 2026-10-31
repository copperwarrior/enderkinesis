package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellDeaths
import org.shipwrights.enderkinesis.spell.SpellVisuals
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Spell effects drawn on living entities, in vanilla's textures laid at 16 texels per block:
 *
 * - **Entangled:** a web of mangrove-root and vine strands wound round the body in both
 *   directions and bound by loops, slowly shifting, with vines hanging off it.
 * - **Frozen:** a shell of ice over the whole hitbox, studded with packed-ice cubes, each
 *   turned its own way. All of it turns with the body's tumble, as the model does.
 * - **Crystallized:** grown over whatever the mob draws; see [CrystalLayer] and [EntityPasses].
 * - **Weighted:** dark square frames sinking around the entity, dragging it down. Shaded,
 *   never glowing.
 *
 * Entangled, Frozen and Weighted are drawn after the entity, in its own space (origin at its
 * feet), by [EntityPasses] whatever the entity's renderer. Each render type
 * is drawn in one pass: an immediate buffer source reuses one builder per type.
 */
object SpellLayers {
    /** Vanilla's foliage green, for the untinted vine texture. */
    private const val VINE_TINT = 0x48B518
    private const val ROOT_STRANDS = 6
    private const val VINE_STRANDS = 4
    private const val ROOT_LOOPS = 2
    private const val STRAND_STEPS = 18
    private const val ICE_CUBES = 12
    /** How far the ice shell stands off the hitbox. */
    private const val ICE_SHELL = 0.06
    private const val WEIGHT_FRAMES = 3

    @JvmStatic
    fun render(entity: LivingEntity, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        if (entity.deathTime > 0 && SpellDeaths.of(entity) == Element.GROWTH) return buried(entity, partialTick, pose, buffers, light)
        val bits = SpellNetworkClient.visuals(entity.id)
        if (bits and SpellVisuals.CRYSTALLIZED == 0) CrystalLayer.forget(entity.id)
        if (bits == 0 || !SpellVfxRenderTypes.isReady()) return
        // Vanilla's rule: what's invisible to you shows you nothing on it either.
        if (invisible(entity)) return
        val w = entity.bbWidth / 2.0
        val h = entity.bbHeight.toDouble()
        val time = entity.tickCount + partialTick
        pose.pushPose()
        SpellVisuals.clientPose(entity.id, partialTick)?.let { tumble(pose, it, h.toFloat()) }
        val m = pose.last().pose()
        if (bits and (SpellVisuals.ENTANGLED or SpellVisuals.FROZEN) != 0) {
            val blocks = buffers.getBuffer(SpellVfxRenderTypes.sprite(TextureAtlas.LOCATION_BLOCKS))
            if (bits and SpellVisuals.ENTANGLED != 0) entangled(blocks, m, entity, w, h, time, light)
            if (bits and SpellVisuals.FROZEN != 0) frozen(blocks, m, entity, w, h, light)
        }
        pose.popPose()
        if (bits and SpellVisuals.WEIGHTED != 0) weight(pose, buffers, w.toFloat(), h.toFloat(), time)
    }

    /** A mob Growth kills: roots and vines climb it as it's dragged down into the ground. */
    private fun buried(entity: LivingEntity, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        if (!SpellVfxRenderTypes.isReady()) return
        val d = (entity.deathTime - 1 + partialTick) / 20.0
        val climb = SpellGeom.smooth(d / 0.8)
        val sink = (entity.bbHeight * 1.08 + 0.05) * ((d - 0.7) / 1.8).coerceIn(0.0, 1.0).pow(1.6)
        if (climb < 0.02 || sink > entity.bbHeight * 1.05) return
        pose.pushPose()
        pose.translate(0.0, -sink, 0.0)
        val blocks = buffers.getBuffer(SpellVfxRenderTypes.sprite(TextureAtlas.LOCATION_BLOCKS))
        entangled(blocks, pose.last().pose(), entity, entity.bbWidth / 2.0, entity.bbHeight * climb, entity.tickCount + partialTick, light)
        pose.popPose()
    }

    fun reset() {
        CrystalLayer.reset()
    }

    /** Whether [entity] is invisible to the one watching (a spectator, or a teammate who can see
     *  friendly invisibles, still sees it). */
    fun invisible(entity: LivingEntity): Boolean = Minecraft.getInstance().player?.let { entity.isInvisibleTo(it) } ?: false

    private fun tumble(pose: PoseStack, rotation: Quaternionf, h: Float) {
        pose.translate(0f, h / 2f, 0f)
        pose.mulPose(rotation)
        pose.translate(0f, -h / 2f, 0f)
    }

    /** A point on the hitbox's side walls: [around] runs 0..1 once round the perimeter. */
    private fun perimeter(around: Double, w: Double): Pair<Double, Double> {
        val u = ((around % 1.0) + 1.0) % 1.0 * 4.0
        val side = u.toInt().coerceIn(0, 3)
        val along = (u - side) * 2.0 - 1.0
        return when (side) {
            0 -> w to along * w
            1 -> -along * w to w
            2 -> -w to -along * w
            else -> along * w to -w
        }
    }

    // ---- Entangled --------------------------------------------------------------------------

    /** A point on a rounded square round the body, [angle] in radians, [r] out. */
    private fun around(angle: Double, r: Double): Pair<Double, Double> {
        val c = cos(angle); val s = sin(angle)
        // Superellipse: squarer than a circle, so it hugs a box-shaped hitbox.
        return r * Math.signum(c) * Math.sqrt(Math.abs(c)) to r * Math.signum(s) * Math.sqrt(Math.abs(s))
    }

    /** One strand: wound [turns] times round the body from feet to head (negative winds the
     *  other way), weaving in and out a little and drifting with [time]. */
    private fun strandPath(start: Double, turns: Double, r: Double, h: Double, time: Float, seed: Double): List<Vec3> =
        List(STRAND_STEPS + 1) { i ->
            val f = i.toDouble() / STRAND_STEPS
            val angle = start + turns * 2 * Math.PI * f + 0.18 * sin(time * 0.04 + seed * 7 + f * 5)
            val (x, z) = around(angle, r + 0.03 * sin(time * 0.06 + seed * 11 + f * 9))
            Vec3(x, h * (0.02 + 0.96 * f), z)
        }

    /** A loop binding the strands at height [y], rising and falling a little. */
    private fun loopPath(y: Double, r: Double, time: Float, seed: Double): List<Vec3> =
        List(STRAND_STEPS + 1) { i ->
            val angle = 2 * Math.PI * i / STRAND_STEPS
            val (x, z) = around(angle + time * 0.003, r + 0.02)
            Vec3(x, y + 0.06 * sin(angle * 3 + seed * 5 + time * 0.05), z)
        }

    /** A ribbon lying on the body along [path], [w0] wide at the start and [w1] at the end,
     *  across it the texture's own pixels round column [uc], along it repeating once a block. */
    private fun surfaceStrip(c: VertexConsumer, m: Matrix4f, path: List<Vec3>, w0: Double, w1: Double, sprite: Sprite, rgb: Int, uc: Double, light: Int) {
        val edges = path.mapIndexed { i, p ->
            val tangent = path[minOf(path.size - 1, i + 1)].subtract(path[maxOf(0, i - 1)])
            tangent.cross(Vec3(p.x, 0.0, p.z)).normalize().scale((w0 + (w1 - w0) * i / (path.size - 1)) / 2)
        }
        SpellShapes.stripPx(c, m, path, edges, sprite, rgb, FloatArray(path.size) { 1f }, uc, light)
    }

    private fun entangled(c: VertexConsumer, m: Matrix4f, entity: LivingEntity, w: Double, h: Double, time: Float, light: Int) {
        val r = Random(entity.id * 17 + 3)
        val roots = SpellArt.block("mangrove_roots_side")
        val vine = SpellArt.block("vine")
        val reach = w + 0.04
        repeat(ROOT_STRANDS) { i ->
            // Alternate directions so the strands cross and lock into a web.
            val dir = if (i % 2 != 0) -1.0 else 1.0
            val start = 2 * Math.PI * i / ROOT_STRANDS + r.nextDouble() * 0.6
            surfaceStrip(c, m, strandPath(start, dir * (0.55 + 0.35 * r.nextDouble()), reach, h, time, r.nextDouble()), 0.22 + 0.06 * r.nextDouble(), 0.12, roots, 0xFFFFFF, r.nextDouble(), light)
        }
        repeat(ROOT_LOOPS) { i ->
            val y = h * (0.3 + 0.4 * i + 0.08 * r.nextDouble())
            surfaceStrip(c, m, loopPath(y, reach, time, r.nextDouble()), 0.19, 0.19, roots, 0xFFFFFF, r.nextDouble(), light)
        }
        val hang = ArrayList<Vec3>()
        repeat(VINE_STRANDS) { i ->
            val dir = if (i % 2 != 0) 1.0 else -1.0
            val path = strandPath(2 * Math.PI * (i + 0.5) / VINE_STRANDS + r.nextDouble() * 0.5, dir * (0.8 + 0.4 * r.nextDouble()), w + 0.06, h, time * 0.8f, r.nextDouble())
            surfaceStrip(c, m, path, 0.25, 0.16, vine, VINE_TINT, r.nextDouble(), light)
            hang += path[3 + r.nextInt(12)]
        }
        // Vines hanging off the web, swaying: two upright planes crossed, as vanilla draws plants.
        for (p in hang) {
            val size = 0.32 + 0.2 * r.nextDouble()
            val sway = 0.14 * sin(time * 0.1 + r.nextDouble() * 20)
            for (k in 0 until 2) {
                val an = Math.PI / 4 + k * Math.PI / 2
                val d = Vec3(cos(an) * size / 2, 0.0, sin(an) * size / 2)
                val bottom = Vec3(p.x + sway * 0.3, p.y - size, p.z)
                SpellShapes.texFace(c, m, bottom.subtract(d), bottom.add(d), p.add(d), p.subtract(d), vine, VINE_TINT, 1f, light, (k * 7 % 16) / 16.0, 0.0)
            }
        }
    }

    // ---- Frozen -----------------------------------------------------------------------------

    private fun frozen(c: VertexConsumer, m: Matrix4f, entity: LivingEntity, w: Double, h: Double, light: Int) {
        // The shell over the whole body first, then the cubes studding it.
        val s = w + ICE_SHELL
        val shell = Array(8) { i -> Vec3(if (i and 1 != 0) s else -s, if (i and 2 != 0) h + ICE_SHELL else -ICE_SHELL, if (i and 4 != 0) s else -s) }
        SpellShapes.texBox(c, m, shell, SpellArt.block("ice"), 0xFFFFFF, 0.75f, light, entity.id)
        val r = Random(entity.id * 13 + 5)
        val packed = SpellArt.block("packed_ice")
        repeat(ICE_CUBES) { i ->
            val (x, z) = perimeter(r.nextDouble(), w * 0.95)
            val y = h * (0.05 + 0.9 * r.nextDouble())
            val size = 0.09 + 0.09 * r.nextDouble()
            val cube = SpellGeom.cubeCorners(Vec3(x, y, z), size, r.nextDouble() * 6.28, r.nextDouble() * 6.28, r.nextDouble() * 6.28)
            SpellShapes.texBox(c, m, cube, packed, 0xFFFFFF, 0.95f, light, entity.id * 31 + i)
        }
    }

    // ---- Weighted ---------------------------------------------------------------------------

    private fun weight(pose: PoseStack, buffers: MultiBufferSource, w: Float, h: Float, time: Float) {
        val matrix = pose.last().pose()
        val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
        val (r, g, b) = rgb(SpellGeom.darken(Element.GRAVITY.color, 0.25f))
        for (i in 0 until WEIGHT_FRAMES) {
            // Each frame sinks from the head to the feet, tightening and turning as it goes,
            // then starts again.
            val u = ((time * 0.04f + i.toFloat() / WEIGHT_FRAMES) % 1f)
            val y = h * (1.05f - u)
            val half = (w + 0.35f) * (1.1f - 0.4f * u)
            val alpha = (1f - u) * u * 4f
            val turn = (i * 30f + u * 45f) * Mth.DEG_TO_RAD
            frame(shade, matrix, y, half, 0.14f, turn, 20, 16, 28, (alpha * 130).toInt())
            frame(shade, matrix, y, half, 0.05f, turn, r, g, b, (alpha * 220).toInt())
        }
    }

    /** A flat square frame at height [y], [half] out from the centre and turned [turn] about
     *  the vertical, [width] wide, fading at both edges. */
    private fun frame(c: VertexConsumer, m: Matrix4f, y: Float, half: Float, width: Float, turn: Float, r: Int, g: Int, b: Int, alpha: Int) {
        if (alpha <= 0) return
        val ct = cos(turn); val st = sin(turn)
        fun put(x: Float, z: Float, a: Float) {
            c.vertex(m, x * ct - z * st, y, x * st + z * ct).color(r, g, b, (alpha * a).toInt()).endVertex()
        }
        // Each side runs corner to corner, so the four meet in clean mitred corners.
        val corners = arrayOf(floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f), floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f))
        for (k in 0 until 4) {
            val c0 = corners[k]; val c1 = corners[(k + 1) % 4]
            for ((inner, outer, aIn, aOut) in listOf(
                listOf(half - width, half, 0f, 1f), listOf(half, half + width, 1f, 0f),
            )) {
                put(c0[0] * inner, c0[1] * inner, aIn)
                put(c1[0] * inner, c1[1] * inner, aIn)
                put(c1[0] * outer, c1[1] * outer, aOut)
                put(c0[0] * outer, c0[1] * outer, aOut)
            }
        }
    }

    private fun rgb(rgb: Int) = Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
