package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.logging.LogUtils
import net.minecraft.client.renderer.block.BlockModelShaper
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.block.model.ItemOverrides
import net.minecraft.client.renderer.block.model.ItemTransforms
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf
import org.joml.Vector3f
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.GuardianSpikeBase
import org.shipwrights.enderkinesis.block.GuardianSpikeBlock
import org.shipwrights.enderkinesis.block.SpikeAttachment
import org.shipwrights.enderkinesis.registry.EKBlocks
import java.util.EnumMap

/**
 * Builds every guardian spike state model from the floor model, since 1.20.1 block JSON cannot tilt an
 * element on two axes or combine spikes. Each spike is rotated onto its axis; its plate folds onto the support faces.
 */
object GuardianSpikeModels {

    private val LOG = LogUtils.getLogger()
    private val STRIDE = DefaultVertexFormat.BLOCK.integerSize
    private const val UV_OFFSET = 4
    private const val NORMAL_OFFSET = 7
    /** Matches the 0.1 px lift on the plate in guardian_spike.json. */
    private const val LIFT = 0.1f / 16f
    private const val FLAT_EPSILON = 0.02f

    /** Called at the end of ModelBakery.bakeModels; replaces every spike state model in [models]. */
    @JvmStatic
    fun retilt(models: MutableMap<ResourceLocation, BakedModel>) {
        val cluster = EKBlocks.GUARDIAN_SPIKE.get() as GuardianSpikeBase
        val outer = EKBlocks.GUARDIAN_SPIKE_OUTER.get() as GuardianSpikeBase
        // Both blockstate files map every state to the plain floor model.
        val floorState = cluster.defaultBlockState()
        val floor = models[BlockModelShaper.stateToModelLocation(floorState)]
        if (floor == null) {
            LOG.warn("Guardian spike: no floor model baked; spikes will render upright")
            return
        }
        val quads = allQuads(floor, floorState)
        val spike = quads.filter { q -> (0 until 4).any { y(q, it) > FLAT_EPSILON } }
        val plate = quads.firstOrNull { q -> q.direction == Direction.UP && (0 until 4).all { y(q, it) <= FLAT_EPSILON } }
        val jittered = EnumMap<SpikeAttachment, Array<List<BakedQuad>>>(SpikeAttachment::class.java)
        val outward = EnumMap<SpikeAttachment, List<BakedQuad>>(SpikeAttachment::class.java)
        for (attach in SpikeAttachment.entries) {
            val tilted = spike.map { tilt(it, attach) }
            val inner = if (attach.order == 1) {
                tilted + listOfNotNull(plate?.let { tilt(it, attach) })
            } else {
                tilted + (plate?.let { fold(it, attach, inward = true, lift = innerLift(attach)) } ?: emptyList())
            }
            val shifts = GuardianSpikeBlock.JITTER.getValue(attach)
            jittered[attach] = Array(GuardianSpikeBlock.VARIANTS) { k -> inner.map { translate(it, shifts[k]) } }
            if (attach.order > 1) outward[attach] = tilted + (plate?.let { fold(it, attach, inward = false, lift = LIFT) } ?: emptyList())
        }
        var replaced = 0
        for (state in cluster.stateDefinition.possibleStates) {
            models[BlockModelShaper.stateToModelLocation(state)] = ClusterModel(floor, cluster.spikes(state).map { jittered.getValue(it) })
            replaced++
        }
        for (state in outer.stateDefinition.possibleStates) {
            models[BlockModelShaper.stateToModelLocation(state)] = TiltedModel(floor, outer.spikes(state).flatMap { outward.getValue(it) })
            replaced++
        }
        LOG.info("Guardian spike: built {} state models ({} spike quads, plate {})", replaced, spike.size, plate != null)
    }

    private fun allQuads(model: BakedModel, state: BlockState): List<BakedQuad> {
        val rand = RandomSource.create(0L)
        return (Direction.entries.map { model.getQuads(state, it, rand) } + listOf(model.getQuads(state, null, rand))).flatten()
    }

    private fun x(q: BakedQuad, v: Int) = Float.fromBits(q.vertices[v * STRIDE])
    private fun y(q: BakedQuad, v: Int) = Float.fromBits(q.vertices[v * STRIDE + 1])
    private fun z(q: BakedQuad, v: Int) = Float.fromBits(q.vertices[v * STRIDE + 2])

    /** Rotates a floor-model quad from base (0.5, 0, 0.5) pointing up onto [attach]'s base and direction. */
    private fun tilt(q: BakedQuad, attach: SpikeAttachment): BakedQuad {
        val dir = attach.direction
        val rot = Quaternionf().rotationTo(0f, 1f, 0f, dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat())
        val data = q.vertices.copyOf()
        val p = Vector3f()
        for (v in 0 until 4) {
            p.set(x(q, v) - 0.5f, y(q, v), z(q, v) - 0.5f).rotate(rot)
                .add(attach.base.x.toFloat(), attach.base.y.toFloat(), attach.base.z.toFloat())
            writePos(data, v, p)
        }
        val n = Vector3f(q.direction.stepX.toFloat(), q.direction.stepY.toFloat(), q.direction.stepZ.toFloat()).rotate(rot)
        return finish(data, n, q)
    }

