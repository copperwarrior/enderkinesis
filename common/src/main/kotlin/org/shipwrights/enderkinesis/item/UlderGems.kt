package org.shipwrights.enderkinesis.item

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellContext
import org.shipwrights.enderkinesis.spell.SpellUse

class UlderGemItem(val element: Element, private val shard: () -> Item, properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this))
        if (level !is ServerLevel) return InteractionResultHolder.sidedSuccess(stack, true)
        if (!player.abilities.instabuild) stack.shrink(1)
        // Merges into shard stacks, then free slots, then drops the rest. Before the burst, so a Chaos burst cannot
        // teleport the player away from dropped shards.
        player.inventory.placeItemBackInInventory(ItemStack(shard(), SHARDS_PER_GEM))
        SpellCasting.burst(SpellContext(level, player, player.position(), use = SpellUse.AREA), Spell(listOf(element)), player.position(), area = true)
        // useItem sets the hand from this; the emptied gem stack would delete shards that went into the hand slot.
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), false)
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += element.displayName()
    }

    companion object {
        /** One gem's shards make exactly one maximum-strength single-element scroll. */
        const val SHARDS_PER_GEM = 7
    }
}

class UlderShardItem(val element: Element, properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += element.displayName()
    }
}
