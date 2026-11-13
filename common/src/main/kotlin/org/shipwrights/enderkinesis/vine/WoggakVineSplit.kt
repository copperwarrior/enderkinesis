package org.shipwrights.enderkinesis.vine

import dev.architectury.event.CompoundEventResult
import java.util.Collections
import java.util.IdentityHashMap
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ShearsItem
import net.minecraft.world.level.ClipContext
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.util.pointVelocity
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.sqrt

internal const val PICK_REACH = 5.0
/** Shorter pieces are dropped; VS rejects a link shorter than one particle diameter (0.2). */
private const val MIN_PIECE_LENGTH = 0.25
/**
 * m/s, for a vine of [REBUILD_SPEED_LENGTH] or shorter. A free fall of 32 blocks at 10 m/s² gives about 25 m/s,
 * so a node past twice that comes from a solver blow-up; a split would rebuild that energy into new chains.
 */
private const val MAX_REBUILD_SPEED = 50.0
/** Free-fall speed goes up with the square root of the fall, so a longer vine gets a limit in that ratio. */
private const val REBUILD_SPEED_LENGTH = 32.0
/** Half the drawn vine width plus a small margin, so the shears hit what the player sees. */
internal const val SHEAR_RADIUS = 0.2
private const val MAX_PUFFS = 64
private const val PUFF_STEP = 2

/**
 * Replaces [vine] with one vine for each unbroken run of links, with [cutLink] (or -1) counted as broken too.
 * The chain API has no call to cut a link, so this deletes the chain and makes new ones.
 * Each piece is laid along its deformed shape but at its rest length, so a stretched vine never makes a bigger one.
 * ChainCreateData takes one initial velocity, so a piece keeps the mean velocity of its nodes, not their spin.
 * Joints and grips on a node move to the piece that holds that node; see [repointSplit].
 * Returns true when the caller must drop [vine] from the list.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.splitWoggakVine(vine: WoggakVine, chain: ServerChain, fromBreak: Boolean, cutLink: Int = -1): Boolean {
    val status = chain.status
    val nodes = status.nodeCount
    if (nodes < 2) return false
    val broken = if (cutLink in 0 until nodes - 1) status.brokenLinks + cutLink else status.brokenLinks
    val runs = unbrokenRuns(nodes, broken)
    if (runs.size == 1 && runs[0].first == 0 && runs[0].last == nodes - 1) return false
    val positions = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(positions) != nodes) return false
    val velocities = nodeVelocities(chain, nodes)
    // The paid length: a reel that has not pulled its node in yet was already paid out in items.
    val restPerLink = (status.paidLength() / (nodes - 1)).takeIf { it.isFinite() && it > 0 } ?: VINE_TARGET_SPACING
    val blownUp = blownUpReason(vine, positions, velocities, maxRebuildSpeed(restPerLink * (nodes - 1)))
    if (!shipObjectWorld.deleteChain(vine.chain)) return false
    if (blownUp != null || velocities == null) {
        LOG.warn("Woggak Vine {} was not split, and is removed: {}", vine.chain.value, blownUp)
        return true
    }
    val pieces = ArrayList<SplitPiece>(runs.size)
    for (run in runs) {
        val start = if (run.first == 0) vine.start else null
        val end = if (run.last == nodes - 1) vine.end else null
        addPiece(positions, velocities, run, restPerLink * (run.last - run.first), start, end, fromBreak, vine.dropsOnFade)
            ?.let { pieces += SplitPiece(run, it) }
    }
    for (pair in woggakVines.repointSplit(vine.id, vine, pieces, vine.grips)) pair.restoreContacts(this)
    return true
}

/** Nodes [run] of a split vine are now nodes 0 until the run size of [vine]. */
internal class SplitPiece(val run: IntRange, val vine: WoggakVine)

/**
 * After vine [oldId] is split into [pieces]: each joint on a node of it moves to the piece that holds that node, and
 * each piece gets the [grips] in its run. A joint on a node that no piece holds (a piece too short to keep) is lost,
 * and its end is released. Then the anchor walk decides which vines live. [old] is skipped while it is still listed.
 * Returns the pairs of the lost joints, so the caller gives their nodes the contacts back.
 */
