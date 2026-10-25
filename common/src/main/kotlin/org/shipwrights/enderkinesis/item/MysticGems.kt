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
import org.shipwrights.enderkinesis.spell.Delivery
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.ElementEffects
import org.shipwrights.enderkinesis.spell.SpellContext
import org.shipwrights.enderkinesis.spell.SpellCurves

/**
 * Mystic Gem. Using it fires its element's burst and shatters it into [SHARDS_PER_GEM]
 * shards of that element. The burst is exactly a 1-shard Area cast of the element (see
 * [ElementEffects]), so it always fires: shatter gems somewhere safe.
 *
 * Order matters: the gem is used up and the shards are handed over (or dropped at the
 * user's feet when the inventory is full) before the burst resolves, so a Chaos burst can't
 * teleport the player away from their own shards.
 */
class MysticGemItem(val element: Element, private val shard: () -> Item, properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level is ServerLevel) {
            if (!player.abilities.instabuild) stack.shrink(1)
            giveShards(level, player)
            val ctx = SpellContext(level, player, player.position())
            val delivery = Delivery.Sphere(player.position(), SpellCurves.areaRadius(1f), area = true)
            ElementEffects.of(element).apply(ctx, delivery, 1f)
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

/** A shard of a shattered Mystic Gem: one glyph of [element], inscribed at the Altar of
 *  Illumination. */
class MysticShardItem(val element: Element, properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += element.displayName()
    }
}
