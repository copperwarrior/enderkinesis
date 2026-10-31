package org.shipwrights.enderkinesis.item

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.item.ItemEntity
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

class MysticGemItem(val element: Element, private val shard: () -> Item, properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level is ServerLevel) {
            if (!player.abilities.instabuild) stack.shrink(1)
            // Give the shards before the burst. Otherwise a Chaos burst can teleport the player away from them.
            giveShards(level, player)
            SpellCasting.burst(SpellContext(level, player, player.position(), use = SpellUse.AREA), Spell(listOf(element)), player.position(), area = true)
        }
        player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this))
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    private fun giveShards(level: ServerLevel, player: Player) {
        val shards = ItemStack(shard(), SHARDS_PER_GEM)
        player.inventory.add(shards)
        if (shards.isEmpty) return
        val drop = ItemEntity(level, player.x, player.y, player.z, shards)
        drop.setDefaultPickUpDelay()
        level.addFreshEntity(drop)
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += element.displayName()
    }

    companion object {
        /** One gem's shards make exactly one maximum-strength single-element scroll. */
        const val SHARDS_PER_GEM = 7
    }
}

class MysticShardItem(val element: Element, properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += element.displayName()
    }
}