internal fun WoggakVineData.repointSplit(oldId: Long, old: WoggakVine?, pieces: List<SplitPiece>, grips: List<VineGrip>): List<JointPair> {
    for (piece in pieces) {
        piece.vine.grips = grips.inRun(piece.run)
        piece.vine.hullGrips = null
    }
    val lost = ArrayList<JointPair>()
    // A piece shares its end points, and so their joints, with [old]; each joint moves once.
    val moved = Collections.newSetFromMap(IdentityHashMap<VineJoint, Boolean>())
    fun repoint(owner: WoggakVine, anchor: WoggakVineAnchor) {
        if (anchor.released) return
        val joint = anchor.point?.joint ?: return
        if (joint.vine != oldId || !moved.add(joint)) return
        val piece = pieces.firstOrNull { joint.node in it.run }
        touch(owner)
        if (piece == null) {
            anchor.pair?.let(lost::add)
            anchor.pair = null
            anchor.released = true
            return
        }
        // The old pair holds node ids of the deleted chain; followJoint makes a new one.
        anchor.pair = null
        joint.vine = piece.vine.id
        joint.node -= piece.run.first
        joint.home = piece.vine.home
    }
    for (vine in vines) {
        if (vine === old) continue
        repoint(vine, vine.start)
        repoint(vine, vine.end)
    }
    anchorsDirty = true
    jointsDirty = true
    setDirty()
    return lost
}

/** World velocities (x, y, z triples) of the nodes, NaN for a node not found; null when a velocity is not finite. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.nodeVelocities(chain: ServerChain, nodes: Int): DoubleArray? {
    val out = DoubleArray(nodes * 3) { Double.NaN }
    val ids = chain.nodeIds
    for (i in 0 until minOf(nodes, ids.size)) {
        val v = shipObjectWorld.allParticles.getById(ids[i])?.velocity ?: continue
        if (!v.isFinite) return null
        out[i * 3] = v.x(); out[i * 3 + 1] = v.y(); out[i * 3 + 2] = v.z()
    }
    return out
}

/**
 * Why the chain's state is not safe to rebuild from, or null when it is. Speed is measured against the anchor ships,
 * so a vine on a fast ship is not taken for a blow-up.
 */
private fun ServerLevel.blownUpReason(vine: WoggakVine, positions: DoubleArray, velocities: DoubleArray?, maxSpeed: Double): String? {
    if (positions.any { !it.isFinite() }) return "a node position is not finite"
    if (velocities == null) return "a node velocity is not finite"
    val start = carrierVelocity(vine.start)
    val end = carrierVelocity(vine.end)
    val nodes = velocities.size / 3
    val carrier = Vector3d()
    var fastest = 0.0
    for (i in 0 until nodes) {
        val k = i * 3
        if (velocities[k].isNaN()) continue
        carrier.set(start).lerp(end, i / (nodes - 1.0))
        fastest = maxOf(fastest, carrier.distance(velocities[k], velocities[k + 1], velocities[k + 2]))
    }
    return if (fastest > maxSpeed) "a node moves at %.1f m/s against its anchors".format(fastest) else null
}

private fun maxRebuildSpeed(restLength: Double): Double =
    MAX_REBUILD_SPEED * sqrt(maxOf(1.0, restLength / REBUILD_SPEED_LENGTH))

/**
 * One new vine over [run], at [restLength]; a piece shorter than [MIN_PIECE_LENGTH] is dropped. Null [velocities]
 * start the piece at rest. [fallbackHome] is the home when the piece's own home chunk is not loaded.
 */
@Suppress("LongParameterList") // The run, its two ends, its flags and its id: one piece of a vine.
@OptIn(GameTickOnly::class)
internal fun ServerLevel.addPiece(
    positions: DoubleArray, velocities: DoubleArray?, run: IntRange, restLength: Double,
    start: WoggakVineAnchor?, end: WoggakVineAnchor?, fromBreak: Boolean, dropsOnFade: Boolean,
    id: Long = newVineId(), fallbackHome: Long? = null,
): WoggakVine? {
    if (restLength < MIN_PIECE_LENGTH) return null
    val startAnchor = start.kept()
    val endAnchor = end.kept()
    val pivot = when {
        startAnchor.point != null -> FragmentPivot.FIRST
        endAnchor.point != null -> FragmentPivot.LAST
        else -> FragmentPivot.CENTROID
    }
    val path = restPath(positions, run, restLength, pivot) ?: return null
    val startHold = holdOf(startAnchor)
    val endHold = holdOf(endAnchor)
    // VS puts an attached end node on the anchor; the whole path moves with it, so no link starts stretched.
    when (pivot) {
        FragmentPivot.FIRST -> startHold?.let { path.moveTo(0, it.first) }
        FragmentPivot.LAST -> endHold?.let { path.moveTo(path.lastIndex, it.first) }
        FragmentPivot.CENTROID -> Unit
    }
    // VS adds the carrier velocity of a body end to each node, so only the rest is passed in.
    val initial = velocities?.meanRelative(run, carrierVelocity(startAnchor.point, startHold), carrierVelocity(endAnchor.point, endHold))
        ?: Vector3d()
    val chain = makeVineChain(path, startHold?.second ?: ChainEndpoint.Free, endHold?.second ?: ChainEndpoint.Free, initial) ?: return null
    val vine = WoggakVine(id, chain.id, startAnchor, endAnchor, fromBreak).also { it.dropsOnFade = dropsOnFade }
    addVine(vine, path, fallbackHome)
    return vine
}

