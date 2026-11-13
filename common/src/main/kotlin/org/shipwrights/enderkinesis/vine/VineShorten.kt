package org.shipwrights.enderkinesis.vine

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.chains.ChainAttachmentState
import org.valkyrienskies.core.api.chains.ChainEnd
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ChainReelControl
import org.valkyrienskies.core.api.chains.ChainStatus
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/** C2S: true on a sneak-use press at a vine with an empty hand, false on its release; the server picks the vine. */
val WOGGAK_VINE_SHORTEN: ResourceLocation = EnderkinesisMod.id("woggak_vine/shorten")
/** S2C: the server ended the player's reel, so the client stops the swing and waits for a new press. */
val WOGGAK_VINE_REEL_END: ResourceLocation = EnderkinesisMod.id("woggak_vine/reel_end")

/** A vine is never shortened under this many links. */
internal const val MIN_VINE_LINKS = 1
/** Vanilla's right-click delay: one reel start per click, even when packets come fast. */
private const val SHORTEN_COOLDOWN_TICKS = 4L
/** VS reel pull in m/s at an attached end; with the one-tick wait for each link, about 2.5 blocks/s come in. */
internal const val REEL_RATE = 4.0
/** Newtons: the reel pulls the vine in, but not a heavy load that hangs on it. */
private const val REEL_FORCE = 2000.0
/** Blocks/s that a held reel takes in at a free or joined end, near the fishing-rod reel feel. */
internal const val REEL_SPEED = 2.5
/** A free or joined end is rebuilt for each link; never more often than this, so that a reel is not rebuild spam. */
internal const val REBUILD_TICKS = 4L
/** After a release, the last link in transit still pays for this long at most. */
private const val DRAIN_TICKS = 100L
private const val TICKS_PER_SECOND = 20.0
private const val LENGTH_EPS = 1e-6

/** Links of a vine of [links] links after one shorten; -1 when it is at [MIN_VINE_LINKS]. */
internal fun shortenedLinks(links: Long): Long = if (links > MIN_VINE_LINKS) links - 1 else -1

/** The end of a vine of [links] links that is nearest to node [node]; the start on a tie. */
internal fun nearestEnd(node: Int, links: Int): ChainEnd = if (node * 2 <= links) ChainEnd.START else ChainEnd.END

/** The reel target one link under [rest], a whole number of [spacing]s as VS asks; -1 at the minimum length. */
internal fun reelTarget(rest: Double, spacing: Double): Double {
    val links = shortenedLinks((rest / spacing).roundToLong())
    return if (links < MIN_VINE_LINKS) -1.0 else links * spacing
}

/** The length that came in since the paid rest length [paidRest]; never negative, so a longer vine pays nothing. */
internal fun reelArrival(paidRest: Double, rest: Double): Double =
    if (paidRest.isFinite() && rest.isFinite() && rest < paidRest - LENGTH_EPS) paidRest - rest else 0.0

/** The paid rest length after a [rest] snapshot; it only goes down, so length that a vine gets back pays nothing. */
internal fun paidRestAfter(paidRest: Double, rest: Double): Double =
    if (!rest.isFinite()) paidRest else if (!paidRest.isFinite() || rest < paidRest) rest else paidRest

/** The pull of a free-end reel after one more held tick; capped at one link, so a stall cannot store a burst. */
internal fun nextReelPull(pull: Double, spacing: Double): Double = min(pull + REEL_SPEED / TICKS_PER_SECOND, spacing)

/** True when a free-end reel with [pull] takes in its next link of [spacing] now. */
internal fun rebuildDue(pull: Double, spacing: Double, ticksSinceRebuild: Long): Boolean =
    pull >= spacing * (1.0 - LENGTH_EPS) && ticksSinceRebuild >= REBUILD_TICKS

/**
 * A reel on one vine. While [held], the player reels; after a release it only pays the link still in transit.
 * [paidRest] is the rest length already paid, so one length is never paid twice. Server thread only.
 */
internal class VineReel(val player: UUID, var chain: ChainId, val end: ChainEnd, var paidRest: Double) {
    var held = true
    var stopTime = 0L
    var pull = 0.0
    var lastRebuild = Long.MIN_VALUE / 2
    val origin = Vector3d()
    val direction = Vector3d()
}

