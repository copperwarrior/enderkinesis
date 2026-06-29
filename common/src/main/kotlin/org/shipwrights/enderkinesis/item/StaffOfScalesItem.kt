package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
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

private val LOG = LogUtils.getLogger()

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
        LOG.info("[Scales] use() side={} hand={}", if (level.isClientSide) "CLIENT" else "SERVER", hand)
        if (level.isClientSide) {
            val shipId = clientRaycastShip(level, player)
            LOG.info("[Scales] use() client raycast shipId={}", shipId)
            if (shipId == null) return InteractionResultHolder.pass(stack)
            org.shipwrights.enderkinesis.client.ScalesClient.beginSession(shipId)
            // BEGIN payload is empty — the server does its own raycast and
            // caches the picked ship. Client's shipId is used for the local
            // HUD only.
            val buf = FriendlyByteBuf(Unpooled.buffer())
            NetworkManager.sendToServer(ScalesNetwork.BEGIN, buf)
        }
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.NONE

    /** Runs on both sides while the staff is held. Client side fires the live
     *  scale stream so the server resizes the ship as the mouse moves. */
    override fun onUseTick(level: Level, livingEntity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (!level.isClientSide) return
        // Sample once a second so we know the tick is firing without flooding the log.
        if (remainingUseDuration % 20 == 0) {
            LOG.info("[Scales] onUseTick CLIENT remaining={} isUsing={}",
                remainingUseDuration, livingEntity.isUsingItem)
        }
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