private fun WoggakVineAnchor?.kept(): WoggakVineAnchor =
    if (this == null || released) WoggakVineAnchor(null) else WoggakVineAnchor(point)

/**
 * World position and endpoint of [anchor]; null for a free end, and an anchor that lost its hold is released.
 * A joined end stays: its target can be in a chunk that is not loaded, and followJoint frees it when the target is gone.
 */
private fun ServerLevel.holdOf(anchor: WoggakVineAnchor): Pair<Vector3d, ChainEndpoint>? {
    val point = anchor.point ?: return null
    return attachment(point).also { if (it == null && point.joint == null) anchor.released = true }
}

private fun List<Vector3d>.moveTo(index: Int, target: Vector3dc) {
    val shift = Vector3d(target).sub(this[index])
    for (p in this) p.add(shift)
}

/** The velocity VS gives a node held at [hold]: the ship's point velocity for a body end, else zero. */
private fun ServerLevel.carrierVelocity(point: VinePoint?, hold: Pair<Vector3d, ChainEndpoint>?): Vector3d {
    if (hold == null || hold.second !is ChainEndpoint.Body) return Vector3d()
    val ship = point?.shipId?.let { shipObjectWorld.allShips.getById(it) } ?: return Vector3d()
    return ship.pointVelocity(hold.first, Vector3d())
}

private fun ServerLevel.carrierVelocity(anchor: WoggakVineAnchor): Vector3d {
    val point = anchor.point
    if (anchor.released || point == null) return Vector3d()
    return carrierVelocity(point, attachment(point))
}

/** Mean of the [run] node velocities less the carrier velocity, which VS spreads from [start] to [end]. */
private fun DoubleArray.meanRelative(run: IntRange, start: Vector3dc, end: Vector3dc): Vector3d {
    val sum = Vector3d()
    val carrier = Vector3d()
    val span = (run.last - run.first).toDouble()
    var count = 0
    for (i in run) {
        val k = i * 3
        if (this[k].isNaN()) continue
        carrier.set(start).lerp(end, (i - run.first) / span)
        sum.add(this[k] - carrier.x, this[k + 1] - carrier.y, this[k + 2] - carrier.z)
        count++
    }
    return if (count == 0) sum else sum.div(count.toDouble())
}

@OptIn(GameTickOnly::class)
internal fun ServerLevel.puffVine(chain: ServerChain) {
    val nodes = chain.status.nodeCount
    if (nodes < 1) return
    val positions = DoubleArray(nodes * 3)
    if (chain.copyNodePositions(positions) != nodes) return
    val moss = BlockParticleOption(ParticleTypes.BLOCK, EKBlocks.WOHLON_MOSS.get().defaultBlockState())
    var puffs = 0
    var i = 0
    while (i < nodes && puffs < MAX_PUFFS) {
        val k = i * 3
        sendParticles(moss, positions[k], positions[k + 1], positions[k + 2], 2, 0.08, 0.08, 0.08, 0.02)
        puffs++
        i += PUFF_STEP
    }
}

class VinePick(val vine: WoggakVine, val chain: ServerChain, val link: Int, val hit: Vector3d)

