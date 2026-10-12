package org.shipwrights.enderkinesis.block

import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * One box for each statue, in pixels for FACING = NORTH: x1, z1, x2, z2, height. Each box
 * contains the full client model. Update a box when its model changes.
 */
internal object StatueShapes {
    private const val BLOCK_PIXELS = 16
    private const val FACINGS = 4
    private const val HEIGHT = 4

    private fun northBox(kind: StatueKind): IntArray = when (kind) {
        StatueKind.STEVE -> intArrayOf(-1, 1, 18, 14, 31)
        StatueKind.CATALOGER -> intArrayOf(-1, 2, 17, 14, 30)
        StatueKind.TENTACLES -> intArrayOf(0, 1, 15, 14, 31)
        StatueKind.TENTACLED_BEAST -> intArrayOf(-3, -1, 19, 19, 29)
        StatueKind.YELLOW_TOWER -> intArrayOf(1, 2, 15, 14, 24)
        StatueKind.BLACK_GOAT -> intArrayOf(-1, -1, 17, 14, 38)
        StatueKind.COUNTING -> intArrayOf(0, 1, 15, 15, 25)
        StatueKind.OWL -> intArrayOf(1, 1, 15, 20, 36)
    }

    private val lower: Map<StatueKind, Array<VoxelShape>> = shapesFor(DoubleBlockHalf.LOWER)
    private val upper: Map<StatueKind, Array<VoxelShape>> = shapesFor(DoubleBlockHalf.UPPER)

    fun of(kind: StatueKind, facing: Direction, half: DoubleBlockHalf): VoxelShape {
        val shapes = if (half == DoubleBlockHalf.LOWER) lower else upper
        return shapes.getValue(kind)[(facing.get2DDataValue() + 2) % FACINGS]
    }

    private fun shapesFor(half: DoubleBlockHalf): Map<StatueKind, Array<VoxelShape>> =
        StatueKind.values().associateWith { kind ->
            val box = northBox(kind)
            check(box[HEIGHT] > BLOCK_PIXELS) { "Statue $kind must be taller than one block" }
            Array(FACINGS) { turns -> build(box, turns, half) }
        }

    /** [turns] quarter turns clockwise from NORTH, seen from above. */
    private fun build(box: IntArray, turns: Int, half: DoubleBlockHalf): VoxelShape {
        var x1 = box[0]
        var z1 = box[1]
        var x2 = box[2]
        var z2 = box[3]
        repeat(turns) {
            val oldX1 = x1
            val oldX2 = x2
            x1 = BLOCK_PIXELS - z2
            x2 = BLOCK_PIXELS - z1
            z1 = oldX1
            z2 = oldX2
        }
        val top = if (half == DoubleBlockHalf.LOWER) BLOCK_PIXELS else box[HEIGHT] - BLOCK_PIXELS
        return Block.box(x1.toDouble(), 0.0, z1.toDouble(), x2.toDouble(), top.toDouble(), z2.toDouble())
    }
}
