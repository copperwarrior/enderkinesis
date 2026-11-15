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
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val LINK_EPS = 1e-9
/** Blocks of vine that a normal use on a vine's own anchor pays out there, one item each. */
private const val DROP_BLOCKS = 4
/** A use point this close to a vine anchor on the same block and ship counts as that anchor's spot. */
private const val SAME_SPOT_DISTANCE = 0.25

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

/** Creative pay-outs keep no credit, so they add the whole links nearest [items] blocks. */
internal fun creativeExtendLinks(spacing: Double, items: Int = 1): Int =
    (items * BLOCKS_PER_ITEM / spacing).roundToInt().coerceAtLeast(1)

/**
 * Node [node] of a vine of [links] links and [rest] length, after [added] blocks go on at the start ([atStart]) or the
 * end. VS lays the new chain out again at [spacing], so the node keeps its place along the vine.
 */
internal fun extendedNode(
    node: Int, links: Int, rest: Double, added: Double, atStart: Boolean, spacing: Double = VINE_TARGET_SPACING,
): Int {
    val length = rest + added
    val newLinks = ceil(length / spacing - LINK_EPS).toInt().coerceAtLeast(1)
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
    val data = level.loadedWoggakVines ?: return false
    val pick = level.pickWoggakVine(this) ?: return false
    val vine = pick.vine
    val chain = pick.chain
    val status = chain.status
    level.endReelOf(data, vine)
    // A pay-out in progress blocks an extend.
    if (!status.isReelable() || status.reelBusy()) return true
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
    if (!grew) return true
    data.setDirty()
    if (!creative) stack.shrink(1)
    level.playSound(null, x, y, z, SoundEvents.GROWING_PLANT_CROP, SoundSource.PLAYERS, 1f, 1f)
    return true
}

/**
 * Normal use on the block spot where a vine is already anchored: [DROP_BLOCKS] more blocks pay out at that end, so
 * they drop down from the anchor, one item each. False when no vine end is anchored at [point].
 */
@OptIn(GameTickOnly::class)
internal fun ServerPlayer.dropVineAt(stack: ItemStack, point: VinePoint): Boolean {
    if (point.joint != null || !stack.`is`(EKItems.WOGGAK_VINE.get())) return false
    val level = serverLevel()
    val data = level.loadedWoggakVines ?: return false
    var found: WoggakVine? = null
    var end = ChainEnd.START
    for (vine in data.vines) {
        if (vine.fadeStart >= 0) continue
        end = when {
            vine.start.isAt(point) -> ChainEnd.START
            vine.end.isAt(point) -> ChainEnd.END
            else -> continue
        }
        found = vine
        break
    }
    val vine = found ?: return false
    // From here the use is spent, so a second vine never goes on the same spot.
    val chain = level.shipObjectWorld.allChains.getById(vine.chain) ?: return true
    level.endReelOf(data, vine)
    val status = chain.status
    val attached = if (end == ChainEnd.START) status.startAttachment else status.endAttachment
    if (!status.isReelable() || status.reelBusy() || attached != ChainAttachmentState.ATTACHED) return true
    val creative = abilities.instabuild
    val items = if (creative) DROP_BLOCKS else min(DROP_BLOCKS, stack.count)
    if (items <= 0 || !payOutBlocks(vine, chain, end, creative, items)) return true
    data.setDirty()
    if (!creative) stack.shrink(items)
    level.playSound(null, x, y, z, SoundEvents.GROWING_PLANT_CROP, SoundSource.PLAYERS, 1f, 1f)
    return true
}

private fun ServerLevel.endReelOf(data: WoggakVineData, vine: WoggakVine) {
    val reel = data.reels.remove(vine.id) ?: return
    endReel(reel)
    if (reel.held) server.playerList.getPlayer(reel.player)?.sendReelEnd()
}

private fun WoggakVineAnchor.isAt(point: VinePoint): Boolean {
    val own = this.point ?: return false
    return !released && own.joint == null && own.block == point.block && own.shipId == point.shipId &&
        own.local.distanceSquared(point.local) <= SAME_SPOT_DISTANCE * SAME_SPOT_DISTANCE
}

@OptIn(GameTickOnly::class)
private fun payOutBlock(vine: WoggakVine, chain: ServerChain, end: ChainEnd, creative: Boolean): Boolean =
    payOutBlocks(vine, chain, end, creative, 1)

/** A VS pay-out of [items] blocks at the attached [end]; the remainder of whole links stays in the vine's credit. */
@OptIn(GameTickOnly::class)
private fun payOutBlocks(vine: WoggakVine, chain: ServerChain, end: ChainEnd, creative: Boolean, items: Int): Boolean {
    val status = chain.status
    val spacing = status.reel?.nodeSpacing ?: (status.currentRestLength / (status.nodeCount - 1))
    if (!spacing.isFinite() || spacing <= 0.0) return false
    val links = if (creative) {
        creativeExtendLinks(spacing, items)
    } else {
        var count = 0
        repeat(items) {
            val (added, credit) = extendLinks(vine.reelCredit, spacing)
            vine.reelCredit = credit
            count += added
        }
        count
    }
    chain.setReel(status, ChainReelControl(end, status.currentRestLength + links * spacing, REEL_RATE, REEL_FORCE))
    return true
}

/** Makes [vine] again with one more block past its node at [end], with the same id, anchors and joints. */
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
        positions, velocities, 0..nodes, rest + added, vine.start, vine.end, vine.fromBreak, vine.dropsOnFade, vine.spacing, vine.id,
        width = vine.width,
    ) ?: return false
    val spacing = vine.spacing
    replaceRebuilt(data, vine, piece) { node -> extendedNode(node, links, rest, added, atStart, spacing) }
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
