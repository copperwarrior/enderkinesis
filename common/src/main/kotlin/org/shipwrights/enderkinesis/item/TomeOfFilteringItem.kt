package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Tome of Filtering — sets the per-orb filter consulted by every
 * filter-aware tome (item-mover Transportation, Vacuum, and the
 * Disintegration tome's block + entity gates). Unlike the linking
 * tomes this one targets *orbs themselves*, not orb pairs.
 *
 * **Works on every orb regardless of link state or role.** Whether
 * the orb is idle (unlinked), pure SEND, pure RECEIVE, or playing
 * both roles in different tomes' networks, right-clicking with the
 * tome of filtering sets the filter — so a player can pre-configure
 * a filter on a fresh orb before wiring it into a network.
 *
 * Gesture: right-click an orb with the tome in **either** hand and a
 * filter target in the **other** hand:
 *  - Filter target → set as filter (single-count copy, NBT preserved).
 *  - Empty other hand → clears the filter so the orb accepts / pulls
 *    everything again.
 *
 * The handler is registered on [org.shipwrights.enderkinesis.block.OrbOfLinkingBlock.use]
 * rather than here so the gesture wins out over the block's own
 * enchanted-book and empty-hand branches, which would otherwise
 * consume the click on certain main-hand items before this item's
 * `useOn` could fire. This item's `useOn` only handles the lectern
 * placement inherited from [TomeItem] — when right-clicking an orb,
 * the block intercept above always runs first and short-circuits
 * before this method is called.
 *
 * The filter lives on the orb's [OrbOfLinkingBlockEntity], persists
 * across save/load, and is shared between every filter-aware tome on
 * that orb (one filter per orb, not per tome).
 *
 * **Filter spec.** Plain item / block item → match that item type
 * exactly. Written book or book and quill → every page split on
 * `\n`, every non-blank line is its own rule:
 *  - `minecraft:iron_sword` — item or block id.
 *  - `#minecraft:arrows` — item / block tag.
 *  - `minecraft:diamond_sword{nbt}` — NBT subset match.
 *  - `minecraft:diamond_sword[enchantments={sharpness:4}]` —
 *    bracket component syntax for enchantments.
 *
 * See [org.shipwrights.enderkinesis.item.OrbFilter] for the parser /
 * predicate details.
 */
class TomeOfFilteringItem(properties: Properties) : TomeItem(properties) {

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Filtering", "The Sieve", "tome_of_filtering")

    override fun useOn(context: UseOnContext): InteractionResult {
        // Orb-click case is handled in OrbOfLinkingBlock.use (which
        // runs *before* this method in the vanilla interact pipeline)
        // so the gesture wins out over the block's enchanted-book and
        // empty-hand branches. Everything else (notably lectern
        // placement) falls through to TomeItem.useOn.
        val state = context.level.getBlockState(context.clickedPos)
        if (state.block === EKBlocks.ORB_OF_LINKING.get()) return InteractionResult.PASS
        return super.useOn(context)
    }

    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(
            Component.translatable("item.enderkinesis.tome_of_filtering.tooltip.set")
                .withStyle(ChatFormatting.DARK_GRAY),
        )
        tooltip.add(
            Component.translatable("item.enderkinesis.tome_of_filtering.tooltip.clear")
                .withStyle(ChatFormatting.DARK_GRAY),
        )
    }
}
