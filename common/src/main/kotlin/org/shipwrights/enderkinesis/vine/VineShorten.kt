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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** C2S: true on a sneak-use press at a vine with an empty hand, false on its release; the server picks the vine. */
val WOGGAK_VINE_SHORTEN: ResourceLocation = EnderkinesisMod.id("woggak_vine/shorten")
/** S2C: the server ended the player's reel, so the client stops the swing and waits for a new press. */
val WOGGAK_VINE_REEL_END: ResourceLocation = EnderkinesisMod.id("woggak_vine/reel_end")

/** Vanilla's right-click delay: one reel start per click, even when packets come fast. */
private const val SHORTEN_COOLDOWN_TICKS = 4L
/** VS pay-out rate in m/s at an attached end. */
internal const val REEL_RATE = 4.0
/** Newtons: the most that a VS pay-out pushes. */
internal const val REEL_FORCE = 2000.0
/** Blocks/s that a held reel takes in, near the fishing-rod reel feel. */
internal const val REEL_SPEED = 2.5
/** VS refuses a link under one particle diameter; the margin keeps the squeezed nodes off each other. */
internal const val MIN_REEL_SPACING = 2 * PARTICLE_RADIUS * 1.25
/** Reeled length paid at one time, so the reel sound plays about once for each link, not each tick. */
private const val PAY_STEP = VINE_TARGET_SPACING
private const val LENGTH_EPS = 1e-6

/** The end of a vine of [links] links that is nearest to node [node]; the start on a tie. */
internal fun nearestEnd(node: Int, links: Int): ChainEnd = if (node * 2 <= links) ChainEnd.START else ChainEnd.END

/** The shortest rest length that [links] links of a [width] vine can reel to; the particle radius goes with the width. */
internal fun reelFloor(links: Int, width: Double = VINE_WIDTH): Double = links * MIN_REEL_SPACING * width / VINE_WIDTH

/** True when a rest length of [rest] is at the [reelFloor] of [links] links. */
internal fun atReelFloor(rest: Double, links: Int, width: Double = VINE_WIDTH): Boolean = rest <= reelFloor(links, width) + LENGTH_EPS

/** Links that VS gives a new chain of [rest] length: it lays any path out again at [spacing]. */
internal fun respacedLinks(rest: Double, spacing: Double = VINE_TARGET_SPACING): Int =
    ceil(rest / spacing - LENGTH_EPS).toInt().coerceAtLeast(1)

/** The length that came in since the paid rest length [paidRest]; never negative, so a longer vine pays nothing. */
internal fun reelArrival(paidRest: Double, rest: Double): Double =
    if (paidRest.isFinite() && rest.isFinite() && rest < paidRest - LENGTH_EPS) paidRest - rest else 0.0

/** The paid rest length after a [rest] snapshot; it only goes down, so length that a vine gets back pays nothing. */
internal fun paidRestAfter(paidRest: Double, rest: Double): Double =
    if (!rest.isFinite()) paidRest else if (!paidRest.isFinite() || rest < paidRest) rest else paidRest

/**
 * A reel on one vine. While [held], VS shortens every link of [chain] toward [target]. [paidRest] is the rest length
 * already paid, so one length is never paid twice. Server thread only.
 */
internal class VineReel(val player: UUID, var chain: ChainId, var paidRest: Double) {
    var held = true
    /** The target length last sent to VS; NaN when none was sent to this chain. */
    var target = Double.NaN
    var failure: String? = null
    val origin = Vector3d()
    val direction = Vector3d()
}

