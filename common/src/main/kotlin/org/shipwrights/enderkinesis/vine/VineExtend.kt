package org.shipwrights.enderkinesis.vine

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.chains.ChainAttachmentState
import org.valkyrienskies.core.api.chains.ChainEnd
import org.valkyrienskies.core.api.chains.ChainReelControl
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.util.GameTickOnly
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val LINK_EPS = 1e-9

/**
 * Links that a VS pay-out adds for one item, and the credit after it. VS pays out whole links only, so the part of
 * the block that does not fit stays in the vine's credit, and the next pay-out or the refund pays it.
 */
internal fun extendLinks(credit: Double, spacing: Double): Pair<Int, Double> {
    require(spacing > 0.0 && spacing.isFinite()) { "Spacing $spacing" }
    val owed = validReelCredit(credit) + BLOCKS_PER_ITEM
    val links = floor(owed / spacing + LINK_EPS).toInt().coerceAtLeast(1)
    return links to validReelCredit(owed - links * spacing)
}

/** Creative pay-outs keep no credit, so they add the whole links nearest one block. */
internal fun creativeExtendLinks(spacing: Double): Int = (BLOCKS_PER_ITEM / spacing).roundToInt().coerceAtLeast(1)

/**
 * Node [node] of a vine of [links] links and [rest] length, after [added] blocks go on at the start ([atStart]) or the
 * end. VS lays the new chain out again at [VINE_TARGET_SPACING], so the node keeps its place along the vine.
 */
internal fun extendedNode(node: Int, links: Int, rest: Double, added: Double, atStart: Boolean): Int {
    val length = rest + added
    val newLinks = ceil(length / VINE_TARGET_SPACING - LINK_EPS).toInt().coerceAtLeast(1)
    val along = node * rest / links + if (atStart) added else 0.0
    return (along * newLinks / length).roundToInt().coerceIn(0, newLinks)
}

/**
 * Sneak-use with Woggak Vines: one more block of the vine under the look, at the end nearest the hit, for one item.
 * Same pick as the shears. False when no vine is under the look, so the item does its normal use.
 */
@OptIn(GameTickOnly::class)
internal fun ServerPlayer.extendVine(stack: ItemStack): Boolean {
    if (!stack.`is`(EKItems.WOGGAK_VINE.get())) return false
    val level = serverLevel()
    val data = level.loadedWoggakVines ?: return false.also { LOG.info("[VineReelDebug] server extend: no loaded vine data") }
    val pick = level.pickWoggakVine(this) ?: return false.also {
        LOG.info("[VineReelDebug] server extend: no server pick eye={} look={}", eyePosition, getViewVector(1f))
    }
    val vine = pick.vine
    val chain = pick.chain
    val status = chain.status
    data.reels.remove(vine.id)?.let { reel ->
        level.endReel(reel)
        if (reel.held) level.server.playerList.getPlayer(reel.player)?.sendReelEnd()
    }
    // A pay-out in progress blocks an extend.
    if (!status.isReelable() || status.reelBusy()) {
        LOG.info("[VineReelDebug] server extend: refused reelable={} busy={} status={}", status.isReelable(), status.reelBusy(), status)
        return true
    }
    val end = nearestEnd(level.nearestNode(pick), status.nodeCount - 1)
    val creative = abilities.instabuild
    val attached = if (end == ChainEnd.START) status.startAttachment else status.endAttachment
    val anchor = if (end == ChainEnd.START) vine.start else vine.end
    // VS pays out only at an end on a world point or a body; a free or joined end is rebuilt.
    val grew = if (attached == ChainAttachmentState.ATTACHED && !anchor.isJoined) {
        payOutBlock(vine, chain, end, creative)
    } else {
        level.rebuildLonger(data, vine, chain, end)
    }
    LOG.info("[VineReelDebug] server extend: end={} attached={} joined={} grew={}", end, attached, anchor.isJoined, grew)
    if (!grew) return true
    data.setDirty()
    if (!creative) stack.shrink(1)
    level.playSound(null, x, y, z, SoundEvents.GROWING_PLANT_CROP, SoundSource.PLAYERS, 1f, 1f)
    return true
}