    /**
     * Splits the plate into pieces on the support faces under the spike: two halves across an edge,
     * three quarters around a corner (the fourth quarter has no face to land on).
     */
    /**
     * Jittered crease plates can overlap on a shared wall, so each edge slot and the corner sit at their own
     * lift. Face plates keep the JSON lift; no two of them share a wall.
     */
    private fun innerLift(attach: SpikeAttachment): Float = when {
        attach.order == 3 -> 0.5f / 16f
        attach.dz == 0 -> 0.2f / 16f
        attach.dy == 0 -> 0.3f / 16f
        else -> 0.4f / 16f
    }

    private fun translate(q: BakedQuad, shift: Vec3): BakedQuad {
        if (shift == Vec3.ZERO) return q
        val data = q.vertices.copyOf()
        for (v in 0 until 4) {
            data[v * STRIDE] = (x(q, v) + shift.x.toFloat()).toRawBits()
            data[v * STRIDE + 1] = (y(q, v) + shift.y.toFloat()).toRawBits()
            data[v * STRIDE + 2] = (z(q, v) + shift.z.toFloat()).toRawBits()
        }
        return BakedQuad(data, q.tintIndex, q.direction, q.sprite, q.isShade)
    }

    private fun fold(plate: BakedQuad, attach: SpikeAttachment, inward: Boolean, lift: Float): List<BakedQuad> {
        val minX = (0 until 4).minOf { x(plate, it) }; val maxX = (0 until 4).maxOf { x(plate, it) }
        val minZ = (0 until 4).minOf { z(plate, it) }; val maxZ = (0 until 4).maxOf { z(plate, it) }
        val midX = (minX + maxX) / 2f; val midZ = (minZ + maxZ) / 2f
        val d = intArrayOf(attach.dx, attach.dy, attach.dz)
        val axes = (0 until 3).filter { d[it] != 0 }
        val pieces = ArrayList<BakedQuad>(3)
        if (axes.size == 2) {
            val edgeAxis = (0 until 3).first { d[it] == 0 }
            pieces += piece(plate, attach, inward, lift, minX, maxX, minZ, midZ, face = axes[0]) { px, pz, out ->
                out[edgeAxis] = px; out[axes[1]] = midZ - pz
            }
            pieces += piece(plate, attach, inward, lift, minX, maxX, midZ, maxZ, face = axes[1]) { px, pz, out ->
                out[edgeAxis] = px; out[axes[0]] = pz - midZ
            }
        } else {
            val quarters = arrayOf(floatArrayOf(minX, midX, minZ, midZ), floatArrayOf(midX, maxX, minZ, midZ), floatArrayOf(minX, midX, midZ, maxZ))
            for (k in 0 until 3) {
                val (i, j) = (0 until 3).filter { it != k }
                val r = quarters[k]
                pieces += piece(plate, attach, inward, lift, r[0], r[1], r[2], r[3], face = k) { px, pz, out ->
                    out[i] = Math.abs(px - midX); out[j] = Math.abs(pz - midZ)
                }
            }
        }
        return pieces
    }

    /**
     * One plate piece over plate rect [x0, x1] × [z0, z1], laid on the support face whose normal is on axis [face].
     * [place] writes, per in-face axis, the distance into the support from the base; the edge axis gets
     * the plate x as a cell coordinate.
     */
    private fun piece(
        plate: BakedQuad, attach: SpikeAttachment, inward: Boolean, lift: Float, x0: Float, x1: Float, z0: Float, z1: Float, face: Int,
        place: (Float, Float, FloatArray) -> Unit,
    ): BakedQuad {
        val d = intArrayOf(attach.dx, attach.dy, attach.dz)
        val base = floatArrayOf(attach.base.x.toFloat(), attach.base.y.toFloat(), attach.base.z.toFloat())
        val corners = arrayOf(floatArrayOf(x0, z0), floatArrayOf(x0, z1), floatArrayOf(x1, z1), floatArrayOf(x1, z0))
        val data = IntArray(STRIDE * 4)
        val offsets = FloatArray(3)
        val pos = FloatArray(3)
        val p = Vector3f()
        // Outer: the plate lies on the support's faces outside the cell. Crease: on the wall faces inside it.
        val reach = if (inward) -1f else 1f
        for ((v, c) in corners.withIndex()) {
            System.arraycopy(plate.vertices, 0, data, v * STRIDE, STRIDE)
            offsets.fill(Float.NaN)
            place(c[0], c[1], offsets)
            for (a in 0 until 3) {
                pos[a] = when {
                    a == face -> base[a] - d[a] * lift
                    d[a] == 0 -> offsets[a]
                    else -> base[a] + reach * d[a] * offsets[a]
                }
            }
            writePos(data, v, p.set(pos[0], pos[1], pos[2]))
            val (u, w) = plateUv(plate, c[0], c[1])
            data[v * STRIDE + UV_OFFSET] = u.toRawBits()
            data[v * STRIDE + UV_OFFSET + 1] = w.toRawBits()
        }
        val normal = Vector3f()
        normal.setComponent(face, -d[face].toFloat())
        if (windingNormal(data).dot(normal) < 0f) reverse(data)
        return finish(data, normal, plate)
    }