/** Starts or stops this player's reel on the vine under its crosshair. */
@OptIn(GameTickOnly::class)
internal fun ServerPlayer.reelVine(start: Boolean) {
    val level = serverLevel()
    val data = level.loadedWoggakVines ?: return
    data.heldReelOf(uuid)?.let { level.stopReel(data, it, this, false) }
    if (!start) return
    if (!canReelVine(data)) return sendReelEnd()
    val last = data.lastPlayerShorten[uuid]
    if (last != null && level.gameTime - last < SHORTEN_COOLDOWN_TICKS) return sendReelEnd()
    val pick = level.pickWoggakVine(this) ?: return sendReelEnd()
    val id = pick.vine.id
    val status = pick.chain.status
    val other = data.reels.get(id)
    if ((other != null && other.held) || !status.isReelable() || status.reelBusy()) return sendReelEnd()
    data.lastPlayerShorten[uuid] = level.gameTime
    val reel = VineReel(uuid, pick.vine.chain, status.currentRestLength)
    data.reels.put(id, reel)
    if (!level.stepReel(data, id, reel, pick.chain, this)) level.stopReel(data, reel, this, true)
}

/** Pays the reels' links as they come in, steps the held ones and ends those that can no longer reel. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.tickVineReels(data: WoggakVineData) {
    if (data.reels.isEmpty()) return
    val iterator = data.reels.long2ObjectEntrySet().fastIterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (tickReel(data, entry.longKey, entry.value)) continue
        endReel(entry.value)
        iterator.remove()
    }
}

/**
 * Keeps the length that [reel] reached. The published rest is a few physics frames behind, so the vine gets that
 * little back at the reel speed, and that length is not paid.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.endReel(reel: VineReel) {
    if (reel.target.isNaN()) return
    reel.target = Double.NaN
    val chain = shipObjectWorld.allChains.getById(reel.chain) ?: return
    val status = chain.status
    if (status.lifecycle != ChainLifecycle.ACTIVE || status.reel != null) return
    val rest = status.currentRestLength
    if (rest.isFinite() && rest > 0.0 && status.targetLength < rest - LENGTH_EPS) chain.setTargetLength(rest, REEL_SPEED)
}

/** False when [reel] is done and can go. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.tickReel(data: WoggakVineData, id: Long, reel: VineReel): Boolean {
    if (!reel.held) return false
    val player = server.playerList.getPlayer(reel.player)?.takeIf { it.level() === this }
    val vine = data.vine(id)?.takeIf { it.chain == reel.chain && it.fadeStart < 0 }
    val chain = vine?.let { shipObjectWorld.allChains.getById(it.chain) }
    if (player == null || chain == null) {
        player?.sendReelEnd()
        return false
    }
    val status = chain.status
    // A rebuilt chain is pending for a tick or two; it has no node positions yet.
    if (status.lifecycle == ChainLifecycle.PENDING) return true
    // VS keeps a failure until the next command applies, so it can be stale; it is only logged.
    val failure = status.failure
    if (failure != reel.failure && failure != null) LOG.warn("Woggak Vine {} reel: {}", reel.chain.value, failure)
    reel.failure = failure
    // A link that VS broke ends the reel; the break path splits the vine.
    if (!status.isReelable()) {
        player.sendReelEnd()
        return false
    }
    player.payReelArrival(data, vine, reel, status, PAY_STEP)
    if (!player.canReelVine(data) || !looksAtVine(data, player, chain, reel) || !stepReel(data, id, reel, chain, player)) {
        stopReel(data, reel, player, true)
        return false
    }
    return true
}

/**
 * Shortens every link of [chain] at [REEL_SPEED]; the VS joints pull as hard as the load needs and break at
 * [VINE_BREAK_FORCE]. At the [reelFloor] the vine is made again with fewer links. False when it cannot get shorter.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stepReel(data: WoggakVineData, id: Long, reel: VineReel, chain: ServerChain, player: ServerPlayer): Boolean {
    val status = chain.status
    val links = status.nodeCount - 1
    val rest = status.currentRestLength
    if (links < 1 || !rest.isFinite() || rest <= 0.0) return false
    val vine = data.vine(id) ?: return false
    if (atReelFloor(rest, links, vine.width)) {
        if (respacedLinks(rest, vine.spacing) >= links) return false
        val piece = rebuildRespaced(data, vine, chain) ?: return false
        reel.chain = piece.chain
        reel.target = Double.NaN
        return true
    }
    val floor = reelFloor(links, vine.width)
    if (reel.target != floor) {
        chain.setTargetLength(floor, REEL_SPEED)
        reel.target = floor
    }
    return true
}

/** Pays the length of [vine] that came in since [reel]'s paid rest, once it is at least [step]. */
private fun ServerPlayer.payReelArrival(data: WoggakVineData, vine: WoggakVine, reel: VineReel, status: ChainStatus, step: Double) {
    val came = reelArrival(reel.paidRest, status.currentRestLength)
    if (came <= 0.0 || came < step) return
    payReel(data, vine, came)
    reel.paidRest = paidRestAfter(reel.paidRest, status.currentRestLength)
}