@OptIn(GameTickOnly::class)
private fun payOutBlock(vine: WoggakVine, chain: ServerChain, end: ChainEnd, creative: Boolean): Boolean {
    val status = chain.status
    val spacing = status.reel?.nodeSpacing ?: (status.currentRestLength / (status.nodeCount - 1))
    if (!spacing.isFinite() || spacing <= 0.0) return false
    val links = if (creative) {
        creativeExtendLinks(spacing)
    } else {
        val (count, credit) = extendLinks(vine.reelCredit, spacing)
        vine.reelCredit = credit
        count
    }
    LOG.info("[VineReelDebug] server VS pay-out end={} links={} spacing={} rest={}", end, links, spacing, status.currentRestLength)
    chain.setReel(status, ChainReelControl(end, status.currentRestLength + links * spacing, REEL_RATE, REEL_FORCE))
    return true
}

/** Makes [vine] again with one more block past its node at [end], with the same id, anchors, joints and grips. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.rebuildLonger(data: WoggakVineData, vine: WoggakVine, chain: ServerChain, end: ChainEnd): Boolean {
    val status = chain.status
    val nodes = status.nodeCount
    val links = nodes - 1
    val rest = status.currentRestLength
    if (links < 1 || !rest.isFinite() || rest <= 0.0) return false
    val old = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(old) != nodes) return false
    val oldVelocities = nodeVelocities(chain, nodes)
    val atStart = end == ChainEnd.START
    val shift = if (atStart) 1 else 0
    val positions = DoubleArray((nodes + 1) * 3)
    old.copyInto(positions, shift * 3)
    val velocities = oldVelocities?.let { DoubleArray((nodes + 1) * 3).also { v -> it.copyInto(v, shift * 3) } }
    val tip = if (atStart) 0 else links
    val inner = if (atStart) 1 else links - 1
    val added = BLOCKS_PER_ITEM
    val slot = if (atStart) 0 else nodes
    if (!extendPast(old, tip, inner, added, positions, slot)) return false
    // The new node starts with the velocity of the end node it extends.
    val tipSlot = tip + shift
    velocities?.let { it.copyInto(it, slot * 3, tipSlot * 3, tipSlot * 3 + 3) }
    val piece = addPiece(
        positions, velocities, 0..nodes, rest + added, vine.start, vine.end, vine.fromBreak, vine.dropsOnFade, vine.id,
    ) ?: return false
    piece.grips = vine.grips.map { VineGrip(extendedNode(it.node, links, rest, added, atStart), it.shipId, it.local) }
    replaceRebuilt(data, vine, piece) { node -> extendedNode(node, links, rest, added, atStart) }
    return true
}

/** Writes the point [added] past node [tip] (away from node [inner]) of [old] to slot [slot] of [out]; down when the link has no length. */
private fun extendPast(old: DoubleArray, tip: Int, inner: Int, added: Double, out: DoubleArray, slot: Int): Boolean {
    val t = tip * 3
    val i = inner * 3
    var dx = old[t] - old[i]; var dy = old[t + 1] - old[i + 1]; var dz = old[t + 2] - old[i + 2]
    val length = sqrt(dx * dx + dy * dy + dz * dz)
    if (length.isFinite() && length > LINK_EPS) {
        dx /= length; dy /= length; dz /= length
    } else {
        dx = 0.0; dy = -1.0; dz = 0.0
    }
    val s = slot * 3
    out[s] = old[t] + dx * added; out[s + 1] = old[t + 1] + dy * added; out[s + 2] = old[t + 2] + dz * added
    return out[s].isFinite() && out[s + 1].isFinite() && out[s + 2].isFinite()
}
