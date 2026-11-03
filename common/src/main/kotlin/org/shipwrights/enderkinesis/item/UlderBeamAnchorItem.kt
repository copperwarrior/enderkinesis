package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import org.shipwrights.enderkinesis.block.OrbOfLinkingSounds
import org.shipwrights.enderkinesis.blockentity.UlderBeamAnchorBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKSounds

/** Right-click an orb to select it. Anchors placed or right-clicked after that bend the orb's beams. */
class UlderBeamAnchorItem(block: Block, properties: Properties) : BlockItem(block, properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        if (level.getBlockState(pos).block !== EKBlocks.ORB_OF_LINKING.get()) return super.useOn(context)
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        OrbSelection.write(context.itemInHand, NBT_ORB, pos, level.dimension().location())
        context.player?.displayClientMessage(msg("selected").withStyle(ChatFormatting.GRAY), true)
        OrbOfLinkingSounds.play(level, pos, EKSounds.ORB_OF_LINKING_SELECT)
        return InteractionResult.CONSUME
    }

    fun hasSelection(stack: ItemStack): Boolean = OrbSelection.read(stack, NBT_ORB) != null

    /** Binds the anchor at [anchorPos] to the orb that [stack] selects. Does nothing without a selection. */
    fun bind(level: ServerLevel, anchorPos: BlockPos, stack: ItemStack, player: Player?) {
        if (!hasSelection(stack)) return
        val anchor = level.getBlockEntity(anchorPos) as? UlderBeamAnchorBlockEntity ?: return
        val selection = OrbSelection.readIn(level, stack, NBT_ORB)
        val refusal = if (selection == null) UlderBeamAnchorBlockEntity.Refusal.ORB_MISSING
        else anchor.claim(level, selection.pos)
        if (refusal == null) {
            player?.displayClientMessage(msg("bound").withStyle(ChatFormatting.GRAY), true)
            return
        }
        val text = refusal.limit?.let { msg(refusal.langSuffix, it) } ?: msg(refusal.langSuffix)
        player?.displayClientMessage(text.withStyle(ChatFormatting.RED), true)
        OrbOfLinkingSounds.play(level, anchorPos, EKSounds.ORB_OF_LINKING_REFUSE)
    }

    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        super.appendHoverText(stack, level, tooltip, flag)
        tooltip.add(msg("tooltip.gesture").withStyle(ChatFormatting.GRAY))
        val sel = OrbSelection.read(stack, NBT_ORB) ?: return
        tooltip.add(msg("tooltip.selected", sel.pos.x, sel.pos.y, sel.pos.z).withStyle(ChatFormatting.AQUA))
    }

    private fun msg(suffix: String, vararg args: Any): MutableComponent =
        Component.translatable("item.enderkinesis.ulder_beam_anchor.$suffix", *args)

    private companion object {
        const val NBT_ORB = "SelectedOrb"
    }
}