/** Starts or stops this player's reel on the vine under its crosshair; a tap still takes in one link. */
@OptIn(GameTickOnly::class)
internal fun ServerPlayer.reelVine(start: Boolean) {
    val level = serverLevel()
    val data = level.loadedWoggakVines ?: return
    data.heldReelOf(uuid)?.let { level.stopReel(it, this, false) }
    if (!start) return
    if (!canReelVine(data)) return sendReelEnd()
    val last = data.lastPlayerShorten[uuid]
    if (last != null && level.gameTime - last < SHORTEN_COOLDOWN_TICKS) return sendReelEnd()
    val pick = level.pickWoggakVine(this) ?: return sendReelEnd()
    val id = pick.vine.id
    val status = pick.chain.status
    val other = data.reels.get(id)
    if (data.throws.containsKey(id) || (other != null && other.held) || !status.isReelable()) return sendReelEnd()
    data.lastPlayerShorten[uuid] = level.gameTime
    val end = nearestEnd(level.nearestNode(pick), status.nodeCount - 1)
    // A link still in transit from a released reel is paid once, to this reel.
    val paidRest = if (other != null && other.chain == pick.vine.chain && other.end == end) other.paidRest else status.currentRestLength
    val reel = VineReel(uuid, pick.vine.chain, end, paidRest)
    reel.pull = Double.MAX_VALUE
    data.reels.put(id, reel)
    if (!level.stepReel(data, id, reel, pick.chain, this)) level.stopReel(reel, this, true)
}

/** Pays the reels' links as they come in, steps the held ones and ends those that can no longer reel. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineReels(data: WoggakVineData) {
    if (data.reels.isEmpty()) return
    val iterator = data.reels.long2ObjectEntrySet().fastIterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (!tickReel(data, entry.longKey, entry.value)) iterator.remove()
    }
}

/** False when [reel] is done and can go. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.tickReel(data: WoggakVineData, id: Long, reel: VineReel): Boolean {
    val player = server.playerList.getPlayer(reel.player)?.takeIf { it.level() === this }
    val vine = data.vine(id)?.takeIf { it.chain == reel.chain && it.fadeStart < 0 }
    val chain = vine?.let { shipObjectWorld.allChains.getById(it.chain) }
    if (player == null || chain == null) {
        if (reel.held) player?.sendReelEnd()
        return false
    }
    val status = chain.status
    // A rebuilt chain is pending for a tick or two; it has no node positions yet.
    if (status.lifecycle == ChainLifecycle.PENDING) return true
    if (!status.isReelable()) {
        if (reel.held) player.sendReelEnd()
        return false
    }
    val ours = status.isOurReel(reel.end)
    if (ours) {
        val came = reelArrival(reel.paidRest, status.currentRestLength)
        if (came > 0.0) player.payReel(data, vine, came)
    }
    reel.paidRest = paidRestAfter(reel.paidRest, status.currentRestLength)
    if (!reel.held) return ours && status.inTransit() && gameTime - reel.stopTime < DRAIN_TICKS
    if (!player.canReelVine(data) || !looksAtVine(data, player, chain, reel) || !stepReel(data, id, reel, chain, player)) {
        stopReel(reel, player, true)
        return ours && status.inTransit()
    }
    return true
}

/** Takes in the next link of [reel] when one is due; false when the vine cannot reel further. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stepReel(data: WoggakVineData, id: Long, reel: VineReel, chain: ServerChain, player: ServerPlayer): Boolean {
    val vine = data.vine(id) ?: return false
    val status = chain.status
    val attached = if (reel.end == ChainEnd.START) status.startAttachment else status.endAttachment
    val anchor = if (reel.end == ChainEnd.START) vine.start else vine.end
    // VS reels only an end on a world point or a body; a free or joined end is rebuilt.
    if (attached == ChainAttachmentState.ATTACHED && !anchor.isJoined) return reelIn(chain, status, reel.end)
    val spacing = status.currentRestLength / (status.nodeCount - 1)
    if (!spacing.isFinite() || spacing <= 0.0) return false
    reel.pull = nextReelPull(reel.pull, spacing)
    if (!rebuildDue(reel.pull, spacing, gameTime - reel.lastRebuild)) return true
    val reeled = rebuildShorter(data, vine, chain, reel.end)
    if (reeled <= 0.0) return false
    val piece = data.vine(id) ?: return false
    reel.pull = 0.0
    reel.lastRebuild = gameTime
    reel.chain = piece.chain
    reel.paidRest = status.currentRestLength - reeled
    player.payReel(data, piece, reeled)
    return true
}

/** Keeps a VS reel at [end] one link under the rest length; the reel sets its own pace. */
@OptIn(GameTickOnly::class)
private fun reelIn(chain: ServerChain, status: ChainStatus, end: ChainEnd): Boolean {
    val reel = status.reel
    if (reel != null && !status.isOurReel(end)) return false
    val spacing = reel?.nodeSpacing ?: (status.currentRestLength / (status.nodeCount - 1))
    if (!spacing.isFinite() || spacing <= 0.0) return false
    val target = reelTarget(status.currentRestLength, spacing)
    if (target < 0.0) return false
    if (reel == null || abs(reel.control.targetLength - target) > spacing * LENGTH_EPS) {
        chain.setReelTarget(ChainReelControl(end, target, REEL_RATE, REEL_FORCE))
    }
    return true
}

/** Throws and captures reel with other rates and forces, so their shortening is never paid as a reel-in. */
private fun ChainStatus.isOurReel(end: ChainEnd): Boolean {
    val control = reel?.control ?: return false
    return control.end == end && control.maxRate == REEL_RATE && control.maxForce == REEL_FORCE
}

