package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Shared base for tomes that wire orb networks together via the send → receiver gesture loop.
 * Under the multi-tome substrate, each orb can participate in any number of tomes' networks at
 * once — this class scopes every operation to its subclass's [tomeKind] so tomes never step
 * on each other.
 *
 *  - **Right-click an orb (no selection)** → select this orb as the SEND end for the next link
 *    *for this tome*. The orb may already be part of other tomes' networks; that's fine.
 *  - **Right-click the same orb again** → clear the tome's selection.
 *  - **Right-click a different orb (with selection)** →
 *    - if the SEND→RECEIVE link for this tome already exists → unlink (toggle off).
 *    - else if the pair passes [validateLink] + 64-block cap → create a fresh link.
 *  - **Sneak-right-click an orb** → drop ALL of this tome's links through the orb (incoming
 *    and outgoing). Other tomes' links through the orb are untouched.
 *  - **Sneak-right-click empty space** → clear the tome's selection.
 *
 * Subclasses contribute:
 *  - [tomeKind] — the [ResourceLocation] identifying this tome's links.
 *  - [langPrefix] — `"item.enderkinesis.tome_of_X"`; the status keys (`select_send`,
 *    `link_added`, `link_removed`, `dismantle`, …) live under `"$langPrefix.<suffix>"`.
 *  - [writtenBook] — placeholder lore, like every [TomeItem] subclass.
 *  - [validateLink] (optional) — tome-specific rejection of a pairing (e.g. Binding refuses
 *    world↔world and same-ship). The universal same-dim + 64-block checks are separate.
 */
abstract class LinkingTomeItem(properties: Properties) : TomeItem(properties) {

    abstract val tomeKind: ResourceLocation
    abstract val langPrefix: String

