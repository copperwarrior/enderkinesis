package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.WoodStatueBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.getShipMountedTo
import org.valkyrienskies.mod.common.getShipStoodOn

// Feet cell first, then up, then the eight neighbours: at most 27 cells for each frame.
private const val MAX_RISE = 2
private const val SIDE_REACH = 1

/**
 * Carves the player into a statue at the death spot and moves the inventory into it.
 * With no free spot nothing changes, so vanilla drops the items.
 */
fun ServerPlayer.petrifyIntoStatue() {
    val level = serverLevel()
    val onShip = statueShip(level)?.let { ship -> findStatueSpot(level, shipFeet(ship))?.let { it to shipYaw(ship) } }
    val pos = onShip?.first ?: findStatueSpot(level, blockPosition()) ?: return
    val yawOffset = onShip?.second ?: 0f

    val lower = EKBlocks.WOOD_STATUE.get().defaultBlockState()
    level.setBlock(pos, lower, Block.UPDATE_ALL)
    level.setBlock(pos.above(), lower.setValue(WoodStatueBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL)
    val statue = level.getBlockEntity(pos) as? WoodStatueBlockEntity ?: return
    statue.carve(
        gameProfile,
        Mth.wrapDegrees(yBodyRot - yawOffset),
        Mth.wrapDegrees(yHeadRot - yBodyRot),
        xRot,
    )
    if (!level.gameRules.getBoolean(GameRules.RULE_KEEPINVENTORY)) statue.takeInventory(inventory)
    level.sendBlockUpdated(pos, lower, lower, Block.UPDATE_CLIENTS)
}

// A player already in the shipyard needs no transform; one on a deck stands in world space.
private fun ServerPlayer.statueShip(level: ServerLevel): Ship? =
    if (level.getShipManagingPos(blockPosition()) != null) null else getShipMountedTo(this) ?: getShipStoodOn()

private fun ServerPlayer.shipFeet(ship: Ship): BlockPos {
    val local = ship.transform.worldToShip.transformPosition(Vector3d(x, y, z))
    return BlockPos.containing(local.x, local.y, local.z)
}

private fun shipYaw(ship: Ship): Float {
    val forward = ship.transform.shipToWorldRotation.transform(Vector3d(0.0, 0.0, 1.0))
    return Math.toDegrees(Math.atan2(-forward.x, forward.z)).toFloat()
}

private fun findStatueSpot(level: ServerLevel, feet: BlockPos): BlockPos? {
    val probe = BlockPos.MutableBlockPos()
    for (dy in 0..MAX_RISE) {
        if (fits(level, probe.set(feet.x, feet.y + dy, feet.z))) return probe.immutable()
    }
    for (dy in 0..MAX_RISE) {
        for (dx in -SIDE_REACH..SIDE_REACH) {
            for (dz in -SIDE_REACH..SIDE_REACH) {
                if (dx == 0 && dz == 0) continue
                if (fits(level, probe.set(feet.x + dx, feet.y + dy, feet.z + dz))) return probe.immutable()
            }
        }
    }
    return null
}

// Only replaceable cells with no block entity, so the statue never destroys stored items.
private fun fits(level: ServerLevel, lower: BlockPos): Boolean {
    if (lower.y < level.minBuildHeight || lower.y + 1 >= level.maxBuildHeight) return false
    return isFree(level, lower) && isFree(level, lower.above())
}

private fun isFree(level: ServerLevel, pos: BlockPos): Boolean =
    level.getBlockState(pos).canBeReplaced() && level.getBlockEntity(pos) == null