private fun ChainStatus.inTransit(): Boolean {
    val target = reel?.control?.targetLength ?: return false
    return currentRestLength > target + LENGTH_EPS
}

private fun ChainStatus.isReelable(): Boolean =
    lifecycle == ChainLifecycle.ACTIVE && brokenLinks.isEmpty() && nodeCount >= 2

private fun ServerPlayer.canReelVine(data: WoggakVineData): Boolean = isShiftKeyDown && canHoldVine() && !data.holds.containsKey(uuid)

/** True when the player's look ray meets [chain] within [PICK_REACH]; blocks do not count, to keep this cheap. */
@OptIn(GameTickOnly::class)
private fun looksAtVine(data: WoggakVineData, player: ServerPlayer, chain: ServerChain, reel: VineReel): Boolean {
    val nodes = chain.status.nodeCount
    if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(data.scratch) != nodes) return false
    reel.origin.set(player.x, player.eyeY, player.z)
    // Entity.calculateViewVector, without its Vec3.
    val pitch = Math.toRadians(player.xRot.toDouble())
    val yaw = Math.toRadians(-player.yRot.toDouble())
    reel.direction.set(sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    for (link in 0 until nodes - 1) {
        if (data.scratch.rayHitsSegment(reel.origin, reel.direction, link * 3, PICK_REACH) >= 0) return true
    }
    return false
}

private fun WoggakVineData.heldReelOf(player: UUID): VineReel? = reels.values.firstOrNull { it.held && it.player == player }

/** Ends the held part of [reel]; a link in transit still pays. [tellClient] when the client did not ask for it. */
private fun ServerLevel.stopReel(reel: VineReel, player: ServerPlayer, tellClient: Boolean) {
    reel.held = false
    reel.stopTime = gameTime
    if (tellClient) player.sendReelEnd()
}

private fun ServerPlayer.sendReelEnd() = NetworkManager.sendToPlayer(this, WOGGAK_VINE_REEL_END, FriendlyByteBuf(Unpooled.buffer()))

/** Pays [reeled] blocks of [vine] through its credit: one Woggak Vine for each full 4 blocks. */
private fun ServerPlayer.payReel(data: WoggakVineData, vine: WoggakVine, reeled: Double) {
    serverLevel().playSound(null, x, y, z, SoundEvents.GROWING_PLANT_CROP, SoundSource.PLAYERS, 1f, 1f)
    // Creative reels give nothing and keep no credit, so a creative reel cannot pay a survival player later.
    if (isCreative) return
    val items = reelItems(vine.reelCredit, reeled)
    vine.reelCredit = reelCreditLeft(vine.reelCredit, reeled)
    data.setDirty()
    if (items > 0) {
        val stack = ItemStack(EKItems.WOGGAK_VINE.get(), items)
        if (!inventory.add(stack)) drop(stack, false)
    }
}

/**
 * Makes [vine] again without its node at [end], one link shorter, with the same id, anchors, joints and grips. Joints
 * to a node of it move down one node when the start goes; a joint on the start node stays on the new start node.
 * Returns the length taken off, or 0.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.rebuildShorter(data: WoggakVineData, vine: WoggakVine, chain: ServerChain, end: ChainEnd): Double {
    val status = chain.status
    // A reel in progress holds a target that the rebuild would lose.
    if (status.reel != null) return 0.0
    val nodes = status.nodeCount
    val links = nodes - 1
    if (shortenedLinks(links.toLong()) < MIN_VINE_LINKS) return 0.0
    val rest = status.currentRestLength
    val spacing = rest / links
    if (!spacing.isFinite() || spacing <= 0.0) return 0.0
    val positions = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(positions) != nodes) return 0.0
    val run = if (end == ChainEnd.START) 1 until nodes else 0 until nodes - 1
    val newRest = spacing * (links - 1)
    val piece = addPiece(
        positions, nodeVelocities(chain, nodes), run, newRest, vine.start, vine.end, vine.fromBreak, vine.dropsOnFade, vine.id,
    ) ?: return 0.0
    piece.reelCredit = vine.reelCredit
    piece.grips = vine.grips.inRun(run)
    if (!shipObjectWorld.deleteChain(vine.chain)) LOG.warn("Woggak Vine {} was already removed", vine.chain.value)
    data.remove(vine)
    for (other in data.vines) {
        for (anchor in arrayOf(other.start, other.end)) {
            val joint = anchor.point?.joint ?: continue
            if (joint.vine != vine.id) continue
            // The old pair holds node ids of the deleted chain; followJoint makes a new one.
            anchor.pair = null
            if (end == ChainEnd.START && joint.node > 0) joint.node--
        }
    }
    data.anchorsDirty = true
    data.jointsDirty = true
    data.setDirty()
    return rest - newRest
}
