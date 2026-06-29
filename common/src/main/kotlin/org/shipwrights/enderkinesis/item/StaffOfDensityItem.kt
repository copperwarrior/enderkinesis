package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/** Staff of Density — right-click + hold on a target VS2 ship; mouse Y while held
 *  scales the ship's mass live between 0.5× and 2× of its baseline. The camera
 *  still tracks the mouse normally; the same pitch delta also feeds the
 *  multiplier accumulator (clamped to ±800 px from the anchor). The client
 *  streams the current multiplier to the server once per game tick while it
 *  changes, so the mass adjusts as the player moves the mouse.
 *
 *  Activation flow:
 *  1. [use] — pick the ship under the crosshair (via VS2's shipyard-coord
 *     `BlockHitResult.blockPos` → `getLoadedShipManagingPos`). On a hit, register
 *     the target with [org.shipwrights.enderkinesis.client.DensityClient] and send
 *     a BEGIN packet so the server echoes back the current multiplier; start the
 *     use action so [Player.isUsingItem] returns true (the mixin gates on it).
 *  2. [releaseUsing] — [org.shipwrights.enderkinesis.client.DensityClient.endSession]
 *     flushes the final multiplier and clears local state. */
class StaffOfDensityItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) {
            val shipId = clientRaycastShip(level, player) ?: return InteractionResultHolder.pass(stack)
            org.shipwrights.enderkinesis.client.DensityClient.beginSession(shipId)
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeLong(shipId)
            NetworkManager.sendToServer(DensityNetwork.BEGIN, buf)
        }
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.NONE

    /** Runs on both sides while the staff is held. Client side fires the live
     *  multiplier stream so the server adjusts mass as the mouse moves. */
    override fun onUseTick(level: Level, livingEntity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (!level.isClientSide) return
        org.shipwrights.enderkinesis.client.DensityClient.streamTickIfActive()
    }

    override fun releaseUsing(stack: ItemStack, level: Level, livingEntity: LivingEntity, timeLeft: Int) {
        if (!level.isClientSide) return
        if (livingEntity !is Player) return
        org.shipwrights.enderkinesis.client.DensityClient.endSession()
    }

    companion object {
        /** Matches [StaffOfConcealmentItem]'s reach — vanilla `mc.hitResult` only
         *  goes ~5 blocks, so we run our own clip at the same range Concealment
         *  uses to pick the target ship. */
        private const val REACH: Double = 64.0

        /** Picks the VS2 ship under the player's crosshair within [REACH]. Uses
         *  VS2's `level.clip` mixin (shipyard-mapped `blockPos`) so a ship block
         *  resolves to the ship that owns it. Server re-validates the target via
         *  [DensityManager.commitMultiplier]. */
        @JvmStatic
        fun clientRaycastShip(level: Level, player: Player): Long? {
            val eye = player.getEyePosition(1f)
            val end = eye.add(player.getViewVector(1f).scale(REACH))
            val ctx = ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
            val hit = level.clip(ctx)
            if (hit.type != HitResult.Type.BLOCK) return null
            val ship = level.getLoadedShipManagingPos(hit.blockPos) ?: return null
            return ship.id
        }
    }
}
