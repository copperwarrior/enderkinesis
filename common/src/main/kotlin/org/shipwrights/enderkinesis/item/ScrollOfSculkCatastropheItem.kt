package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.util.SculkSpread

/**
 * Scroll of Sculk Catastrophe — single-use right-click consumable that
 * sculk-converts a 7×5×7 ellipsoid centred on the player.
 *
 *  - **Generates** on the lectern in the `sselith_specimen_sculk` structure.
 *  - **Right-click in air** consumes the scroll and routes through
 *    [SculkSpread.spread] with the canonical catastrophe radius.
 *  - **Right-click on an empty lectern** places the scroll there (via the
 *    inherited [TomeItem.useOn]); right-click on a lectern that already
 *    holds the scroll opens its book view, paginated through
 *    [TomeLore.fromSselithBook].
 *
 *  Extends [TomeItem] for the lectern integration (place / hasBook /
 *  open-book screen) so the existing tome mixins handle the menu plumbing
 *  for free. The on-lectern *texture* is swapped to a flat scroll page by
 *  [org.shipwrights.enderkinesis.mixin.LecternRendererScrollFlatPageMixin]
 *  — vanilla's BookModel never paints over the scroll.
 */
class ScrollOfSculkCatastropheItem(properties: Properties) : TomeItem(properties) {

    /** Scrolls are mundane parchment — no enchant glow. */
    override fun isFoil(stack: ItemStack): Boolean = false

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Scroll of Sculk Catastrophe", "The Doom-Sayer", "scroll_of_sculk_catastrophe")

    override fun use(
        level: Level, player: Player, hand: InteractionHand,
    ): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide || level !is ServerLevel) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
        }
        // Centre the bloom on the player's feet. Foot-Y puts the sphere
        // mostly into the floor under the caster, so the catastrophe
        // visibly chews the ground beneath them rather than carving a
        // pocket out of the air around their head.
        val cx = player.blockX
        val cy = player.blockY
        val cz = player.blockZ
        SculkSpread.start(level, BlockPos(cx, cy, cz))
        if (!player.abilities.instabuild) stack.shrink(1)
        player.cooldowns.addCooldown(this, COOLDOWN_TICKS)
        return InteractionResultHolder.sidedSuccess(stack, false)
    }

    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(
            Component.translatable("item.enderkinesis.scroll_of_sculk_catastrophe.tooltip")
                .withStyle(ChatFormatting.GRAY),
        )
    }

    companion object {
        private const val COOLDOWN_TICKS = 20
    }
}