/** VS rejects a new reel end while an old reel is set, so a done reel at the other end is ended first. */
@OptIn(GameTickOnly::class)
internal fun ServerChain.setReel(status: ChainStatus, control: ChainReelControl) {
    val old = status.reel?.control
    if (old != null && old.end != control.end) setTargetLength(status.currentRestLength)
    setReelTarget(control)
}

/** True while a VS reel still pays out or takes in; a reel at its target is done and changes nothing. */
internal fun ChainStatus.reelBusy(): Boolean {
    val target = reel?.control?.targetLength ?: return false
    return abs(currentRestLength - target) > LENGTH_EPS
}

internal fun ChainStatus.isReelable(): Boolean =
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

/**
 * Ends the held part of [reel]: pays the length that came in and keeps the length reached. [tellClient] when the
 * client did not ask for it.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.stopReel(data: WoggakVineData, reel: VineReel, player: ServerPlayer, tellClient: Boolean) {
    reel.held = false
    val vine = data.vines.firstOrNull { it.chain == reel.chain }
    val status = shipObjectWorld.allChains.getById(reel.chain)?.status
    if (vine != null && status != null) player.payReelArrival(data, vine, reel, status, 0.0)
    endReel(reel)
    if (tellClient) player.sendReelEnd()
}

internal fun ServerPlayer.sendReelEnd() = NetworkManager.sendToPlayer(this, WOGGAK_VINE_REEL_END, FriendlyByteBuf(Unpooled.buffer()))

/** Pays [reeled] blocks of [vine] through its credit: one Woggak Vine for each full [BLOCKS_PER_ITEM] blocks. */
internal fun ServerPlayer.payReel(data: WoggakVineData, vine: WoggakVine, reeled: Double) {
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
 * Makes [vine] again at its rest length, with the same id, anchors and joints. VS lays it out at
 * its spacing, so a vine squeezed to the [reelFloor] gets fewer links. Null when it cannot.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.rebuildRespaced(data: WoggakVineData, vine: WoggakVine, chain: ServerChain): WoggakVine? {
    val status = chain.status
    val nodes = status.nodeCount
    val links = nodes - 1
    val rest = status.currentRestLength
    val positions = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(positions) != nodes) return null
    val piece = addPiece(
        positions, nodeVelocities(chain, nodes), 0 until nodes, rest, vine.start, vine.end, vine.fromBreak, vine.dropsOnFade,
        vine.spacing, vine.id, width = vine.width,
    ) ?: return null
    val spacing = vine.spacing
    replaceRebuilt(data, vine, piece) { node -> extendedNode(node, links, rest, 0.0, false, spacing) }
    return piece
}

/**
 * Puts [piece] (same id) in place of [vine]: the credit goes with it, the old chain goes, and each joint to a node of
 * [vine] moves to node [renumber] of [piece].
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.replaceRebuilt(data: WoggakVineData, vine: WoggakVine, piece: WoggakVine, renumber: (Int) -> Int) {
    piece.reelCredit = vine.reelCredit
    if (!shipObjectWorld.deleteChain(vine.chain)) LOG.warn("Woggak Vine {} was already removed", vine.chain.value)
    data.remove(vine)
    for (other in data.vines) {
        for (anchor in arrayOf(other.start, other.end)) {
            val joint = anchor.point?.joint ?: continue
            if (joint.vine != vine.id) continue
            // The old pair holds node ids of the deleted chain; followJoint makes a new one.
            anchor.pair = null
            joint.node = renumber(joint.node)
        }
    }
    data.anchorsDirty = true
    data.jointsDirty = true
    data.setDirty()
}
