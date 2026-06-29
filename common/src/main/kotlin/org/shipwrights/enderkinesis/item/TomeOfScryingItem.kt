package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import org.shipwrights.enderkinesis.block.OrbOfLinkingBlock
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Tome of Scrying — single-use conversion. Right-click an Orb of Linking with this tome to
 * replace it in-place with an [org.shipwrights.enderkinesis.block.OrbOfScryingBlock] of the
 * same FACING. The tome itself is consumed.
 *
 * Conversion is REFUSED if the target orb has any active links (outgoing or incoming) —
 * scrying it would silently drop those, which is a footgun. Players have to dismantle the
 * orb's tome network first.
 *
 * Right-click on a lectern (with no book) plumbs the written-book lore via the base class.
 */
class TomeOfScryingItem(properties: Properties) : TomeItem(properties) {

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Scrying", "The Watcher", "tome_of_scrying")

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)

        // Only act on an Orb of Linking. Lectern / other paths fall through to the base.
        if (state.block !== EKBlocks.ORB_OF_LINKING.get()) return super.useOn(context)

        val be = level.getBlockEntity(pos) as? OrbOfLinkingBlockEntity ?: return InteractionResult.PASS
        val player = context.player ?: return InteractionResult.PASS

        if (be.allOutgoing().isNotEmpty() || be.allIncoming().isNotEmpty()) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                    Component.translatable("item.enderkinesis.tome_of_scrying.refuse_linked")
                        .withStyle(ChatFormatting.RED),
                    true,
                )
            }
            return InteractionResult.FAIL
        }

        if (level.isClientSide) return InteractionResult.SUCCESS

        // Preserve FACING so the scrying orb sits on the same surface as the source orb.
        val facing = state.getValue(OrbOfLinkingBlock.FACING)
        val scrying = EKBlocks.ORB_OF_SCRYING.get().defaultBlockState()
            .setValue(org.shipwrights.enderkinesis.block.OrbOfScryingBlock.FACING, facing)
        // setBlock flag 3 = block update + neighbour notification; matches the orb's own state
        // mutations elsewhere in the codebase. The level handles BE swap + POI update.
        level.setBlock(pos, scrying, Block.UPDATE_ALL)

        // Consume the tome (creative-instabuild keeps it).
        if (!player.abilities.instabuild) {
            context.itemInHand.shrink(1)
        }
        player.displayClientMessage(
            Component.translatable("item.enderkinesis.tome_of_scrying.converted")
                .withStyle(ChatFormatting.GRAY),
            true,
        )
        return InteractionResult.CONSUME
    }

    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(
            Component.translatable("item.enderkinesis.tome_of_scrying.tooltip.use")
                .withStyle(ChatFormatting.GRAY),
        )
        tooltip.add(
            Component.translatable("item.enderkinesis.tome_of_scrying.tooltip.chunkloader")
                .withStyle(ChatFormatting.GOLD),
        )
    }

    @Suppress("unused")
    private fun keepImports(pos: BlockPos) = pos // intentionally retained for future hooks
}
