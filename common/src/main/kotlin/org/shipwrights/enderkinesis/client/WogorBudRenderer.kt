package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.inventory.InventoryMenu
import org.joml.FrustumIntersection
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Vector4f
import org.shipwrights.enderkinesis.block.WogorBudBlock
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Anti-z-fight between growth faces: each box has a unique inset by (face ordinal, box index), and the
 * second face of each opposite pair turns its root cross by 22.5°. Keep both if the geometry changes.
 */
// Render thread only.
object WogorBudRenderer {

    private val LOG = ResourceLocation("enderkinesis", "block/wogor_log")
    private val ROOTS = ResourceLocation("enderkinesis", "block/wohlon_hanging_roots")
    private val SOLID_TYPE: RenderType = RenderType.entityCutout(InventoryMenu.BLOCK_ATLAS)
    private val ROOT_TYPE: RenderType = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS)
    private val DIRECTIONS: Array<Direction> = Direction.values()

    /** Knot layouts: (dx, dz, width scale, height scale), relative to the core's half size. */
    private val KNOTS: Array<Array<DoubleArray>> = arrayOf(
        arrayOf(doubleArrayOf(1.0, 0.4, 0.55, 0.7), doubleArrayOf(-0.6, -1.0, 0.45, 0.5), doubleArrayOf(-0.8, 0.9, 0.35, 0.4)),
        arrayOf(doubleArrayOf(0.5, 1.0, 0.5, 0.65), doubleArrayOf(-1.0, 0.0, 0.4, 0.55), doubleArrayOf(0.9, -0.8, 0.35, 0.45)),
        arrayOf(doubleArrayOf(-0.4, 1.0, 0.45, 0.6), doubleArrayOf(1.0, -0.3, 0.5, 0.7), doubleArrayOf(-0.9, -0.9, 0.3, 0.4)),
    )

    private val pos = BlockPos.MutableBlockPos()
    private val shipOrigin = Vector3d()
    private val emit = Emitter()
    private val viewProj = Matrix4f()
    private val frustum = FrustumIntersection()

    private var rankedSprite: TextureAtlasSprite? = null
    private var rootRank = FloatArray(0)
    private var rootOpaque = BooleanArray(0)

    // The render thread reads the client ship's render transform, not physics state; as in YgannBarnacleDeathRenderer.
    @OptIn(GameTickOnly::class)
    fun renderAll(
        poseStack: PoseStack, buffers: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double, partialTicks: Float,
    ) {
        val tracker = WogorBudClientTracker
        if (tracker.buds.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val atlas = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
        val log = atlas.apply(LOG)
        val roots = atlas.apply(ROOTS)
        rankRoots(roots)
        val time = level.gameTime + partialTicks.toDouble()
        val maxDist = mc.options.renderDistance().get() * BLOCKS_PER_CHUNK
        val maxDistSq = maxDist * maxDist
        val solid = buffers.getBuffer(SOLID_TYPE)
        var rootBuffer: VertexConsumer? = null
        // The same projection and camera rotation that vanilla's cull frustum is built from.
        frustum.set(viewProj.set(RenderSystem.getProjectionMatrix()).mul(poseStack.last().pose()))

        for (bud in tracker.buds.values) {
            if (bud.faces == 0) continue
            pos.set(bud.pos)
            val ship = level.getShipManagingPos(pos) as? ClientShip
            if (!beginBud(poseStack, ship, cameraX, cameraY, cameraZ, maxDistSq)) continue
            emit.light = LevelRenderer.getLightColor(level, pos)

            val size = Mth.lerp(partialTicks.toDouble(), bud.prevSize, bud.size)
            val t0 = time - bud.rootStart
            val reveal = if (bud.animated) easeOutCubic((t0 / (ROOT_GROW_SHARE * bud.mature)).coerceIn(0.0, 1.0)) else 0.0
            val gone = ((t0 - ROOT_FADE_START * bud.mature) / (ROOT_FADE_SHARE * bud.mature)).coerceIn(0.0, 1.0)
            @Suppress("DEPRECATION") // Mth.getSeed is the vanilla position hash; Mojang marks it internal only.
            val seed = Mth.getSeed(pos)

            for (dir in DIRECTIONS) {
                if (bud.faces shr dir.ordinal and 1 == 0) continue
                emit.face = dir
                if (size > MIN_DRAW_PX) {
                    emit.consumer = solid
                    emit.sprite = log
                    drawBulb(size, dir.ordinal, Math.floorMod(seed + dir.ordinal, KNOTS.size.toLong()).toInt())
                }
                if (reveal > 0.0 && gone < 1.0 && rootRank.isNotEmpty()) {
                    val rb = rootBuffer ?: buffers.getBuffer(ROOT_TYPE).also { rootBuffer = it }
                    emit.consumer = rb
                    emit.sprite = roots
                    drawRoots(dir, reveal * ROOT_REVEAL_PX, gone.toFloat())
                }
            }
            if (ship != null) poseStack.popPose()
        }
    }

    /** Starts [emit] at the bud at [pos]. False when a world bud is too far or out of view. A ship bud pushes a pose. */
    @OptIn(GameTickOnly::class)
    private fun beginBud(
        poseStack: PoseStack, ship: ClientShip?, cameraX: Double, cameraY: Double, cameraZ: Double, maxDistSq: Double,
    ): Boolean {
        if (ship == null) {
            val dx = pos.x - cameraX; val dy = pos.y - cameraY; val dz = pos.z - cameraZ
            if (dx * dx + dy * dy + dz * dz > maxDistSq) return false
            val x0 = dx.toFloat(); val y0 = dy.toFloat(); val z0 = dz.toFloat()
            val inView = frustum.testAab(
                x0 - CULL_PAD, y0 - CULL_PAD, z0 - CULL_PAD, x0 + 1f + CULL_PAD, y0 + 1f + CULL_PAD, z0 + 1f + CULL_PAD,
            )
            if (!inView) return false
            emit.begin(poseStack.last(), x0, y0, z0)
        } else {
            // Big world offset in double, only the ship rotation in the float pose (precision; see YgannBarnacleDeathRenderer).
            poseStack.pushPose()
            shipOrigin.set(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            ship.renderTransform.shipToWorld.transformPosition(shipOrigin)
            poseStack.translate(shipOrigin.x - cameraX, shipOrigin.y - cameraY, shipOrigin.z - cameraZ)
            val r = ship.renderTransform.shipToWorldRotation
            poseStack.mulPose(Quaternionf(r.x().toFloat(), r.y().toFloat(), r.z().toFloat(), r.w().toFloat()))
            emit.begin(poseStack.last(), 0f, 0f, 0f)
        }
        return true
    }

    private fun drawBulb(size: Double, rank: Int, variant: Int) {
        val h = size / 2
        val top = min(FULL_PX, size)
        val e0 = inset(rank, 0)
        box(CENTRE - h - e0, -e0, CENTRE - h - e0, CENTRE + h + e0, top + e0, CENTRE + h + e0)
        val knots = KNOTS[variant]
        for (j in knots.indices) {
            val k = knots[j]
            val l = size * k[2]
            val kh = size * k[3]
            if (l < 1.0 || kh < 1.0) continue
            val cx = CENTRE + k[0] * h
            val cz = CENTRE + k[1] * h
            val x0 = (cx - l / 2).coerceIn(0.0, FULL_PX)
            val x1 = (cx + l / 2).coerceIn(0.0, FULL_PX)
            val z0 = (cz - l / 2).coerceIn(0.0, FULL_PX)
            val z1 = (cz + l / 2).coerceIn(0.0, FULL_PX)
            if (x1 - x0 < MIN_KNOT_PX || z1 - z0 < MIN_KNOT_PX) continue
            val e = inset(rank, j + 1)
            box(x0 - e, -e, z0 - e, x1 + e, min(FULL_PX, kh) + e, z1 + e)
        }
    }

    private fun inset(rank: Int, box: Int): Double = (rank * BOXES_PER_FACE + box) * INSET_STEP_PX

    /** Box in the face frame (support at y = 0, growth +Y), in px. The base on the support is never seen. */
    private fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) {
        val e = emit
        e.normal(0f, 1f, 0f)
        e.vtx(x0, y1, z1, x0, z1); e.vtx(x1, y1, z1, x1, z1); e.vtx(x1, y1, z0, x1, z0); e.vtx(x0, y1, z0, x0, z0)
        e.normal(0f, 0f, 1f)
        e.vtx(x0, y0, z1, x0, FULL_PX - y0); e.vtx(x1, y0, z1, x1, FULL_PX - y0)
        e.vtx(x1, y1, z1, x1, FULL_PX - y1); e.vtx(x0, y1, z1, x0, FULL_PX - y1)
        e.normal(0f, 0f, -1f)
        e.vtx(x1, y0, z0, FULL_PX - x1, FULL_PX - y0); e.vtx(x0, y0, z0, FULL_PX - x0, FULL_PX - y0)
        e.vtx(x0, y1, z0, FULL_PX - x0, FULL_PX - y1); e.vtx(x1, y1, z0, FULL_PX - x1, FULL_PX - y1)
        e.normal(1f, 0f, 0f)
        e.vtx(x1, y0, z1, FULL_PX - z1, FULL_PX - y0); e.vtx(x1, y0, z0, FULL_PX - z0, FULL_PX - y0)
        e.vtx(x1, y1, z0, FULL_PX - z0, FULL_PX - y1); e.vtx(x1, y1, z1, FULL_PX - z1, FULL_PX - y1)
        e.normal(-1f, 0f, 0f)
        e.vtx(x0, y0, z0, z0, FULL_PX - y0); e.vtx(x0, y0, z1, z1, FULL_PX - y0)
        e.vtx(x0, y1, z1, z1, FULL_PX - y1); e.vtx(x0, y1, z0, z0, FULL_PX - y1)
    }

    /** Two diagonal planes as a vanilla cross, texture row 0 on the support face, [heightPx] of the strands shown. */
    private fun drawRoots(dir: Direction, heightPx: Double, gone: Float) {
        val sprite = emit.sprite
        val w = sprite.contents().width()
        val h = sprite.contents().height()
        val texelX = CROSS_WIDTH_PX / w
        val texelY = FULL_PX / h
        val turn = if (dir == Direction.UP || dir == Direction.SOUTH || dir == Direction.EAST) CROSS_TURN else 0.0
        for (plane in 0..1) {
            val a = (if (plane == 0) QUARTER_PI else -QUARTER_PI) + turn
            val ca = cos(a); val sa = sin(a)
            emit.normal((-sa).toFloat(), 0f, ca.toFloat())
            if (gone <= 0f) {
                strip(ca, sa, -CROSS_WIDTH_PX / 2, CROSS_WIDTH_PX / 2, 0.0, heightPx, 0.0, FULL_PX)
                continue
            }
            var row = 0
            while (row < h && row * texelY < heightPx) {
                val y0 = row * texelY
                val y1 = min((row + 1) * texelY, heightPx)
                var c = 0
                while (c < w) {
                    if (!visible(row * w + c, gone)) { c++; continue }
                    val start = c
                    while (c < w && visible(row * w + c, gone)) c++
                    val s0 = -CROSS_WIDTH_PX / 2 + start * texelX
                    val s1 = -CROSS_WIDTH_PX / 2 + c * texelX
                    strip(ca, sa, s0, s1, y0, y1, start * FULL_PX / w, c * FULL_PX / w)
                }
                row++
            }
        }
    }

    private fun visible(i: Int, gone: Float): Boolean = rootOpaque[i] && rootRank[i] >= gone

    private fun strip(ca: Double, sa: Double, s0: Double, s1: Double, y0: Double, y1: Double, u0: Double, u1: Double) {
        val e = emit
        e.vtx(CENTRE + s0 * ca, y0, CENTRE + s0 * sa, u0, y0)
        e.vtx(CENTRE + s1 * ca, y0, CENTRE + s1 * sa, u1, y0)
        e.vtx(CENTRE + s1 * ca, y1, CENTRE + s1 * sa, u1, y1)
        e.vtx(CENTRE + s0 * ca, y1, CENTRE + s0 * sa, u0, y1)
    }

    /** Fade order: distance from the support-face centre (row 0) with jitter, so texels drop one at a time. */
    private fun rankRoots(sprite: TextureAtlasSprite) {
        if (sprite === rankedSprite) return
        rankedSprite = sprite
        val contents = sprite.contents()
        val w = contents.width()
        val h = contents.height()
        val opaque = BooleanArray(w * h) { !contents.isTransparent(0, it % w, it / w) }
        val rng = Random(RANK_SEED)
        val jitter = FloatArray(w * h) { rng.nextFloat() }
        val order = (0 until w * h).filter { opaque[it] }
            .sortedBy { hypot(it % w + 0.5 - w / 2.0, it / w + 0.5) + jitter[it] * RANK_JITTER_TEXELS * w / FULL_PX }
        val rank = FloatArray(w * h) { 2f }
        order.forEachIndexed { j, i -> rank[i] = (j + 0.5f) / order.size }
        rootOpaque = opaque
        rootRank = rank
    }

    private fun easeOutCubic(u: Double): Double { val v = 1 - u; return 1 - v * v * v }

    /** Maps face-frame px to block-local units for the current support face and writes one vertex. */
    private class Emitter {
        lateinit var consumer: VertexConsumer
        lateinit var sprite: TextureAtlasSprite
        var face: Direction = Direction.DOWN
        var light = 0
        private lateinit var matrix: Matrix4f
        private lateinit var normalMatrix: Matrix3f
        private var ox = 0f; private var oy = 0f; private var oz = 0f
        private val position = Vector4f()
        private val normal = Vector3f()

        fun begin(pose: PoseStack.Pose, ox: Float, oy: Float, oz: Float) {
            matrix = pose.pose(); normalMatrix = pose.normal()
            this.ox = ox; this.oy = oy; this.oz = oz
        }

        /** Face-frame normal; rotated into block space the same way as the positions, then by the pose one time. */
        fun normal(x: Float, y: Float, z: Float) {
            when (face) {
                Direction.DOWN -> normal.set(x, y, z)
                Direction.UP -> normal.set(x, -y, -z)
                Direction.NORTH -> normal.set(x, -z, y)
                Direction.SOUTH -> normal.set(x, z, -y)
                Direction.WEST -> normal.set(y, -x, z)
                Direction.EAST -> normal.set(-y, x, z)
            }
            normalMatrix.transform(normal)
        }

        fun vtx(x: Double, y: Double, z: Double, u: Double, v: Double) {
            val bx: Double; val by: Double; val bz: Double
            when (face) {
                Direction.DOWN -> { bx = x; by = y; bz = z }
                Direction.UP -> { bx = x; by = FULL_PX - y; bz = FULL_PX - z }
                Direction.NORTH -> { bx = x; by = FULL_PX - z; bz = y }
                Direction.SOUTH -> { bx = x; by = z; bz = FULL_PX - y }
                Direction.WEST -> { bx = y; by = FULL_PX - x; bz = z }
                Direction.EAST -> { bx = FULL_PX - y; by = x; bz = z }
            }
            val s = sprite
            // As VertexConsumer.vertex(Matrix4f, ...); the one-call form lets BufferBuilder write NEW_ENTITY directly.
            position.set(ox + (bx / FULL_PX).toFloat(), oy + (by / FULL_PX).toFloat(), oz + (bz / FULL_PX).toFloat(), 1f)
            matrix.transform(position)
            // Clamp: an inset box reaches past 0..16 px, and the UV must not bleed into the next atlas sprite.
            consumer.vertex(
                position.x, position.y, position.z, WHITE, WHITE, WHITE, WHITE,
                s.getU(u.coerceIn(0.0, FULL_PX)), s.getV(v.coerceIn(0.0, FULL_PX)),
                OverlayTexture.NO_OVERLAY, light, normal.x, normal.y, normal.z,
            )
        }
    }

    private const val FULL_PX = 16.0
    private const val CENTRE = 8.0
    private const val WHITE = 1f
    // The root cross reaches ~2.2 px past the block on each side; boxes stay within 1 px.
    private const val CULL_PAD = 0.25f
    private const val BLOCKS_PER_CHUNK = 16.0
    private const val MIN_DRAW_PX = 0.05
    private const val MIN_KNOT_PX = 0.5
    /** 1/32 px stays depth-resolvable to roughly 40 blocks with the vanilla near plane. */
    private const val INSET_STEP_PX = 1.0 / 32
    private const val BOXES_PER_FACE = 4
    private const val QUARTER_PI = Math.PI / 4
    private const val CROSS_TURN = Math.PI / 8
    /** Vanilla cross: 14.4 px planes rescaled across the diagonal. */
    private const val CROSS_WIDTH_PX = 16.0 * 1.4142135623730951 * 0.9
    private const val ROOT_REVEAL_PX = 13.0
    private const val ROOT_GROW_SHARE = 0.4
    private const val ROOT_FADE_START = 0.5
    private const val ROOT_FADE_SHARE = 0.45
    private const val RANK_SEED = 11
    private const val RANK_JITTER_TEXELS = 2.5
}
