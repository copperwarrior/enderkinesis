package org.shipwrights.enderkinesis.item

import net.minecraft.advancements.CriteriaTriggers
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import org.shipwrights.enderkinesis.registry.EKSounds

class MundaneStoneItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        if (MundaneIncubation.incubating(player)) return InteractionResultHolder.fail(player.getItemInHand(hand))
        return super.use(level, player, hand)
    }

    override fun getEatingSound(): SoundEvent = SoundEvents.EMPTY

    // Not super: Player.eat plays the burp. The stone has no food value and no effects.
    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        if (entity is ServerPlayer) {
            if (!MundaneIncubation.start(entity)) {
                entity.displayClientMessage(Component.translatable("item.enderkinesis.mundane_stone.incubating"), true)
                // The client already took the stone off its stack.
                entity.inventoryMenu.sendAllDataToRemote()
                return stack
            }
            level.playSound(null, entity.x, entity.y, entity.z, EKSounds.MUNDANE_STONE_SWALLOW.get(), SoundSource.PLAYERS, 1f, 1f)
            entity.awardStat(Stats.ITEM_USED.get(this))
            CriteriaTriggers.CONSUME_ITEM.trigger(entity, stack)
        }
        if (entity !is Player || !entity.abilities.instabuild) stack.shrink(1)
        entity.gameEvent(GameEvent.EAT)
        return stack
    }
}
