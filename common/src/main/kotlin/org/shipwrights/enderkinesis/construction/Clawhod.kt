@file:OptIn(org.valkyrienskies.core.api.util.GameTickOnly::class, org.valkyrienskies.core.api.VsBeta::class)

package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import org.shipwrights.enderkinesis.blockentity.ConstriliarPlatformBlockEntity
import org.shipwrights.enderkinesis.block.ClawhodBlock
import org.shipwrights.enderkinesis.blockentity.ClawhodBlockEntity
import org.shipwrights.enderkinesis.entity.ConstriliarCargo
import org.shipwrights.enderkinesis.entity.ConstriliarEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipManagingPos
import java.util.UUID

// Server only, all of them. A clawhod holds items in two slots per hand.

/** The block a clawhod may hold from [stack], or null. */
fun clawhodBlockOf(stack: ItemStack): Block? {
    val item = stack.item as? BlockItem ?: return null
    return if (stack.isEmpty || stack.hasTag() || item.block.defaultBlockState().isAir) null else item.block
}

/** Stands the clawhod at [pos] on a ship with its column up and its hands pointing away from [player], in the
 *  ship's own axes. The placement context reads the player's world yaw, which is wrong on a turned ship. */
fun ServerLevel.faceClawhodToward(pos: BlockPos, player: Player) {
    val state = getBlockState(pos)
    if (!state.`is`(EKBlocks.CLAWHOD.get())) return
    val ship = getShipManagingPos(pos) ?: return
    val local = ship.worldToShip.transformPosition(Vector3d(player.x, player.y, player.z))
    val heading = Direction.getNearest(pos.x + 0.5 - local.x, 0.0, pos.z + 0.5 - local.z)
    setBlock(pos, state.setValue(ClawhodBlock.FACING, Direction.UP).setValue(ClawhodBlock.HEADING, heading), Block.UPDATE_ALL)
}

/** Moves what fits of the held [stack] into the hand slots at [pos] and shrinks [stack] by that count (not in
 *  creative). True when anything moved. */
fun ServerLevel.playerInsertClawhod(pos: BlockPos, player: ServerPlayer, stack: ItemStack): Boolean {
    val clawhod = getBlockEntity(pos) as? ClawhodBlockEntity ?: return false
    if (stack.isEmpty) return false
    if (isClawhodTool(stack)) return insertTool(clawhod, player, stack)
    var left = stack.count
    for (h in 0 until CLAWHOD_HANDS) {
        if (left == 0) break
        // The user's rule: a hand with a tool takes nothing; only the free hand does.
        if (clawhod.worker?.holdsWorkingTool(h) ?: !handTool(clawhod.hand(h)).isEmpty) continue
        val hand = clawhod.hand(h)
        val taken = pickupInsert(hand, stack.copyWithCount(left), minOf(pickupRoom(hand, stack), left))
        if (taken > 0) clawhod.handsChanged()
        left -= taken
    }
    val taken = stack.count - left
    if (taken > 0 && !player.abilities.instabuild) stack.shrink(taken)
    return taken > 0
}

// The user's rule: one working tool per Clawhod, in one hand; carried tools do not count.
private fun insertTool(clawhod: ClawhodBlockEntity, player: ServerPlayer, stack: ItemStack): Boolean {
    if ((0 until CLAWHOD_HANDS).any { clawhod.workToolSlot(it) >= 0 }) return false
    for (h in 0 until CLAWHOD_HANDS) {
        val hand = clawhod.hand(h)
        val free = hand.indexOfFirst { it.isEmpty }
        if (free < 0) continue
        hand[free] = stack.copyWithCount(1)
        clawhod.handsChanged()
        if (!player.abilities.instabuild) stack.shrink(1)
        return true
    }
    return false
}

/** Gives the player everything in the hands. True when anything moved. */
fun ServerLevel.playerTakeFromClawhod(pos: BlockPos, player: ServerPlayer): Boolean {
    val clawhod = getBlockEntity(pos) as? ClawhodBlockEntity ?: return false
    val out = clawhod.takeAllItems()
    for (stack in out) player.inventory.placeItemBackInInventory(stack)
    return out.isNotEmpty()
}

internal class Carrier(val onWalker: Boolean, val entity: ConstriliarEntity?)

/** The walker whose ship carries the clawhod at [pos]. It scans the ship's blocks, so call it on a change only. */
internal fun ServerLevel.carrierAt(pos: BlockPos): Carrier {
    val ship = getLoadedShipManagingPos(pos) ?: return Carrier(false, null)
    val platform = EKBlocks.CONSTRILIAR_PLATFORM.get()
    var onWalker = false
    var walkerId: UUID? = null
    ConstriliarCargo.forEachShipBlock(this, ship, CLAWHOD_SCAN_BUDGET) { cell, state ->
        if (state.`is`(platform)) {
            onWalker = true
            if (walkerId == null) walkerId = (getBlockEntity(cell) as? ConstriliarPlatformBlockEntity)?.ownerUuid
        }
    }
    return Carrier(onWalker, walkerId?.let { getEntity(it) as? ConstriliarEntity })
}

private const val CLAWHOD_SCAN_BUDGET = 4096
