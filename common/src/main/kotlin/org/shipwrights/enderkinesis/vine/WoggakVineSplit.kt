package org.shipwrights.enderkinesis.vine

import dev.architectury.event.CompoundEventResult
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
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld

private const val PICK_REACH = 5.0
/** Shorter pieces are dropped; VS rejects a link shorter than one particle diameter (0.2). */
private const val MIN_PIECE_LENGTH = 0.25
/**
 * m/s. A vine at most 32 blocks long that falls its full length reaches about 25 m/s, so a node past twice that
 * comes from a solver blow-up; a split would rebuild that energy into new chains.
 */
private const val MAX_REBUILD_SPEED = 50.0
/** Half the drawn vine width plus a small margin, so the shears hit what the player sees. */
private const val SHEAR_RADIUS = 0.2
private const val MAX_PUFFS = 64
private const val PUFF_STEP = 2

/**
 * Replaces [vine] with one vine for each unbroken run of links, with [cutLink] (or -1) counted as broken too.
 * The chain API has no call to cut a link, so this deletes the chain and makes new ones; node velocities reset.
 * Each piece is laid along its deformed shape but at its rest length, so a stretched vine never makes a bigger one.
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
    val blownUp = blownUpReason(chain, positions)
    if (!shipObjectWorld.deleteChain(vine.chain)) return false
    if (blownUp != null) {
        LOG.warn("Woggak Vine {} was not split, and is removed: {}", vine.chain.value, blownUp)
        return true
    }
    val restPerLink = (status.currentRestLength / (nodes - 1)).takeIf { it.isFinite() && it > 0 } ?: VINE_TARGET_SPACING
    for (run in runs) {
        val start = if (run.first == 0) vine.start else null
        val end = if (run.last == nodes - 1) vine.end else null
        addPiece(positions, run, restPerLink * (run.last - run.first), start, end, fromBreak)
    }
    return true
}

/** Why the chain's state is not safe to rebuild from, or null when it is. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.blownUpReason(chain: ServerChain, positions: DoubleArray): String? {
    if (positions.any { !it.isFinite() }) return "a node position is not finite"
    var fastest = 0.0
    for (id in chain.nodeIds) {
        val v = shipObjectWorld.allParticles.getById(id)?.velocity ?: continue
        fastest = maxOf(fastest, v.length())
    }
    return if (fastest > MAX_REBUILD_SPEED) "a node moves at %.1f m/s".format(fastest) else null
}

/** One new vine over [run], at [restLength]; a piece shorter than [MIN_PIECE_LENGTH] is dropped. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.addPiece(
    positions: DoubleArray, run: IntRange, restLength: Double,
    start: WoggakVineAnchor?, end: WoggakVineAnchor?, fromBreak: Boolean,
) {
    if (restLength < MIN_PIECE_LENGTH) return
    val startAnchor = keptAnchor(start)
    val endAnchor = keptAnchor(end)
    val pivot = when {
        startAnchor.point != null -> FragmentPivot.FIRST
        endAnchor.point != null -> FragmentPivot.LAST
        else -> FragmentPivot.CENTROID
    }
    val path = restPath(positions, run, restLength, pivot) ?: return
    val chain = makeVineChain(path, endpointOf(startAnchor), endpointOf(endAnchor)) ?: return
    addVine(WoggakVine(chain.id, startAnchor, endAnchor, fromBreak))
}

private fun keptAnchor(anchor: WoggakVineAnchor?): WoggakVineAnchor =
    if (anchor == null || anchor.released) WoggakVineAnchor(null) else WoggakVineAnchor(anchor.point)

private fun ServerLevel.endpointOf(anchor: WoggakVineAnchor): ChainEndpoint {
    val point = anchor.point ?: return ChainEndpoint.Free
    val attachment = attachment(point)
    if (attachment == null) anchor.released = true
    return attachment?.second ?: ChainEndpoint.Free
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
            val t = rayHitsSegment(origin, dir, data.scratch, link * 3, bestT)
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
    val pick = level.pickWoggakVine(player) ?: return CompoundEventResult.pass()
    if (level.splitWoggakVine(pick.vine, pick.chain, false, pick.link)) {
        val data = level.woggakVines
        data.vines.remove(pick.vine)
        data.anchorsDirty = true
        data.setDirty()
        level.syncWoggakVines()
    }
    level.playSound(null, pick.hit.x, pick.hit.y, pick.hit.z, SoundEvents.GROWING_PLANT_CROP, SoundSource.PLAYERS, 1f, 1f)
    stack.hurtAndBreak(1, player) { it.broadcastBreakEvent(hand) }
    (player as? ServerPlayer)?.swing(hand, true)
    return CompoundEventResult.interruptTrue(stack)
}

/**
 * Ray distance to the closest approach of the ray and the segment at [k], or -1 when that approach is
 * farther than [SHEAR_RADIUS] from the segment or past [maxT].
 */
private fun rayHitsSegment(origin: Vector3d, dir: Vector3d, p: DoubleArray, k: Int, maxT: Double): Double {
    val ax = p[k]; val ay = p[k + 1]; val az = p[k + 2]
    val ux = p[k + 3] - ax; val uy = p[k + 4] - ay; val uz = p[k + 5] - az
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