    /** UV at plate point (px, pz); the plate's UV is affine in x and z. */
    private fun plateUv(plate: BakedQuad, px: Float, pz: Float): Pair<Float, Float> {
        val v = plate.vertices
        fun u(i: Int) = Float.fromBits(v[i * STRIDE + UV_OFFSET])
        fun w(i: Int) = Float.fromBits(v[i * STRIDE + UV_OFFSET + 1])
        // Solve uv = a + b·x + c·z from three corners.
        val x0 = x(plate, 0); val z0 = z(plate, 0)
        val dx1 = x(plate, 1) - x0; val dz1 = z(plate, 1) - z0
        val dx2 = x(plate, 2) - x0; val dz2 = z(plate, 2) - z0
        val det = dx1 * dz2 - dx2 * dz1
        val s = ((px - x0) * dz2 - dx2 * (pz - z0)) / det
        val t = (dx1 * (pz - z0) - (px - x0) * dz1) / det
        return Pair(u(0) + s * (u(1) - u(0)) + t * (u(2) - u(0)), w(0) + s * (w(1) - w(0)) + t * (w(2) - w(0)))
    }

    private fun writePos(data: IntArray, v: Int, p: Vector3f) {
        data[v * STRIDE] = p.x.toRawBits()
        data[v * STRIDE + 1] = p.y.toRawBits()
        data[v * STRIDE + 2] = p.z.toRawBits()
    }

    private fun windingNormal(data: IntArray): Vector3f {
        fun at(v: Int) = Vector3f(Float.fromBits(data[v * STRIDE]), Float.fromBits(data[v * STRIDE + 1]), Float.fromBits(data[v * STRIDE + 2]))
        val o = at(0)
        return at(1).sub(o).cross(at(2).sub(o))
    }

    private fun reverse(data: IntArray) {
        val tmp = IntArray(STRIDE)
        System.arraycopy(data, STRIDE, tmp, 0, STRIDE)
        System.arraycopy(data, 3 * STRIDE, data, STRIDE, STRIDE)
        System.arraycopy(tmp, 0, data, 3 * STRIDE, STRIDE)
    }

    // Forge lights from the packed per-vertex normal; vanilla lights from the quad direction. Write both.
    private fun finish(data: IntArray, normal: Vector3f, template: BakedQuad): BakedQuad {
        normal.normalize()
        val packed = (pack(normal.x)) or (pack(normal.y) shl 8) or (pack(normal.z) shl 16)
        for (v in 0 until 4) data[v * STRIDE + NORMAL_OFFSET] = packed
        val dir = Direction.getNearest(normal.x, normal.y, normal.z)
        return BakedQuad(data, template.tintIndex, dir, template.sprite, template.isShade)
    }

    private fun pack(c: Float): Int = (c * 127f).toInt() and 0xFF

    /** One quad-list variant per spike, picked in order from [RandomSource]; GuardianSpikeBlock.placed mirrors the picks. */
    private class ClusterModel(private val base: BakedModel, private val spikes: List<Array<List<BakedQuad>>>) : BakedModel {
        override fun getQuads(state: BlockState?, side: Direction?, rand: RandomSource): List<BakedQuad> {
            if (side != null || spikes.isEmpty()) return emptyList()
            val out = ArrayList<BakedQuad>(spikes.size * QUADS_PER_SPIKE)
            for (variants in spikes) out.addAll(variants[rand.nextInt(GuardianSpikeBlock.VARIANTS)])
            return out
        }
        override fun useAmbientOcclusion(): Boolean = base.useAmbientOcclusion()
        override fun isGui3d(): Boolean = base.isGui3d
        override fun usesBlockLight(): Boolean = base.usesBlockLight()
        override fun isCustomRenderer(): Boolean = false
        override fun getParticleIcon(): TextureAtlasSprite = base.particleIcon
        override fun getTransforms(): ItemTransforms = base.transforms
        override fun getOverrides(): ItemOverrides = base.overrides
    }

    private const val QUADS_PER_SPIKE = 8

    private class TiltedModel(private val base: BakedModel, private val quads: List<BakedQuad>) : BakedModel {
        override fun getQuads(state: BlockState?, side: Direction?, rand: RandomSource): List<BakedQuad> =
            if (side == null) quads else emptyList()
        override fun useAmbientOcclusion(): Boolean = base.useAmbientOcclusion()
        override fun isGui3d(): Boolean = base.isGui3d
        override fun usesBlockLight(): Boolean = base.usesBlockLight()
        override fun isCustomRenderer(): Boolean = false
        override fun getParticleIcon(): TextureAtlasSprite = base.particleIcon
        override fun getTransforms(): ItemTransforms = base.transforms
        override fun getOverrides(): ItemOverrides = base.overrides
    }
}