    /** Tome-specific pre-link refusal. Return null to allow; return a lang-key suffix (e.g.
     *  `"cant_world_world"`) to reject with that message. */
    protected open fun validateLink(
        level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, recvBe: OrbOfLinkingBlockEntity,
    ): String? = null

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        if (state.block !== EKBlocks.ORB_OF_LINKING.get()) return super.useOn(context)
        val be = level.getBlockEntity(pos) as? OrbOfLinkingBlockEntity ?: return InteractionResult.PASS
        val player = context.player ?: return InteractionResult.PASS
        val tome = context.itemInHand
        val sneak = player.isSecondaryUseActive
        if (level.isClientSide) return InteractionResult.sidedSuccess(true)
        val serverLevel = level as ServerLevel
        return if (sneak) handleSneak(serverLevel, player, tome, pos, be)
        else handlePlain(serverLevel, player, tome, pos, be)
    }

    /** Sneak-right-click on empty space clears the tome's selection. */
    override fun use(
        level: Level, player: Player, hand: InteractionHand,
    ): InteractionResultHolder<ItemStack> {
        val tome = player.getItemInHand(hand)
        if (!player.isSecondaryUseActive) return InteractionResultHolder.pass(tome)
        if (getSelectedSend(tome) == null) return InteractionResultHolder.pass(tome)
        if (!level.isClientSide) {
            clearSelection(tome)
            player.displayClientMessage(msg(Lang.SELECTION_CLEARED), true)
        }
        return InteractionResultHolder.sidedSuccess(tome, level.isClientSide)
    }

    private fun handlePlain(
        level: ServerLevel, player: Player, tome: ItemStack, pos: BlockPos, be: OrbOfLinkingBlockEntity,
    ): InteractionResult {
        val selection = getValidSelection(level, tome)
        return when {
            selection == null -> {
                // Fresh selection. Reject pure-receive orbs — they're committed to the
                // "receiver" direction across every tome they're in, and can't become senders
                // without first being fully unlinked.
                if (be.isPureReceive()) {
                    player.displayClientMessage(
                        msg(Lang.CANT_SELECT_RECEIVE).withStyle(ChatFormatting.RED), true,
                    )
                    return InteractionResult.sidedSuccess(false)
                }
                setSelectedSend(tome, pos, level.dimension().location())
                player.displayClientMessage(msg(Lang.SELECT_SEND), true)
                InteractionResult.sidedSuccess(false)
            }
            selection.pos == pos -> {
                // Toggle the selection off — useful escape hatch if the player picked wrong.
                clearSelection(tome)
                player.displayClientMessage(msg(Lang.SELECTION_CLEARED), true)
                InteractionResult.sidedSuccess(false)
            }
            else -> {
                val sendBe = level.getBlockEntity(selection.pos) as? OrbOfLinkingBlockEntity
                if (sendBe == null) {
                    clearSelection(tome)
                    player.displayClientMessage(msg(Lang.SELECTION_STALE), true)
                    return InteractionResult.sidedSuccess(false)
                }
                // The selected orb may have flipped to pure-receive between selection and now
                // (e.g., another player linked to it). Force the player to restart selection.
                if (sendBe.isPureReceive()) {
                    clearSelection(tome)
                    player.displayClientMessage(
                        msg(Lang.CANT_SELECT_RECEIVE).withStyle(ChatFormatting.RED), true,
                    )
                    return InteractionResult.sidedSuccess(false)
                }
                // Already linked? Toggle off — same gesture both creates and removes a link.
                if (sendBe.hasOutgoingLink(tomeKind, pos)) {
                    sendBe.removeOutgoingLink(level, tomeKind, pos)
                    clearSelection(tome)
                    player.displayClientMessage(msg(Lang.LINK_REMOVED), true)
                    return InteractionResult.sidedSuccess(false)
                }
                // The clicked peer can't already be a sender — making it a receiver here would
                // violate the direction-commitment rule. Keep selection so the player can try
                // a different receiver without re-selecting the send orb.
                if (be.isPureSend()) {
                    player.displayClientMessage(
                        msg(Lang.CANT_LINK_TO_SEND).withStyle(ChatFormatting.RED), true,
                    )
                    return InteractionResult.sidedSuccess(false)
                }
                val sendCentre = sendBe.worldCenter() ?: return InteractionResult.sidedSuccess(false)
                val recvCentre = be.worldCenter() ?: return InteractionResult.sidedSuccess(false)
                if (sendCentre.distanceTo(recvCentre) > OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE) {
                    player.displayClientMessage(
                        msg(Lang.TOO_FAR, OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE.toInt())
                            .withStyle(ChatFormatting.RED),
                        true,
                    )
                    return InteractionResult.sidedSuccess(false)
                }
                val veto = validateLink(level, sendBe, be)
                if (veto != null) {
                    player.displayClientMessage(msg(veto).withStyle(ChatFormatting.RED), true)
                    return InteractionResult.sidedSuccess(false)
                }
                if (sendBe.addOutgoingLink(level, tomeKind, pos)) {
                    clearSelection(tome)
                    player.displayClientMessage(msg(Lang.LINK_ADDED), true)
                }
                InteractionResult.sidedSuccess(false)
            }
        }
    }

    private fun handleSneak(
        level: ServerLevel, player: Player, tome: ItemStack, pos: BlockPos, be: OrbOfLinkingBlockEntity,
    ): InteractionResult {
        val outgoing = be.outgoingPeers(tomeKind).size
        val incoming = be.incomingPeers(tomeKind).size
        if (outgoing == 0 && incoming == 0) {
            // No links for this tome through this orb — fall through to "clear stale selection".
            if (getSelectedSend(tome) != null) {
                clearSelection(tome)
                player.displayClientMessage(msg(Lang.SELECTION_CLEARED), true)
            }
            return InteractionResult.sidedSuccess(false)
        }
        be.removeAllForTome(level, tomeKind)
        clearSelection(tome)
        player.displayClientMessage(msg(Lang.DISMANTLE), true)
        return InteractionResult.sidedSuccess(false)
    }

    override fun appendHoverText(
        stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag,
    ) {
        tooltip.add(msg(Lang.TOOLTIP_GESTURE).withStyle(ChatFormatting.GRAY))
        tooltip.add(msg(Lang.TOOLTIP_SNEAK).withStyle(ChatFormatting.DARK_GRAY))
        val sel = getSelectedSend(stack)
        if (sel != null) {
            tooltip.add(
                msg(Lang.TOOLTIP_SELECTED, sel.pos.x, sel.pos.y, sel.pos.z)
                    .withStyle(ChatFormatting.AQUA)
            )
        }
    }

    // --- Per-stack selection (NBT) -----------------------------------------------------------

    data class Selection(val pos: BlockPos, val dim: ResourceLocation)

    protected fun getSelectedSend(stack: ItemStack): Selection? {
        val tag = stack.tag ?: return null
        if (!tag.contains(NBT_SEND, 10 /* compound */)) return null
        val sel = tag.getCompound(NBT_SEND)
        val dim = ResourceLocation.tryParse(sel.getString("Dim")) ?: return null
        return Selection(BlockPos(sel.getInt("X"), sel.getInt("Y"), sel.getInt("Z")), dim)
    }

    private fun getValidSelection(level: ServerLevel, stack: ItemStack): Selection? {
        val s = getSelectedSend(stack) ?: return null
        return if (s.dim == level.dimension().location()) s else null
    }

    private fun setSelectedSend(stack: ItemStack, pos: BlockPos, dim: ResourceLocation) {
        val tag = stack.orCreateTag
        val sub = CompoundTag()
        sub.putInt("X", pos.x); sub.putInt("Y", pos.y); sub.putInt("Z", pos.z)
        sub.putString("Dim", dim.toString())
        tag.put(NBT_SEND, sub)
    }

    protected fun clearSelection(stack: ItemStack) {
        stack.tag?.remove(NBT_SEND)
    }

    // --- Helpers -----------------------------------------------------------------------------

    private fun msg(suffix: String, vararg args: Any): MutableComponent =
        Component.translatable("$langPrefix.$suffix", *args)

    /** Canonical lang-key suffixes consumed by [LinkingTomeItem]. Subclasses must provide an
     *  entry for each under their [langPrefix]. */
    object Lang {
        const val SELECT_SEND = "select_send"
        const val LINK_ADDED = "link_added"
        const val LINK_REMOVED = "link_removed"
        const val DISMANTLE = "dismantle"
        const val TOO_FAR = "too_far"
        const val SELECTION_STALE = "selection_stale"
        const val SELECTION_CLEARED = "selection_cleared"
        const val CANT_SELECT_RECEIVE = "cant_select_receive"
        const val CANT_LINK_TO_SEND = "cant_link_to_send"
        const val TOOLTIP_GESTURE = "tooltip.gesture"
        const val TOOLTIP_SNEAK = "tooltip.sneak"
        const val TOOLTIP_SELECTED = "tooltip.selected"
    }

    companion object {
        private const val NBT_SEND = "SelectedSend"
    }
}
