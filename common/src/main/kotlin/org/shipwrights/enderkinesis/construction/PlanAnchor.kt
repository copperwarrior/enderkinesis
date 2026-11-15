package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation

/** Where a plan sits. [pos] is the template origin before the transform, in the shipyard for a table on a ship. */
data class PlanAnchor(
    val dimension: ResourceKey<Level>,
    val pos: BlockPos,
    val rotation: Rotation,
    val mirror: Mirror,
)

private const val DIMENSION = "Dimension"
private const val POS = "Pos"
private const val ROTATION = "Rotation"
private const val MIRROR = "Mirror"

fun PlanAnchor.toTag(): CompoundTag = CompoundTag().also {
    it.putString(DIMENSION, dimension.location().toString())
    it.put(POS, NbtUtils.writeBlockPos(pos))
    it.putString(ROTATION, rotation.name)
    it.putString(MIRROR, mirror.name)
}

/** Null when the tag is not a whole, valid anchor. */
fun CompoundTag.toPlanAnchor(): PlanAnchor? {
    val dimension = ResourceLocation.tryParse(getString(DIMENSION)) ?: return null
    if (!contains(POS)) return null
    val rotation = Rotation.entries.firstOrNull { it.name == getString(ROTATION) } ?: return null
    val mirror = Mirror.entries.firstOrNull { it.name == getString(MIRROR) } ?: return null
    return PlanAnchor(ResourceKey.create(Registries.DIMENSION, dimension), NbtUtils.readBlockPos(getCompound(POS)), rotation, mirror)
}

fun FriendlyByteBuf.writePlanAnchor(anchor: PlanAnchor) {
    writeResourceKey(anchor.dimension)
    writeBlockPos(anchor.pos)
    writeEnum(anchor.rotation)
    writeEnum(anchor.mirror)
}

fun FriendlyByteBuf.readPlanAnchor(): PlanAnchor = PlanAnchor(
    readResourceKey(Registries.DIMENSION),
    readBlockPos(),
    readEnum(Rotation::class.java),
    readEnum(Mirror::class.java),
)
