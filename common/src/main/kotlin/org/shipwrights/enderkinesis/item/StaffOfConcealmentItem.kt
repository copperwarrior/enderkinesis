package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
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
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/** Staff of Concealment — hold right-click to cloak the ship under the wielder's
 *  crosshair. The target ship is captured at the moment right-click starts; the cloak
 *  fades in over 5 seconds, persists for as long as the player holds, and fades back
 *  out over 5 seconds when released. Entities riding/standing on the cloaked ship are
 *  visually included in the fade.
 *
 *  The vanilla "use animation" pipeline (`getUseDuration` + `releaseUsing` +
 *  `finishUsingItem`) drives the hold loop, so the staff also benefits from vanilla's
 *  built-in cleanup hooks: swapping hotbar slots, taking damage with "block while
 *  hurt" enabled, dying, etc. all release the cloak automatically. Disconnects are
 *  caught by [ShipCloakingTracker.init]'s `PLAYER_QUIT` handler. */
class StaffOfConcealmentItem(properties: Properties) : Item(properties) {

    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.BOW
    /** Effectively infinite — only the release/cancel paths end the cloak. */
    override fun getUseDuration(stack: ItemStack): Int = 72_000

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        val ship = raycastShip(level, player)
            ?: return InteractionResultHolder.fail(stack)

        if (level is ServerLevel && player is ServerPlayer) {
            ShipCloakingTracker.startCloak(level, ship.id, player)
        }
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    /** Per-tick during the hold. No work needed — the server-side fade is timed
     *  client-side from the cloak-start packet, and the server holds the cloak active
     *  until release. Kept as a hook in case future work wants to gate per-tick. */
    override fun onUseTick(level: Level, livingEntity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        // intentionally empty
    }

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        if (level is ServerLevel && entity is ServerPlayer) {
            ShipCloakingTracker.stopCloak(level, entity)
        }
    }

    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        // Would only fire if getUseDuration ran out — kept as a safety net for the
        // disconnect / item-swap / death paths to release the cloak.
        if (level is ServerLevel && entity is ServerPlayer) {
            ShipCloakingTracker.stopCloak(level, entity)
        }
        return stack
    }

    private fun raycastShip(level: Level, player: Player): Ship? {
        val eye = player.getEyePosition(1f)
        val look = player.getViewVector(1f)
        val end = eye.add(look.scale(REACH))
        val ctx = ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
        val hit = level.clip(ctx)
        if (hit.type != HitResult.Type.BLOCK) return null
        // hit.blockPos is in shipyard frame for ship blocks; getLoadedShipManagingPos
        // is keyed off shipyard pos too, so this is the canonical lookup.
        return level.getLoadedShipManagingPos(hit.blockPos)
    }

    companion object {
        /** Maximum raycast distance for picking the target ship. Vanilla reach is ~5;
         *  64 lets the wielder cloak ships from a safe distance. */
        private const val REACH: Double = 64.0
    }
}
