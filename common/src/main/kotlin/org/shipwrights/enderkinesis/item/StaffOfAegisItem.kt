package org.shipwrights.enderkinesis.item

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.Level

/** Staff of Aegis — right-click + hold to project an oriented box 4 blocks in
 *  front of the player. While held:
 *   - projectile entities crossing the box are reflected back along their
 *     incoming direction (with owner reassignment so reflection works for
 *     Ghast-style acceleration-based projectiles too);
 *   - entities in the `deflectable` entity-type tag (TNT, TNT minecart by
 *     default) are pushed away from the player;
 *   - ships overlapping the box are pushed away too, except for the ship
 *     the player is currently riding / dragging.
 *  Per-tick logic is in [AegisManager]; the wireframe + glowing-particle
 *  render is in [org.shipwrights.enderkinesis.client.AegisClient].
 *
 *  Same use-and-hold pattern as the Density / Scales staves: `use` starts
 *  the use action with an effectively infinite duration; `onUseTick` calls
 *  the per-tick effect logic server-side. */
class StaffOfAegisItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.BLOCK

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (level !is ServerLevel) return
        if (entity !is Player) return
        AegisManager.tick(level, entity)
    }

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        // Nothing to clean up — the box only exists while `isUsingItem` is
        // true, and the client renderer keys off that flag directly.
    }
}
