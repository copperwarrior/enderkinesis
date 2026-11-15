package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import org.joml.FrustumIntersection
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Vector4f
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.CORNER_FLOATS
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.DURATION_TICKS
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.NO_EDGE
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.C_B
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.C_BLOCK
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.C_G
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.C_R
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.C_SKY
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.C_U
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.C_V
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.QUAD_FLOATS
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.Q_CORNERS
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.Q_DIR
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.Q_ORDER
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.Q_PLANE
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.Q_S0
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.Q_S1
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.Q_T0
import org.shipwrights.enderkinesis.client.WohlonMudCreepTracker.Q_T1
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Each face edge that touches mud runs the mask from that edge: frame 1 → 2 → 3 → 4 → all mud. Between frames the
 * two signed distance fields are lerped and cut at 0, one texel at a time. A texel is mud when any edge says so.
 */
// Render thread only.
object WohlonMudCreepRenderer {

    private val MASK = ResourceLocation("enderkinesis", "textures/block/wohlon_mud_creep_mask.png")
    private val DIRECTIONS: Array<Direction> = Direction.values()

    private val TYPE: RenderType = RenderType.create(
        "enderkinesis:wohlon_mud_creep",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        BUFFER_BYTES,
        false,
        false,
        RenderType.CompositeState.builder()
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_MIPPED_SHADER)
            .setTextureState(RenderStateShard.TextureStateShard(BLOCK_ATLAS, false, true))
            // The old faces lie on the mud mesh but go through another matrix chain; offset them as vanilla crumbling does.
            .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
            .createCompositeState(false),
    )

    /** Signed distance to the mask edge by mask texel, mud positive: the 4 drawn frames, then all mud. */
    private var frames: Array<FloatArray>? = null
    private var framesLoaded = false

    /** Face texel (row * 16 + column) to mask texel, for the mask's top row on the top, bottom, left or right edge. */
    private val EDGE_MAPS: Array<IntArray> = arrayOf(
        IntArray(TEXELS) { it },
        IntArray(TEXELS) { (LAST - it / SIZE) * SIZE + it % SIZE },
        IntArray(TEXELS) { (it % SIZE) * SIZE + it / SIZE },
        IntArray(TEXELS) { (LAST - it % SIZE) * SIZE + it / SIZE },
    )

    /** [EDGE_MAPS] index of the edge of face F toward direction E, at F * 6 + E; -1 on the face's own axis. */
    private val EDGE_SIDE: IntArray = IntArray(DIRECTION_COUNT * DIRECTION_COUNT) { i ->
        val face = DIRECTIONS[i / DIRECTION_COUNT]
        val edge = DIRECTIONS[i % DIRECTION_COUNT]
        if (edge.axis == face.axis) return@IntArray -1
        val x = HALF + HALF * edge.stepX; val y = HALF + HALF * edge.stepY; val z = HALF + HALF * edge.stepZ
        val s = WohlonMudCreepTracker.faceS(face, x, z)
        val t = WohlonMudCreepTracker.faceT(face, y, z)
        when {
            t < HALF -> EDGE_TOP
            t > HALF -> EDGE_BOTTOM
            s < HALF -> EDGE_LEFT
            else -> EDGE_RIGHT
        }
    }

    private val mud = BooleanArray(TEXELS)
    /** Old-texture rectangles per face for the creep in hand: (s0, s1, t0, t1) each. */
    private val faceRects = Array(DIRECTION_COUNT) { FloatArray(TEXELS * RECT_FLOATS) }
    private val faceRectFloats = IntArray(DIRECTION_COUNT)
    private var facesBuilt = 0
    private val openEnd = IntArray(SIZE)
    private val firstRow = IntArray(SIZE)
    private val continued = BooleanArray(SIZE)
    private var rectCursor = 0

    private val pos = BlockPos.MutableBlockPos()
    private val shipOrigin = Vector3d()
    private val shipCentre = Vector3d()
    private val shipRotation = Quaternionf()
    private val viewProj = Matrix4f()
    private val frustum = FrustumIntersection()
    private val position = Vector4f()
    private val normal = Vector3f()
    private lateinit var matrix: Matrix4f
    private lateinit var normalMatrix: Matrix3f
    private var ox = 0f
    private var oy = 0f
    private var oz = 0f
    private var w0 = 0f
    private var w1 = 0f
    private var w2 = 0f
    private var w3 = 0f

    // The render thread reads the client ship's render transform, not physics state; as in WogorBudRenderer.
    @OptIn(GameTickOnly::class)
    fun renderAll(
        poseStack: PoseStack, buffers: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double, partialTicks: Float,
    ) {
        val tracker = WohlonMudCreepTracker
        if (tracker.creeps.isEmpty()) return
        val mask = frames() ?: return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val time = level.gameTime + partialTicks.toDouble()
        val maxDist = mc.options.renderDistance().get() * BLOCKS_PER_CHUNK
        val maxDistSq = maxDist * maxDist
        frustum.set(viewProj.set(RenderSystem.getProjectionMatrix()).mul(poseStack.last().pose()))
        var consumer: VertexConsumer? = null

        for (creep in tracker.creeps.values) {
            val quads = creep.quads ?: continue
            if (quads.isEmpty()) continue
            if (time - creep.start >= DURATION_TICKS || creep.fallbackFaces < 0) continue
            pos.set(creep.pos)
            val ship = level.getShipManagingPos(pos) as? ClientShip
            if (!begin(poseStack, ship, cameraX, cameraY, cameraZ, maxDistSq)) continue
            val c = consumer ?: buffers.getBuffer(TYPE).also { consumer = it }
            facesBuilt = 0
            var q = 0
            while (q < quads.size) {
                val face = quads[q + Q_DIR].toInt()
                if (facesBuilt shr face and 1 == 0) {
                    facesBuilt = facesBuilt or (1 shl face)
                    buildFace(creep, face, time, mask)
                }
                drawQuad(c, quads, q, faceRects[face], faceRectFloats[face])
                q += QUAD_FLOATS
            }
            if (ship != null) poseStack.popPose()
        }
    }

    /** False when a world block is too far or out of view. A ship block pushes a pose. */
    @OptIn(GameTickOnly::class)
    private fun begin(
        poseStack: PoseStack, ship: ClientShip?, cameraX: Double, cameraY: Double, cameraZ: Double, maxDistSq: Double,
    ): Boolean {
        if (ship == null) {
            val dx = pos.x - cameraX; val dy = pos.y - cameraY; val dz = pos.z - cameraZ
            if (dx * dx + dy * dy + dz * dz > maxDistSq) return false
            val x0 = dx.toFloat(); val y0 = dy.toFloat(); val z0 = dz.toFloat()
            if (!frustum.testAab(x0, y0, z0, x0 + 1f, y0 + 1f, z0 + 1f)) return false
            useOrigin(poseStack.last(), x0, y0, z0)
            return true
        }
        shipOrigin.set(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
        ship.renderTransform.shipToWorld.transformPosition(shipOrigin)
        ship.renderTransform.shipToWorldRotation.transform(shipCentre.set(HALF_BLOCK, HALF_BLOCK, HALF_BLOCK))
        val inView = frustum.testSphere(
            (shipOrigin.x + shipCentre.x - cameraX).toFloat(),
            (shipOrigin.y + shipCentre.y - cameraY).toFloat(),
            (shipOrigin.z + shipCentre.z - cameraZ).toFloat(),
            CULL_RADIUS,
        )
        if (!inView) return false
        // Big world offset in double, only the ship rotation in the float pose (precision; see WogorBudRenderer).
        poseStack.pushPose()
        poseStack.translate(shipOrigin.x - cameraX, shipOrigin.y - cameraY, shipOrigin.z - cameraZ)
        val r = ship.renderTransform.shipToWorldRotation
        poseStack.mulPose(shipRotation.set(r.x().toFloat(), r.y().toFloat(), r.z().toFloat(), r.w().toFloat()))
        useOrigin(poseStack.last(), 0f, 0f, 0f)
        return true
    }

    private fun useOrigin(pose: PoseStack.Pose, x: Float, y: Float, z: Float) {
        matrix = pose.pose(); normalMatrix = pose.normal()
        ox = x; oy = y; oz = z
    }

    /** Unites the edge morphs of [face] and turns the texels that are not mud yet into merged rectangles. */
    private fun buildFace(creep: WohlonMudCreepTracker.Creep, face: Int, time: Double, mask: Array<FloatArray>) {
        mud.fill(false)
        val fallback = creep.fallbackFaces shr face and 1 != 0
        for (e in 0 until DIRECTION_COUNT) {
            val side = EDGE_SIDE[face * DIRECTION_COUNT + e]
            if (side < 0) continue
            var start = creep.edgeStart[e]
            if (fallback && (side == EDGE_TOP || DIRECTIONS[face].axis == Direction.Axis.Y)) start = creep.start
            if (start == NO_EDGE) continue
            val run = ((time - start) / DURATION_TICKS * MASK_STEPS).toFloat().coerceAtLeast(0f)
            if (run >= MASK_STEPS) {
                faceRectFloats[face] = 0
                return
            }
            val k = run.toInt()
            val fade = run - k
            val from = mask[k]
            val to = mask[k + 1]
            val map = EDGE_MAPS[side]
            for (i in 0 until TEXELS) {
                val m = map[i]
                if (from[m] + (to[m] - from[m]) * fade >= 0f) mud[i] = true
            }
        }
        faceRectFloats[face] = mergeRects(faceRects[face])
    }

    /** Runs of old texels, the same run in next rows merged; returns the float count written to [out]. */
    private fun mergeRects(out: FloatArray): Int {
        rectCursor = 0
        openEnd.fill(-1)
        for (row in 0 until SIZE) {
            continued.fill(false)
            var col = 0
            while (col < SIZE) {
                if (mud[row * SIZE + col]) { col++; continue }
                val start = col
                while (col < SIZE && !mud[row * SIZE + col]) col++
                if (openEnd[start] != col) {
                    if (openEnd[start] >= 0) close(out, start, row)
                    openEnd[start] = col
                    firstRow[start] = row
                }
                continued[start] = true
            }
            for (start in 0 until SIZE) if (openEnd[start] >= 0 && !continued[start]) close(out, start, row)
        }
        for (start in 0 until SIZE) if (openEnd[start] >= 0) close(out, start, SIZE)
        return rectCursor
    }

    private fun close(out: FloatArray, start: Int, endRow: Int) {
        val n = rectCursor
        out[n] = start.toFloat() / SIZE; out[n + 1] = openEnd[start].toFloat() / SIZE
        out[n + 2] = firstRow[start].toFloat() / SIZE; out[n + 3] = endRow.toFloat() / SIZE
        rectCursor = n + RECT_FLOATS
        openEnd[start] = -1
    }

    /** Draws the old texture of one captured quad over the face's rectangles. */
    private fun drawQuad(c: VertexConsumer, quads: FloatArray, q: Int, rects: FloatArray, count: Int) {
        if (count == 0) return
        val dir = DIRECTIONS[quads[q + Q_DIR].toInt()]
        val sMin = quads[q + Q_S0]; val sMax = quads[q + Q_S1]
        val tMin = quads[q + Q_T0]; val tMax = quads[q + Q_T1]
        normal.set(dir.stepX.toFloat(), dir.stepY.toFloat(), dir.stepZ.toFloat())
        normalMatrix.transform(normal)
        var r = 0
        while (r < count) {
            val s0 = max(rects[r], sMin); val s1 = min(rects[r + 1], sMax)
            val t0 = max(rects[r + 2], tMin); val t1 = min(rects[r + 3], tMax)
            r += RECT_FLOATS
            if (s1 <= s0 || t1 <= t0) continue
            for (k in 0 until VERTICES) {
                val corner = quads[q + Q_ORDER + k].toInt()
                val s = if (corner and 1 != 0) s1 else s0
                val t = if (corner and 2 != 0) t1 else t0
                vertex(c, quads, q, dir, s, t, (s - sMin) / (sMax - sMin), (t - tMin) / (tMax - tMin))
            }
        }
    }

    /** Bilinear blend of the four captured corners at face point (s, t); fs, ft are its place inside the quad. */
    private fun vertex(
        c: VertexConsumer, quads: FloatArray, q: Int, dir: Direction, s: Float, t: Float, fs: Float, ft: Float,
    ) {
        w0 = (1 - fs) * (1 - ft); w1 = fs * (1 - ft); w2 = (1 - fs) * ft; w3 = fs * ft
        val a = q + Q_CORNERS
        val plane = quads[q + Q_PLANE]
        val x: Float; val y: Float; val z: Float
        when (dir) {
            Direction.NORTH -> { x = 1 - s; y = 1 - t; z = plane }
            Direction.SOUTH -> { x = s; y = 1 - t; z = plane }
            Direction.WEST -> { x = plane; y = 1 - t; z = s }
            Direction.EAST -> { x = plane; y = 1 - t; z = 1 - s }
            Direction.UP -> { x = s; y = plane; z = t }
            Direction.DOWN -> { x = s; y = plane; z = 1 - t }
        }
        position.set(ox + x, oy + y, oz + z, 1f)
        matrix.transform(position)
        val light = (blend(quads, a + C_BLOCK) + ROUND).toInt() or ((blend(quads, a + C_SKY) + ROUND).toInt() shl LIGHT_SHIFT)
        c.vertex(
            position.x, position.y, position.z,
            blend(quads, a + C_R), blend(quads, a + C_G), blend(quads, a + C_B), 1f,
            blend(quads, a + C_U), blend(quads, a + C_V), OverlayTexture.NO_OVERLAY, light, normal.x, normal.y, normal.z,
        )
    }

    private fun blend(quads: FloatArray, i: Int): Float =
        w0 * quads[i] + w1 * quads[i + CORNER_FLOATS] + w2 * quads[i + 2 * CORNER_FLOATS] + w3 * quads[i + 3 * CORNER_FLOATS]

    private fun frames(): Array<FloatArray>? {
        if (!framesLoaded) {
            framesLoaded = true
            frames = loadFrames()
        }
        return frames
    }

    private fun loadFrames(): Array<FloatArray>? {
        val resource = Minecraft.getInstance().resourceManager.getResource(MASK).orElse(null) ?: return null
        val on = Array(FRAMES) { BooleanArray(TEXELS) }
        resource.open().use { stream ->
            NativeImage.read(stream).use { image ->
                if (image.width != SIZE || image.height < SIZE * FRAMES) return null
                for (f in 0 until FRAMES) for (i in 0 until TEXELS) {
                    on[f][i] = image.getPixelRGBA(i % SIZE, f * SIZE + i / SIZE) ushr ALPHA_SHIFT != 0
                }
            }
        }
        // All mud: the edge lies past the far row, so frame 4's ragged rim fills toward it row by row.
        val full = FloatArray(TEXELS) { SIZE - it / SIZE - HALF }
        return Array(FRAMES + 1) { f -> if (f == FRAMES) full else signedDistance(on[f]) }
    }

    /** Exact by brute force, texel centre to texel centre, less half a texel so the two sides meet at the edge. */
    private fun signedDistance(inside: BooleanArray): FloatArray = FloatArray(TEXELS) { i ->
        var best = Float.MAX_VALUE
        for (j in 0 until TEXELS) {
            if (inside[j] == inside[i]) continue
            val dx = (i % SIZE - j % SIZE).toFloat()
            val dy = (i / SIZE - j / SIZE).toFloat()
            best = min(best, sqrt(dx * dx + dy * dy))
        }
        // A frame with no texel of the other kind has no edge on the face.
        val d = if (best == Float.MAX_VALUE) NO_EDGE_DISTANCE else best - HALF
        if (inside[i]) d else -d
    }

    private const val SIZE = 16
    private const val TEXELS = SIZE * SIZE
    private const val FRAMES = 4
    private const val LAST = SIZE - 1
    /** Frame 1 → 2, 2 → 3, 3 → 4, 4 → all mud; each an equal share of the run. */
    private const val MASK_STEPS = FRAMES
    private const val DIRECTION_COUNT = 6
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
    private const val HALF = 0.5f
    private const val NO_EDGE_DISTANCE = 2f * SIZE
    private const val ALPHA_SHIFT = 24
    private const val RECT_FLOATS = 4
    private const val VERTICES = 4
    private const val LIGHT_SHIFT = 16
    private const val ROUND = 0.5f
    private const val BLOCKS_PER_CHUNK = 16.0
    private const val HALF_BLOCK = 0.5
    private const val CULL_RADIUS = 0.5f * 1.7320508f
    private const val BUFFER_BYTES = 262144
}
