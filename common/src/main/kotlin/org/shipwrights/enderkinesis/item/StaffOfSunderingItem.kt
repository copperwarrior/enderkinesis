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

/** Staff of Sundering — right-click + hold to fire a 128-block laser beam that
 *  ramps up through four visual+damage stages over [SunderingManager.STAGE_TICKS]
 *  × 3 game ticks. Each stage adds an effect layer on top of the previous:
 *
 *  - **Stage 1** — narrow orange-to-white particle beam, sets mobs alight, deals
 *    Sharpness-I-iron-sword damage per second (interp floor).
 *  - **Stage 2** — adds a clockwise ring of fire particles at the staff tip.
 *  - **Stage 3** — beam becomes a beacon-style rotating laser, slow block
 *    breaking (Disintegration-baseline hardness × 30 ticks), plus a second
 *    counter-clockwise outer ring at the tip.
 *  - **Stage 4** — colour shifts toward white, helical fire-particle spiral
 *    along the beam length, block breaking at Efficiency-V speed
 *    (~27× faster), Sharpness-V-diamond-sword damage per second (interp ceil).
 *
 *  Server-side per-tick logic (raycast, damage, fire, block mining) is in
 *  [SunderingManager]; the visuals are in
 *  [org.shipwrights.enderkinesis.client.SunderingClient].
 *
 *  Same use-and-hold shape as the Density / Scales / Aegis staves. */
class StaffOfSunderingItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        player.startUsingItem(hand)
        return InteractionResultHolder.consume(stack)
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE

    // BOW = "drawing a bowstring" arm pose + slows the player slightly. Reads as
    // the wielder focusing on the laser ramp-up; matches the "ramping power"
    // feel better than BLOCK or NONE.
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.BOW

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (level !is ServerLevel) return
        if (entity !is Player) return
        SunderingManager.tick(level, entity)
    }

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        if (level is ServerLevel && entity is Player) {
            SunderingManager.release(entity)
        }
    }
}
