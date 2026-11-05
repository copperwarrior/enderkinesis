package org.shipwrights.enderkinesis.item

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.UseAnim
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.client.ShellOfTidesEffects
import org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock
import org.shipwrights.enderkinesis.registry.EKSounds

class ShellOfTidesItem(properties: Properties) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        if (level.getBlockState(context.clickedPos).block !is CrepusculiteLatticeBlock) return InteractionResult.PASS
        if (!level.isClientSide) {
            ShellOfTidesBinding.bind(context.itemInHand, level, context.clickedPos)
            level.playSound(null, context.clickedPos, EKSounds.SHELL_OF_TIDES_BIND.get(), SoundSource.PLAYERS, 1f, 1f)
            context.player?.displayClientMessage(Component.translatable("message.enderkinesis.shell_of_tides.bound"), true)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        player.startUsingItem(hand)
        val sound = if (ShellOfTidesBinding.latticeInReach(level, player, stack) != null) {
            EKSounds.SHELL_OF_TIDES_BLOW_START
        } else {
            EKSounds.SHELL_OF_TIDES_UNANSWERED
        }
        // Same pattern as vanilla InstrumentItem: both sides call it, and the server skips the user.
        level.playSound(player, player, sound.get(), SoundSource.RECORDS, HORN_VOLUME, 1f)
        return InteractionResultHolder.consume(stack)
    }

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        if (entity !is Player) return
        if (ShellOfTidesBinding.latticeInReach(level, entity, stack) == null) return
        level.playSound(entity, entity, EKSounds.SHELL_OF_TIDES_BLOW_STOP.get(), SoundSource.RECORDS, HORN_VOLUME, 1f)
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.TOOT_HORN

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        val lattice = ShellOfTidesBinding.latticeInReach(level, entity, stack)
        if (level.isClientSide) {
            if (lattice != null) ShellOfTidesEffects.tick(level, entity, lattice)
            return
        }
        if (level !is ServerLevel) return
        if (lattice == null) {
            if (entity.ticksUsingItem == 0) {
                val key = if (ShellOfTidesBinding.isBound(stack)) "out_of_reach" else "unbound"
                (entity as? Player)?.displayClientMessage(
                    Component.translatable("message.enderkinesis.shell_of_tides.$key"), true,
                )
            }
            return
        }
        // MC pitch is negative when the entity looks up.
        val delta = -entity.xRot / MAX_PITCH * MAX_BLOCKS_PER_TICK
        if (delta == 0.0) return
        val newLevel = ShellOfTidesBinding.oceanOwner(level, lattice).nudgeSeaLevel(delta) ?: return
        (entity as? Player)?.displayClientMessage(
            Component.translatable("message.enderkinesis.shell_of_tides.sea_level", "%.1f".format(newLevel)),
            true,
        )
    }

    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(Component.translatable("item.enderkinesis.shell_of_tides.tooltip"))
        val key = if (ShellOfTidesBinding.isBound(stack)) "bound" else "unbound"
        tooltip.add(Component.translatable("item.enderkinesis.shell_of_tides.tooltip.$key"))
    }

    companion object {
        /** Sea-level change in blocks per second when the player looks straight up or down. */
        const val MAX_BLOCKS_PER_SECOND: Double = 4.0
        private const val MAX_BLOCKS_PER_TICK: Double = MAX_BLOCKS_PER_SECOND / 20.0
        private const val MAX_PITCH: Double = 90.0
        /** Vanilla goat horns use 16 (256-block range). */
        const val HORN_VOLUME: Float = 16f
    }
}
