package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.client.OldPaperClient
import org.shipwrights.enderkinesis.dimension.AlmanacData
import org.shipwrights.enderkinesis.dimension.DimensionNames
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.util.Sga

/**
 * The Almanac of Everywhere. Obtained by right-clicking a crepusculite lattice with a book; it
 * records every dimension the *item* has been to.
 *
 * Tracking is event-driven on the item itself:
 *  - [inventoryTick] stamps the current dimension whenever the almanac sits in an entity's
 *    inventory — this covers being picked up into an inventory and the carrying player changing
 *    dimension.
 *  - `EntityChangeDimensionMixin` stamps a dropped almanac item-entity when it changes dimension,
 *    the one case [inventoryTick] cannot observe.
 *
 * [AlmanacData.addVisited] is idempotent, so re-stamping an already-known dimension is a no-op.
 *
 * Lectern placement + book rendering come from the [TomeItem] base — the lectern stores the *real*
 * almanac stack (preserving its recorded dimensions) and the lectern mixins ask [writtenBook] for
 * the display copy on open.
 */
class AlmanacOfEverywhereItem(properties: Properties) : TomeItem(properties) {

    override fun writtenBook(stack: ItemStack): ItemStack = AlmanacLore.writtenBook()

    override fun pageCount(stack: ItemStack): Int = AlmanacLore.pageCount()

    /** The first creative right-click records every dimension the server has loaded; after that,
     *  and always in survival, right-click opens the book.
     *
     *  Populate uses [AlmanacData.addEntryTrusted], not the [AlmanacData.addVisited] gate, so the
     *  hidden Ygann's Abyss entry gets recorded too. */
    override fun use(
        level: Level, player: Player, hand: InteractionHand
    ): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (player.isCreative && !AlmanacData.isCreativePopulated(stack)) {
            if (!level.isClientSide) populate(level, player, stack)
        } else if (level.isClientSide) {
            // Null skin: same view as a vanilla lectern.
            OldPaperClient.open(writtenBook(stack), skin = null)
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    private fun populate(level: Level, player: Player, stack: ItemStack) {
        val server = level.server ?: return
        var added = 0
        for (lvl in server.allLevels) {
            if (AlmanacData.addEntryTrusted(stack, lvl.dimension().location())) added++
        }
        AlmanacData.markCreativePopulated(stack)
        player.displayClientMessage(
            Component.translatable("message.enderkinesis.almanac.populated", added), true
        )
    }

    override fun inventoryTick(
        stack: ItemStack, level: Level, entity: Entity, slotId: Int, isSelected: Boolean
    ) {
        if (!level.isClientSide) {
            AlmanacData.addVisited(stack, level.dimension().location())
        }
    }

    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag
    ) {
        val visited = AlmanacData.visited(stack)
        if (visited.isEmpty()) {
            tooltip.add(Component.translatable("item.enderkinesis.almanac_of_everywhere.empty"))
            return
        }
        tooltip.add(Component.translatable("item.enderkinesis.almanac_of_everywhere.count", visited.size))
        for (dim in visited) {
            // Friendly dimension name (fancy translation, else bare path), galactic-alphabet font.
            // Ygann's Abyss gets DARK_PURPLE styling — visual cue that this is the hidden
            // dimension the lectern almanac seeds, distinct from the regular entries.
            val line = Sga.obfuscate(DimensionNames.display(dim))
            if (dim == YgannAbyss.ID) line.withStyle(ChatFormatting.DARK_PURPLE)
            tooltip.add(line)
        }
    }
}
