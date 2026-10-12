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


/** Staff of Scales — right-click + hold on a target VS2 ship; mouse Y while held
 *  rescales the ship live between 0.25× and 1.5× of its transform-scale. The
 *  camera still tracks the mouse normally; the same pitch delta also feeds the
 *  scale accumulator (clamped to ±800 px from the anchor scale). The client
 *  streams the current scale to the server once per game tick while it changes,
 *  so the ship resizes as the player moves the mouse.
 *
 *  Same interaction shape as [StaffOfDensityItem]; the only differences are the
 *  multiplier range and that the underlying VS2 mutation is a first-class
 *  `setScaling(Vector3dc)` call, not a private-API mass tweak. */
class StaffOfScalesItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) {
            // Try a ship pick first (VS2's shipyard-mapped `level.clip`),
            // then fall back to a cage-body pick — standalone VS bodies
            // like the Ga'Mylan cage don't own blocks, so `level.clip`
            // never resolves them and we'd otherwise refuse to open a
            // session on a crystal the player is clearly aiming at.
            // The BEGIN payload is empty either way; the server runs
            // both pickers itself (cage first, then ship) and caches
            // whichever hit.
            val shipId = clientRaycastShip(level, player)
            val cageBodyId = if (shipId == null) clientRaycastCageBody(player) else null
            val handleId = shipId ?: cageBodyId
            if (handleId == null) return InteractionResultHolder.pass(stack)
            org.shipwrights.enderkinesis.client.ScalesClient.beginSession(handleId)
            val buf = FriendlyByteBuf(Unpooled.buffer())
            NetworkManager.sendToServer(ScalesNetwork.BEGIN, buf)
        }
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    /** Client cage-body picker — 64-block ray-sphere against every
     *  cage in the player's current dimension. Mirrors the shape of
     *  [clientRaycastShip] so the client HUD gets a handle to display
     *  before the server's authoritative pick lands in STATE. */
    private fun clientRaycastCageBody(player: Player): Long? {
        val eye = player.getEyePosition(1f)
        val look = player.getViewVector(1f)
        val entry = org.shipwrights.enderkinesis.client.GamylanCageRaytrace
            .findCageOnRay(eye, look, REACH) ?: return null
        return entry.bodyId
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.NONE

    /** Runs on both sides while the staff is held. Client side fires the live
     *  scale stream so the server resizes the ship as the mouse moves. */
    override fun onUseTick(level: Level, livingEntity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (!level.isClientSide) return
        org.shipwrights.enderkinesis.client.ScalesClient.streamTickIfActive()
    }

    override fun releaseUsing(stack: ItemStack, level: Level, livingEntity: LivingEntity, timeLeft: Int) {
        if (level.isClientSide) {
            if (livingEntity is Player) {
                org.shipwrights.enderkinesis.client.ScalesClient.endSession()
            }
        } else if (livingEntity is net.minecraft.server.level.ServerPlayer) {
            // Drop the cached server-side picked ship so a stale ref doesn't
            // accept a stray COMMIT packet after the release.
            ScalesNetwork.clearActive(livingEntity)
        }
    }

    companion object {
        /** Matches [StaffOfConcealmentItem]'s reach — vanilla `mc.hitResult` only
         *  goes ~5 blocks, so we run our own clip at the same range Concealment
         *  uses to pick the target ship. */
        private const val REACH: Double = 64.0

        /** Picks the VS2 ship under the player's crosshair within [REACH]. Uses
         *  VS2's `level.clip` mixin (shipyard-mapped `blockPos`) so a ship block
         *  resolves to the ship that owns it. */
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
