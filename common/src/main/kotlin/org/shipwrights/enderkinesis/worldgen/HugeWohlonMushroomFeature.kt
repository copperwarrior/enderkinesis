package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HugeMushroomBlock
import net.minecraft.world.level.block.PipeBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Large Wohlon mushrooms, from bonemeal or worldgen. [Shape.LUMPY] is a dome with tumour-like
 * lumps; [Shape.CUP] is a cap that cups upward with a ragged rim.
 */
class HugeWohlonMushroomFeature(
    codec: Codec<NoneFeatureConfiguration>,
    private val shape: Shape,
) : Feature<NoneFeatureConfiguration>(codec) {

    enum class Shape { LUMPY, CUP }

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean =
        grow(context.level(), context.random(), context.origin(), shape)

    companion object {
        /** Builds the mushroom rooted at [origin] if every cell is free; nothing is placed otherwise. */
        fun grow(level: WorldGenLevel, random: RandomSource, origin: BlockPos, shape: Shape): Boolean {
            var stemHeight: Int
            // [solid] is the cap's whole volume; [cap] is the blocks placed. They differ only for a hollow cap.
            val solid = HashSet<Long>()
            val cap: Set<Long>
            when (shape) {
                Shape.LUMPY -> {
                    stemHeight = LUMPY_STEM_MIN + random.nextInt(LUMPY_STEM_SPREAD)
                    lumpyCap(random, stemHeight, solid)
                    cap = hollowed(solid)
                    // Like a red mushroom, the stem rises inside the hollow cap to meet its roof.
                    while (stemHeight < MAX_STEM && !cap.contains(pack(0, stemHeight, 0))) stemHeight++
                }
                Shape.CUP -> {
                    stemHeight = CUP_STEM_MIN + random.nextInt(CUP_STEM_SPREAD)
                    cupCap(random, stemHeight, solid)
                    cap = solid
                }
            }
            val pos = BlockPos.MutableBlockPos()
            for (y in 0 until stemHeight) {
                if (!isFree(level.getBlockState(pos.setWithOffset(origin, 0, y, 0)), y == 0)) return false
            }
            for (packed in cap) {
                if (!isFree(level.getBlockState(pos.setWithOffset(origin, unpackX(packed), unpackY(packed), unpackZ(packed))), false)) {
                    return false
                }
            }
            val capBlock = if (shape == Shape.LUMPY) EKBlocks.WOHLON_LUMPY_MUSHROOM_BLOCK.get() else EKBlocks.WOHLON_CUP_MUSHROOM_BLOCK.get()
            val stem = EKBlocks.WOHLON_MUSHROOM_STEM.get().defaultBlockState()
                .setValue(HugeMushroomBlock.UP, false).setValue(HugeMushroomBlock.DOWN, false)
            for (y in 0 until stemHeight) {
                if (cap.contains(pack(0, y, 0))) break
                level.setBlock(pos.setWithOffset(origin, 0, y, 0), stem, Block.UPDATE_CLIENTS)
            }
            for (packed in cap) {
                val x = unpackX(packed)
                val y = unpackY(packed)
                val z = unpackZ(packed)
                var state = capBlock.defaultBlockState()
                // Only a face open to the outside shows the cap skin; faces into the hollow show the inside texture.
                for (dir in Direction.entries) {
                    val nx = x + dir.stepX
                    val ny = y + dir.stepY
                    val nz = z + dir.stepZ
                    val covered = solid.contains(pack(nx, ny, nz)) || (nx == 0 && nz == 0 && ny in 0 until stemHeight)
                    state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION[dir]!!, !covered)
                }
                level.setBlock(pos.setWithOffset(origin, x, y, z), state, Block.UPDATE_CLIENTS)
            }
            return true
        }

        /** A dome over the stem top with 2–4 lumps bulging out of it, so no two caps match. */
        private fun lumpyCap(random: RandomSource, stemHeight: Int, out: MutableSet<Long>) {
            val radius = LUMPY_RADIUS_MIN + random.nextInt(2)
            addBall(out, 0.0, stemHeight.toDouble(), 0.0, radius + 0.5, stemHeight - 1)
            repeat(LUMPY_LUMPS_MIN + random.nextInt(LUMPY_LUMPS_SPREAD)) {
                val yaw = random.nextDouble() * 2.0 * Math.PI
                val pitch = random.nextDouble() * Math.PI * 0.45
                val cx = Math.cos(yaw) * Math.cos(pitch) * radius
                val cz = Math.sin(yaw) * Math.cos(pitch) * radius
                val cy = stemHeight + Math.sin(pitch) * radius
                addBall(out, cx, cy, cz, LUMP_RADIUS_MIN + random.nextDouble() * LUMP_RADIUS_SPREAD, stemHeight - 1)
            }
        }

        /** The skin of [solid]: blocks open to the outside upward or sideways. The underside is left open. */
        private fun hollowed(solid: Set<Long>): Set<Long> {
            val shell = HashSet<Long>()
            for (packed in solid) {
                val x = unpackX(packed)
                val y = unpackY(packed)
                val z = unpackZ(packed)
                for (dir in Direction.entries) {
                    if (dir == Direction.DOWN) continue
                    if (!solid.contains(pack(x + dir.stepX, y + dir.stepY, z + dir.stepZ))) {
                        shell += packed
                        break
                    }
                }
            }
            return shell
        }

        /** A disc at the stem top with a ragged rim one block higher, so the cap cups upward. */
        private fun cupCap(random: RandomSource, stemHeight: Int, out: MutableSet<Long>) {
            val radius = CUP_RADIUS_MIN + random.nextInt(2)
            val outer = (radius + 0.4) * (radius + 0.4)
            val rimInner = (radius - 0.6) * (radius - 0.6)
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    val d2 = (dx * dx + dz * dz).toDouble()
                    if (d2 > outer) continue
                    out += pack(dx, stemHeight, dz)
                    if (d2 > rimInner && random.nextFloat() >= CUP_RIM_GAP_CHANCE) out += pack(dx, stemHeight + 1, dz)
                }
            }
        }

        private fun addBall(out: MutableSet<Long>, cx: Double, cy: Double, cz: Double, r: Double, minY: Int) {
            val reach = Math.ceil(r).toInt()
            for (dx in -reach..reach) {
                for (dy in -reach..reach) {
                    for (dz in -reach..reach) {
                        val x = Math.round(cx).toInt() + dx
                        val y = Math.round(cy).toInt() + dy
                        val z = Math.round(cz).toInt() + dz
                        if (y < minY) continue
                        val ex = x - cx
                        val ey = y - cy
                        val ez = z - cz
                        if (ex * ex + ey * ey + ez * ez <= r * r) out += pack(x, y, z)
                    }
                }
            }
        }

        private fun isFree(state: BlockState, isBase: Boolean): Boolean =
            state.isAir || state.`is`(BlockTags.LEAVES) || state.canBeReplaced() ||
                (isBase && (state.`is`(EKBlocks.WOHLON_LUMPY_MUSHROOM.get()) || state.`is`(EKBlocks.WOHLON_CUP_MUSHROOM.get())))

        // Offsets stay within ±64 of the origin, so 8 bits each with a +128 bias is plenty.
        private fun pack(x: Int, y: Int, z: Int): Long = ((x + 128).toLong() shl 16) or ((y + 128).toLong() shl 8) or (z + 128).toLong()
        private fun unpackX(p: Long): Int = ((p shr 16) and 0xFF).toInt() - 128
        private fun unpackY(p: Long): Int = ((p shr 8) and 0xFF).toInt() - 128
        private fun unpackZ(p: Long): Int = (p and 0xFF).toInt() - 128

        /** Widest the cap reaches from the stem, for callers keeping the mushroom inside one chunk. */
        const val MAX_CAP_REACH = 5

        /** Bounds the stem's climb into the cap: stem spread plus the dome and lump height. */
        private const val MAX_STEM = 16
        private const val LUMPY_STEM_MIN = 3
        private const val LUMPY_STEM_SPREAD = 3
        private const val LUMPY_RADIUS_MIN = 2
        private const val LUMPY_LUMPS_MIN = 2
        private const val LUMPY_LUMPS_SPREAD = 3
        private const val LUMP_RADIUS_MIN = 1.0
        private const val LUMP_RADIUS_SPREAD = 0.7

        private const val CUP_STEM_MIN = 4
        private const val CUP_STEM_SPREAD = 3
        private const val CUP_RADIUS_MIN = 2
        private const val CUP_RIM_GAP_CHANCE = 0.15f
    }
}
