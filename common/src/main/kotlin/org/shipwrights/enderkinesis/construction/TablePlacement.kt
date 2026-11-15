package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.shipwrights.enderkinesis.item.PlanPlacement

/** Where a drafting table puts its plan: [offset] from the table to the plan's bottom-centre cell, in the table's frame. */
data class TablePlacement(val offset: Vec3i, val rotation: Rotation, val mirror: Mirror) {

    fun anchorPos(table: BlockPos, size: Vec3i): BlockPos =
        PlanPlacement.anchorFor(table.offset(offset), size, rotation, mirror)

    fun anchor(dimension: ResourceKey<Level>, table: BlockPos, size: Vec3i): PlanAnchor =
        PlanAnchor(dimension, anchorPos(table, size), rotation, mirror)

    fun box(table: BlockPos, size: Vec3i): BoundingBox = PlanPlacement.box(anchorPos(table, size), size, rotation, mirror)

    /** The user's rule: the plan's centre stays within 1.5 times its radius (half its largest side), and at least 8 blocks, of the table. */
    fun inReach(size: Vec3i): Boolean {
        val box = box(BlockPos.ZERO, size)
        // Cell centres: the table cell's centre is (0.5, 0.5, 0.5).
        val dx = (box.minX() + box.maxX() + 1) / 2.0 - HALF
        val dy = (box.minY() + box.maxY() + 1) / 2.0 - HALF
        val dz = (box.minZ() + box.maxZ() + 1) / 2.0 - HALF
        val reach = maxOf(MIN_REACH, REACH_FACTOR * maxOf(size.x, size.y, size.z) / 2.0)
        return dx * dx + dy * dy + dz * dz <= reach * reach
    }

    fun covers(size: Vec3i): Boolean = box(BlockPos.ZERO, size).isInside(BlockPos.ZERO)

    companion object {
        const val REACH_FACTOR = 1.5
        const val MIN_REACH = 8.0
        private const val HALF = 0.5
        val NONE = TablePlacement(Vec3i.ZERO, Rotation.NONE, Mirror.NONE)

        /**
         * A first placement for a new plan: beside the table on the side away from [front], then the other sides,
         * then on top. Null when no candidate is in reach and clear of the table.
         */
        fun initial(size: Vec3i, front: Direction): TablePlacement? {
            val sides = listOf(front.opposite, front.clockWise, front.counterClockWise, front)
            for (side in sides) {
                // Bounded: the plan clears the table within its largest side.
                for (step in 1..maxOf(size.x, size.z)) {
                    val p = TablePlacement(Vec3i(side.stepX * step, 0, side.stepZ * step), Rotation.NONE, Mirror.NONE)
                    if (p.covers(size)) continue
                    if (p.inReach(size)) return p
                    break
                }
            }
            val top = TablePlacement(Vec3i(0, 1, 0), Rotation.NONE, Mirror.NONE)
            return top.takeIf { it.inReach(size) }
        }
    }
}

private const val OFFSET = "Offset"
private const val ROTATION = "Rotation"
private const val MIRROR = "Mirror"

fun TablePlacement.toTag(): CompoundTag = CompoundTag().also {
    it.putIntArray(OFFSET, intArrayOf(offset.x, offset.y, offset.z))
    it.putString(ROTATION, rotation.name)
    it.putString(MIRROR, mirror.name)
}

fun CompoundTag.toTablePlacement(): TablePlacement {
    val o = getIntArray(OFFSET)
    val offset = if (o.size == 3) Vec3i(o[0], o[1], o[2]) else Vec3i.ZERO
    val rotation = Rotation.entries.firstOrNull { it.name == getString(ROTATION) } ?: Rotation.NONE
    val mirror = Mirror.entries.firstOrNull { it.name == getString(MIRROR) } ?: Mirror.NONE
    return TablePlacement(offset, rotation, mirror)
}

fun FriendlyByteBuf.writeTablePlacement(p: TablePlacement) {
    writeVarInt(p.offset.x)
    writeVarInt(p.offset.y)
    writeVarInt(p.offset.z)
    writeEnum(p.rotation)
    writeEnum(p.mirror)
}

fun FriendlyByteBuf.readTablePlacement(): TablePlacement =
    TablePlacement(Vec3i(readVarInt(), readVarInt(), readVarInt()), readEnum(Rotation::class.java), readEnum(Mirror::class.java))