/** The unfaded vine link nearest along the player's look, closer than any block and [PICK_REACH]. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.pickWoggakVine(player: Player): VinePick? {
    val data = woggakVines
    if (data.vines.isEmpty()) return null
    val eye = player.eyePosition
    val look = player.getViewVector(1f)
    val reachEnd = eye.add(look.scale(PICK_REACH))
    val blockHit = clip(ClipContext(eye, reachEnd, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
    val origin = Vector3d(eye.x, eye.y, eye.z)
    val dir = Vector3d(look.x, look.y, look.z)

    var best: VinePick? = null
    var bestT = blockHit.location.distanceTo(eye)
    for (vine in data.vines) {
        if (vine.fadeStart >= 0) continue
        val chain = shipObjectWorld.allChains.getById(vine.chain) ?: continue
        val nodes = chain.status.nodeCount
        if (nodes < 2) continue
        if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
        if (chain.copyNodePositions(data.scratch) != nodes) continue
        for (link in 0 until nodes - 1) {
            val t = data.scratch.rayHitsSegment(origin, dir, link * 3, bestT)
            if (t < 0) continue
            bestT = t
            best = VinePick(vine, chain, link, Vector3d(dir).mul(t).add(origin))
        }
    }
    return best
}

/** The node of [pick]'s link nearest to the hit point. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.nearestNode(pick: VinePick): Int {
    val data = woggakVines
    val nodes = pick.chain.status.nodeCount
    if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
    if (pick.chain.copyNodePositions(data.scratch) != nodes) return pick.link
    val k = pick.link * 3
    val p = data.scratch
    val d0 = pick.hit.distanceSquared(p[k], p[k + 1], p[k + 2])
    val d1 = pick.hit.distanceSquared(p[k + 3], p[k + 4], p[k + 5])
    return if (d1 < d0) pick.link + 1 else pick.link
}

@OptIn(GameTickOnly::class)
internal fun shearWoggakVine(player: Player, hand: InteractionHand): CompoundEventResult<ItemStack> {
    val stack = player.getItemInHand(hand)
    if (stack.item !is ShearsItem) return CompoundEventResult.pass()
    val level = player.level() as? ServerLevel ?: return CompoundEventResult.pass()
    val data = level.woggakVines
    // Shears in both hands send a use packet for each hand on one click.
    if (data.lastPlayerCut[player.uuid] == level.gameTime) return CompoundEventResult.pass()
    val pick = level.pickWoggakVine(player) ?: return CompoundEventResult.pass()
    val firstPiece = data.vines.size
    if (!level.splitWoggakVine(pick.vine, pick.chain, false, pick.link)) return CompoundEventResult.pass()
    data.lastPlayerCut[player.uuid] = level.gameTime
    val pieces = data.vines.subList(firstPiece, data.vines.size).toList()
    data.remove(pick.vine)
    if (player.isCreative) {
        // Fade now, so that only the pieces this cut set free lose the drop, not anchored ones that fade later.
        data.startFadeOfUnanchored(level.gameTime) { level.isJointWaiting(data, it) }
        for (piece in pieces) if (piece.fadeStart >= 0) piece.dropsOnFade = false
    }
    data.anchorsDirty = true
    data.jointsDirty = true
    data.setDirty()
    level.playSound(null, pick.hit.x, pick.hit.y, pick.hit.z, SoundEvents.GROWING_PLANT_CROP, SoundSource.PLAYERS, 1f, 1f)
    stack.hurtAndBreak(1, player) { it.broadcastBreakEvent(hand) }
    (player as? ServerPlayer)?.swing(hand, true)
    return CompoundEventResult.interruptTrue(stack)
}

/**
 * Ray distance to the closest approach of the ray and the segment at [k], or -1 when that approach is
 * farther than [SHEAR_RADIUS] from the segment or past [maxT].
 */
internal fun DoubleArray.rayHitsSegment(origin: Vector3d, dir: Vector3d, k: Int, maxT: Double): Double {
    val ax = this[k]; val ay = this[k + 1]; val az = this[k + 2]
    val ux = this[k + 3] - ax; val uy = this[k + 4] - ay; val uz = this[k + 5] - az
    val wx = origin.x - ax; val wy = origin.y - ay; val wz = origin.z - az
    val a = dir.x * dir.x + dir.y * dir.y + dir.z * dir.z
    val b = dir.x * ux + dir.y * uy + dir.z * uz
    val c = ux * ux + uy * uy + uz * uz
    val d = dir.x * wx + dir.y * wy + dir.z * wz
    val e = ux * wx + uy * wy + uz * wz
    val den = a * c - b * b
    var s = if (den > 1e-12) ((b * e - c * d) / den) else 0.0
    var u = if (c > 1e-12) ((b * s + e) / c).coerceIn(0.0, 1.0) else 0.0
    s = ((b * u - d) / a)
    if (s < 0 || s > maxT) return -1.0
    if (c > 1e-12) u = ((b * s + e) / c).coerceIn(0.0, 1.0)
    val dx = origin.x + dir.x * s - (ax + ux * u)
    val dy = origin.y + dir.y * s - (ay + uy * u)
    val dz = origin.z + dir.z * s - (az + uz * u)
    return if (dx * dx + dy * dy + dz * dz <= SHEAR_RADIUS * SHEAR_RADIUS) s else -1.0
}
