package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Wheel keys of the staff's plan capture entries. They share the key space with block and entity ids. */
object StaffBuildKeys {
    val MARK: String = key("mark")
    val SAVE: String = key("save")
    val CLEAR: String = key("clear")

    val ALL: Set<String> = setOf(MARK, SAVE, CLEAR)

    private fun key(path: String): String = "${EnderkinesisMod.MOD_ID}:build/$path"

    /** "Save plan" shows when both corners are set; "Clear corners" when a corner is set. */
    fun wheelKeys(corners: Int): List<String> = buildList {
        add(MARK)
        if (corners == 2) add(SAVE)
        if (corners > 0) add(CLEAR)
    }
}

/** Plan placement math shared by the drafting table checks and the client ghost. Pivot is the origin, as in the build. */
object PlanPlacement {

    /** The bottom-centre cell of the placed box, as an offset from the anchor pos. */
    fun bottomCentre(size: Vec3i, rotation: Rotation, mirror: Mirror): BlockPos {
        require(size.x > 0 && size.y > 0 && size.z > 0) { "empty plan size: $size" }
        val a = StructureTemplate.transform(BlockPos.ZERO, mirror, rotation, BlockPos.ZERO)
        val b = StructureTemplate.transform(BlockPos(size.x - 1, size.y - 1, size.z - 1), mirror, rotation, BlockPos.ZERO)
        return BlockPos(Math.floorDiv(a.x + b.x, 2), minOf(a.y, b.y), Math.floorDiv(a.z + b.z, 2))
    }

    /** The anchor pos that puts the bottom centre of the placed box on [base]. */
    fun anchorFor(base: BlockPos, size: Vec3i, rotation: Rotation, mirror: Mirror): BlockPos =
        base.subtract(bottomCentre(size, rotation, mirror))

    fun baseOf(anchor: BlockPos, size: Vec3i, rotation: Rotation, mirror: Mirror): BlockPos =
        anchor.offset(bottomCentre(size, rotation, mirror))

    fun box(anchor: BlockPos, size: Vec3i, rotation: Rotation, mirror: Mirror): BoundingBox {
        val a = StructureTemplate.transform(BlockPos.ZERO, mirror, rotation, BlockPos.ZERO).offset(anchor)
        val b = StructureTemplate.transform(BlockPos(size.x - 1, size.y - 1, size.z - 1), mirror, rotation, BlockPos.ZERO)
            .offset(anchor)
        return BoundingBox.fromCorners(a, b)
    }
}

fun Rotation.turned(clockwise: Boolean): Rotation =
    getRotated(if (clockwise) Rotation.CLOCKWISE_90 else Rotation.COUNTERCLOCKWISE_90)

fun Mirror.cycled(forward: Boolean): Mirror {
    val all = Mirror.entries
    return all[Math.floorMod(ordinal + if (forward) 1 else -1, all.size)]
}
