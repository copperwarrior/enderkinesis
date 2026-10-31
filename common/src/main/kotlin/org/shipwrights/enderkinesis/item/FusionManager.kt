package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.item.FusionGeometry.Lifted
import org.shipwrights.enderkinesis.item.FusionGeometry.ShipBlocks
import org.shipwrights.enderkinesis.physics.FusionPullAttachment
import org.shipwrights.enderkinesis.physics.ShipGravityHold
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.ShipWelderBridge
import org.valkyrienskies.core.api.attachment.getOrPutAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

/** Staff of Fusion: ships in the bubble are pulled together, and two ships whose blocks touch are
 *  welded where they touch. Geometry is in [FusionGeometry]. */
object FusionManager {

    private val LOG = LogUtils.getLogger()

    const val RADIUS: Double = 12.0

    /** Ticks for the bubble to fly from the staff to its center. Ships stay free until it lands. */
    const val THROW_TICKS: Float = 18f

    private const val REACH: Double = 64.0
    /** Distance from the cast surface to the bubble center. */
    private const val SURFACE_OFFSET: Double = 2.0
    private const val MAX_SHIPS: Int = 12
    /** Pull targets lie within this fraction of [RADIUS] from the center. */
    private const val INNER_FRACTION: Double = 0.5
    /** Blocks per tick that a hold point moves toward its next random point. */
    private const val DRIFT_PER_TICK: Double = 0.025
    /** Blocks this far outside the bubble are scanned too, as ships move between scans. */
    private const val SCAN_MARGIN: Double = 4.0
    private const val SCAN_INTERVAL_TICKS: Int = 10
    /** Ticks between the block passes: claims, the inside share and the neighbor search. The forces
     *  and the contact test use the result each tick. */
    private const val REFRESH_INTERVAL_TICKS: Int = 5
    /** A claim holds across one refresh gap, so the bubbles that contest a ship can compare. */
    private const val CLAIM_TICKS: Int = REFRESH_INTERVAL_TICKS + 1
    private const val SYNC_INTERVAL_TICKS: Int = 10
    private const val STALE_TICKS: Int = 2
    private const val REFUSAL_COOLDOWN_TICKS: Int = 20
    /** Contact welds per tick, on pairs with no ship in common. Each weld reads every block of both ships. */
    private const val MAX_WELDS_PER_TICK: Int = 4
    /** Largest jump, in blocks, of the moved block to its welded cell. Above this the pull continues. */
    private const val MAX_SNAP: Double = 0.6
    /** Rotation error, in radians, below which a touching pair counts as aligned for the weld. */
    private const val ALIGN_TOLERANCE: Double = 0.087
    /** Touching pairs that cannot align in this time weld anyway. */
    private const val ALIGN_TIMEOUT_TICKS: Int = 60

    /** A ship's pull target. It drifts toward [goal], a random inner point, and picks a new one there. */
    private class HoldPoint(val current: Vector3d, val goal: Vector3d)

    private class Aim(val surface: Vec3, val center: Vec3)

    /** The nearest inside blocks of a ship and its closest neighbor ship. */
    class Neighbor(val otherId: Long, val ownBlock: Vector3d, val otherBlock: Vector3d, val distanceSq: Double)

    /** The bubble that pulls a ship. With overlapping bubbles, the nearest center wins. */
    private class Claim(var owner: UUID, var distanceSq: Double, var tick: Int)

    private class Bubble(val level: ServerLevel, val surface: Vec3, val center: Vec3, val startTick: Int) {
        var lastTick: Int = startTick
        var nextScanTick: Int = startTick
        var nextSyncTick: Int = startTick
        var nextRefreshTick: Int = startTick
        var lifted: List<Lifted> = emptyList()
        val blocks: Long2ObjectOpenHashMap<ShipBlocks> = Long2ObjectOpenHashMap()
        val refusedUntil: Long2IntOpenHashMap = Long2IntOpenHashMap()
        val contactSince: Long2IntOpenHashMap = Long2IntOpenHashMap()
        val touching: LongOpenHashSet = LongOpenHashSet()
        /** Ships in a weld this tick. Their scans are stale until the next refresh. */
        val welded: LongOpenHashSet = LongOpenHashSet()
        /** The tick of the last weld, or of the landing; [teleport] starts after a long gap. */
        var lastWeldTick: Int = startTick + THROW_TICKS.toInt()
        val teleport: FusionTeleport = FusionTeleport()
        val summon: FusionSummon = FusionSummon()
        /** Each lifted ship's pull target, kept for the cast. */
        val pullTargets: Long2ObjectOpenHashMap<HoldPoint> = Long2ObjectOpenHashMap()
        val neighbors: Long2ObjectOpenHashMap<Neighbor> = Long2ObjectOpenHashMap()
        /** True while two or more ships are pulled. The client plays the pull loop from it. */
        var pulling: Boolean = false
    }

    // Server thread only.
    private val bubbles = HashMap<UUID, Bubble>()
    private val claims = Long2ObjectOpenHashMap<Claim>()

    fun init() {
        TickEvent.SERVER_POST.register(TickEvent.Server(::sweepStale))
        PlayerEvent.PLAYER_QUIT.register { end(it) }
        // Loads the bridge now, so a VS2 change shows in the log at startup, not at the first weld.
        if (!ShipWelderBridge.isAvailable()) LOG.warn("Staff of Fusion: VS2 welding is not available")
    }

    fun begin(level: ServerLevel, player: Player) {
        val aim = aim(level, player)
        bubbles[player.uuid] = Bubble(level, aim.surface, aim.center, level.server.tickCount)
        playAt(level, player.x, player.y, player.z, EKSounds.FUSION_CAST.get())
    }

    fun end(player: Player) {
        val bubble = bubbles.remove(player.uuid) ?: return
        val c = bubble.center
        playAt(bubble.level, c.x, c.y, c.z, EKSounds.FUSION_RELEASE.get())
    }

    fun tick(level: ServerLevel, player: Player) {
        val now = level.server.tickCount
        val existing = bubbles[player.uuid]
        if (existing == null || existing.level !== level) begin(level, player)
        val bubble = bubbles.getValue(player.uuid)
        bubble.lastTick = now
        if (now >= bubble.nextSyncTick) {
            FusionNetwork.broadcastBubble(level, player, bubble.surface, bubble.center, bubble.pulling)
            bubble.nextSyncTick = now + SYNC_INTERVAL_TICKS
        }
        bubble.pulling = false
        if (now - bubble.startTick < THROW_TICKS) return
        val center = Vector3d(bubble.center.x, bubble.center.y, bubble.center.z)
        if (now >= bubble.nextRefreshTick) {
            refresh(level, player, bubble, center, now)
            bubble.nextRefreshTick = now + REFRESH_INTERVAL_TICKS
        }
        val lifted = bubble.lifted
        bubble.summon.update(level, lifted, center, now)
        // A lone ship has nothing to fuse with.
        if (lifted.size < 2) {
            // The last parts of a summon can be on the kept ship alone.
            if (bubble.summon.active) teleportBlock(level, bubble, lifted, now)
            return
        }
        bubble.pulling = true
        applyForces(level, bubble, lifted, center)
        val fused = fuseTouching(level, bubble, lifted, now)
        if (fused) {
            bubble.lastWeldTick = now
            bubble.teleport.reset()
            bubble.summon.invalidate()
        } else {
            teleportBlock(level, bubble, lifted, now)
        }
        if (fused) {
            // Only the welded ships changed; the deleted source is dropped from the lifted set at the refresh.
            for (id in bubble.welded) bubble.blocks.remove(id)
            bubble.nextRefreshTick = now + 1
        }
    }

    /** The block passes. A full rescan runs every [SCAN_INTERVAL_TICKS]; between them only ships
     *  that came into the bubble are scanned. */
    private fun refresh(level: ServerLevel, player: Player, bubble: Bubble, center: Vector3d, now: Int) {
        val ships = shipsInBubble(level, bubble.center).filter { claim(it, player.uuid, center, now) }
        if (now >= bubble.nextScanTick) {
            bubble.blocks.clear()
            bubble.nextScanTick = now + SCAN_INTERVAL_TICKS
        }
        for (ship in ships) {
            if (!bubble.blocks.containsKey(ship.id)) {
                bubble.blocks.put(ship.id, FusionGeometry.scan(level, ship, center, RADIUS + SCAN_MARGIN))
            }
        }
        bubble.lifted = fusable(ships.mapNotNull { ship ->
            bubble.blocks.get(ship.id)?.let { FusionGeometry.lifted(ship, it, center, RADIUS) }
        })
        if (bubble.lifted.size < 2) return
        refreshNeighbors(bubble, bubble.lifted)
        FusionNetwork.broadcastTethers(level, player, bubble.center, bubble.neighbors)
    }

    private fun applyForces(level: ServerLevel, bubble: Bubble, lifted: List<Lifted>, center: Vector3d) {
        for (entry in lifted) {
            val hold = bubble.pullTargets.computeIfAbsent(entry.ship.id) { _ ->
                HoldPoint(randomInnerPoint(level, center), randomInnerPoint(level, center))
            }
            drift(level, hold, center)
            ShipGravityHold.hold(level, entry.ship)
            val attachment = entry.ship.getOrPutAttachment { FusionPullAttachment() }
            attachment.pullToward(hold.current, entry.share, entry.centroid)
            val slot = bubble.summon.slot(entry.ship.id)
            if (slot != null) {
                attachment.attractTo(slot.anchor.id, slot.partCenter, slot.cellCenter)
                continue
            }
            val neighbor = bubble.neighbors.get(entry.ship.id) ?: continue
            if (lifted.any { it.ship.id == neighbor.otherId }) {
                attachment.attractTo(neighbor.otherId, neighbor.ownBlock, neighbor.otherBlock)
            }
        }
    }

    private fun teleportBlock(level: ServerLevel, bubble: Bubble, lifted: List<Lifted>, now: Int) {
        val move = bubble.teleport.tick(level, lifted, bubble.summon, bubble.lastWeldTick, now) ?: return
        applyMove(level, bubble, move, now)
    }

    private fun applyMove(level: ServerLevel, bubble: Bubble, move: FusionTeleport.Move, now: Int) {
        playWeld(level, move.point, move.state)
        FusionNetwork.broadcastWeld(level, move.point)
        // A moved block leaves the scans nearly right; only a deleted ship makes the lifted set stale.
        if (move.deletedShip) bubble.nextRefreshTick = now + 1
    }

    /** Catches a cast that ends without releaseUsing: a slot change, death or dimension change. */
    private fun sweepStale(server: MinecraftServer) {
        val now = server.tickCount
        if (bubbles.isNotEmpty()) bubbles.values.removeIf { now - it.lastTick > STALE_TICKS }
        if (claims.isNotEmpty()) claims.values.removeIf { now - it.tick > CLAIM_TICKS }
    }

    /** True if the bubble of [owner] may pull [ship] this tick. */
    private fun claim(ship: LoadedServerShip, owner: UUID, center: Vector3d, now: Int): Boolean {
        val distanceSq = ship.transform.positionInWorld.distanceSquared(center)
        val current = claims.get(ship.id)
        if (current == null) {
            claims.put(ship.id, Claim(owner, distanceSq, now))
            return true
        }
        if (current.owner != owner && now - current.tick <= CLAIM_TICKS && current.distanceSq <= distanceSq) return false
        current.owner = owner
        current.distanceSq = distanceSq
        current.tick = now
        return true
    }

    /** VS2 cannot weld ships of different or non-uniform scale. Such a ship is left free, so the
     *  pull does not drag it forever. */
    private fun fusable(lifted: List<Lifted>): List<Lifted> {
        val uniform = lifted.filter { FusionGeometry.uniformScale(it.ship) != null }
        return uniform.filter { a ->
            val scale = FusionGeometry.uniformScale(a.ship)!!
            uniform.any { b -> b !== a && FusionGeometry.sameScale(FusionGeometry.uniformScale(b.ship)!!, scale) }
        }
    }

    /** VS2 refuses to move a ship with joints, live or saved, but a jointed ship can keep the blocks. */
    fun isJointed(level: ServerLevel, ship: LoadedServerShip): Boolean =
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).getJointsFromShip(ship.id).isNotEmpty() ||
            level.shipObjectWorld.persistedJointStore.hasJointsForShip(ship.id)

    // VS2 clip: .location is in the world frame, but .blockPos and .direction are in the shipyard frame.
    private fun aim(level: ServerLevel, player: Player): Aim {
        val eye = player.getEyePosition(1f)
        val end = eye.add(player.getViewVector(1f).scale(REACH))
        val hit = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK || hit !is BlockHitResult) return Aim(end, end)
        val face = hit.direction
        val normal = Vector3d(face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble())
        level.getShipManagingPos(hit.blockPos)?.shipToWorld?.transformDirection(normal)?.normalize()
        return Aim(hit.location, hit.location.add(normal.x * SURFACE_OFFSET, normal.y * SURFACE_OFFSET, normal.z * SURFACE_OFFSET))
    }

    private fun shipsInBubble(level: ServerLevel, center: Vec3): List<LoadedServerShip> {
        val dim = level.dimensionId
        val found = ArrayList<LoadedServerShip>()
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dim) continue
            val box = ship.worldAABB
            val dx = maxOf(box.minX() - center.x, 0.0, center.x - box.maxX())
            val dy = maxOf(box.minY() - center.y, 0.0, center.y - box.maxY())
            val dz = maxOf(box.minZ() - center.z, 0.0, center.z - box.maxZ())
            if (dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS) found.add(ship)
        }
        if (found.size <= MAX_SHIPS) return found
        found.sortBy { it.transform.positionInWorld.distanceSquared(center.x, center.y, center.z) }
        return found.subList(0, MAX_SHIPS)
    }

    private fun drift(level: ServerLevel, hold: HoldPoint, center: Vector3d) {
        val distance = hold.current.distance(hold.goal)
        if (distance <= DRIFT_PER_TICK) {
            hold.current.set(hold.goal)
            hold.goal.set(randomInnerPoint(level, center))
            return
        }
        val t = DRIFT_PER_TICK / distance
        hold.current.add((hold.goal.x - hold.current.x) * t, (hold.goal.y - hold.current.y) * t, (hold.goal.z - hold.current.z) * t)
    }

    /** A uniform random point within [INNER_FRACTION] of the bubble radius from [center]. */
    private fun randomInnerPoint(level: ServerLevel, center: Vector3d): Vector3d {
        val random = level.random
        val direction = Vector3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
        if (direction.lengthSquared() < 1e-12) return Vector3d(center)
        val distance = RADIUS * INNER_FRACTION * Math.cbrt(random.nextDouble())
        return direction.normalize(distance).add(center)
    }

    /** For each lifted ship, the nearest inside blocks between it and its closest lifted neighbor.
     *  Pairs go nearest bounding box first; a pair whose box gap cannot beat either ship's best is skipped. */
    private fun refreshNeighbors(bubble: Bubble, lifted: List<Lifted>) {
        bubble.neighbors.clear()
        val pairs = ArrayList<Triple<Lifted, Lifted, Double>>()
        for (i in lifted.indices) for (j in i + 1 until lifted.size) {
            val a = lifted[i]
            val b = lifted[j]
            if (!FusionGeometry.sameScale(FusionGeometry.uniformScale(a.ship)!!, FusionGeometry.uniformScale(b.ship)!!)) continue
            pairs.add(Triple(a, b, FusionGeometry.aabbGapSq(a.ship, b.ship)))
        }
        pairs.sortBy { it.third }
        for ((a, b, gapSq) in pairs) {
            if (gapSq >= bestNeighborDistanceSq(bubble, a) && gapSq >= bestNeighborDistanceSq(bubble, b)) continue
            val pair = FusionGeometry.nearestPair(a, b) ?: continue
            offerNeighbor(bubble, a.ship.id, Neighbor(b.ship.id, pair.first, pair.second, pair.third))
            offerNeighbor(bubble, b.ship.id, Neighbor(a.ship.id, pair.second, pair.first, pair.third))
        }
    }

    private fun bestNeighborDistanceSq(bubble: Bubble, lifted: Lifted): Double =
        bubble.neighbors.get(lifted.ship.id)?.distanceSq ?: Double.MAX_VALUE

    private fun offerNeighbor(bubble: Bubble, shipId: Long, candidate: Neighbor) {
        val current = bubble.neighbors.get(shipId)
        if (current == null || candidate.distanceSq < current.distanceSq) bubble.neighbors.put(shipId, candidate)
    }

    /** Touching ships stop tumbling, and the ship to move turns to the nearest grid alignment with
     *  the ship to keep. Welds up to [MAX_WELDS_PER_TICK] pairs with no ship in common; returns true if a weld happened. */
    private fun fuseTouching(level: ServerLevel, bubble: Bubble, lifted: List<Lifted>, now: Int): Boolean {
        bubble.touching.clear()
        bubble.welded.clear()
        try {
            for (i in lifted.indices) for (j in i + 1 until lifted.size) {
                val a = lifted[i].ship
                val b = lifted[j].ship
                if (!FusionGeometry.sameScale(FusionGeometry.uniformScale(a)!!, FusionGeometry.uniformScale(b)!!)) continue
                if (!FusionGeometry.aabbsTouch(a, b)) continue
                if (bubble.welded.contains(a.id) || bubble.welded.contains(b.id)) continue
                // During a summon, the only weld is a lone part ship into its cell on the anchor.
                val slot = bubble.summon.slotFor(a.id, b.id)
                if (slot == null && bubble.summon.active) continue
                val aJointed = isJointed(level, a)
                val bJointed = isJointed(level, b)
                if (aJointed && bJointed) continue
                val contact = FusionGeometry.closestContact(a, lifted[i].blocks, b, lifted[j].blocks) ?: continue
                val key = pairKey(a.id, b.id)
                bubble.touching.add(key)
                if (!bubble.contactSince.containsKey(key)) {
                    bubble.contactSince.put(key, now)
                    val point = worldCentre(a, contact.a)
                    playAt(level, point.x, point.y, point.z, EKSounds.FUSION_CONTACT.get())
                }
                val aIsTarget = if (slot != null) slot.anchor === a else prefersAsTarget(a, aJointed, b, bJointed)
                val target = if (aIsTarget) a else b
                val source = if (aIsTarget) b else a
                val alignment = FusionGeometry.alignment(target, source, slot?.head == true)
                source.getOrPutAttachment { FusionPullAttachment() }.alignTo(alignment.goal)
                target.getOrPutAttachment { FusionPullAttachment() }.holdSteady()
                if (now < bubble.refusedUntil.getOrDefault(key, Int.MIN_VALUE) || slot?.open == false) continue
                if ((slot?.snap() ?: contact.snap) > MAX_SNAP) continue
                val aligned = alignment.error <= ALIGN_TOLERANCE ||
                    now - bubble.contactSince.get(key) >= ALIGN_TIMEOUT_TICKS
                if (!aligned) continue
                val targetPos = slot?.parent ?: if (aIsTarget) contact.a else contact.b
                val sourcePos = slot?.part ?: if (aIsTarget) contact.b else contact.a
                // The weld moves the source ship's blocks, so read the states and the point first.
                val aState = level.getBlockState(contact.a)
                val bState = level.getBlockState(contact.b)
                val point = worldCentre(a, contact.a)
                val last = slot != null && bubble.summon.isLast(level, slot.cell)
                if (tryWeld(level, bubble, key, target, targetPos, source, sourcePos, alignment.relative, now)) {
                    if (slot != null) bubble.summon.placed(level, slot.cell, last)
                    playWeld(level, point, aState, bState)
                    FusionNetwork.broadcastWeld(level, point)
                    bubble.welded.add(a.id)
                    bubble.welded.add(b.id)
                    if (bubble.welded.size >= 2 * MAX_WELDS_PER_TICK) return true
                }
            }
            return !bubble.welded.isEmpty()
        } finally {
            bubble.contactSince.keys.removeIf { !bubble.touching.contains(it) }
        }
    }

    @Suppress("LongParameterList") // A ship/block pair for each side, plus the pair state.
    private fun tryWeld(
        level: ServerLevel, bubble: Bubble, key: Long,
        target: LoadedServerShip, targetPos: BlockPos, source: LoadedServerShip, sourcePos: BlockPos,
        relative: Quaterniond, now: Int,
    ): Boolean {
        val targetFace = FusionGeometry.faceToward(target, targetPos, source, sourcePos)
        val sourceFace = FusionGeometry.sourceFaceFor(targetFace, relative)
        val refusal = ShipWelderBridge.weld(level, target, targetPos, targetFace, source, sourcePos, sourceFace)
            ?: return true
        bubble.refusedUntil.put(key, now + REFUSAL_COOLDOWN_TICKS)
        LOG.debug("Staff of Fusion: VS2 refused to weld ship {} into {}: {}", source.id, target.id, refusal)
        return false
    }

    private fun playAt(level: ServerLevel, x: Double, y: Double, z: Double, event: SoundEvent) {
        level.playSound(null, x, y, z, event, SoundSource.PLAYERS, 1f, 1f)
    }

    private fun worldCentre(ship: LoadedServerShip, pos: BlockPos): Vector3d =
        ship.shipToWorld.transformPosition(Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))

    /** The place sounds use the volume and pitch of vanilla block placement. */
    private fun playWeld(level: ServerLevel, point: Vector3d, vararg states: BlockState) {
        playAt(level, point.x, point.y, point.z, EKSounds.FUSION_WELD.get())
        for (state in states) {
            val type = state.soundType
            level.playSound(
                null, point.x, point.y, point.z, type.placeSound, SoundSource.BLOCKS,
                (type.volume + 1f) / 2f, type.pitch * 0.8f,
            )
        }
    }

    /** The ship that a weld would keep: jointed, then static, then the heaviest. */
    internal fun keptShip(level: ServerLevel, lifted: List<Lifted>): LoadedServerShip? {
        var best: LoadedServerShip? = null
        var bestJointed = false
        for (entry in lifted) {
            val jointed = isJointed(level, entry.ship)
            val current = best
            if (current == null || prefersAsTarget(entry.ship, jointed, current, bestJointed)) {
                best = entry.ship
                bestJointed = jointed
            }
        }
        return best
    }

    /** A jointed ship stays, as VS2 cannot move it; then a static ship; otherwise the heavier ship. */
    fun prefersAsTarget(a: LoadedServerShip, aJointed: Boolean, b: LoadedServerShip, bJointed: Boolean): Boolean {
        if (aJointed != bJointed) return aJointed
        if (a.isStatic != b.isStatic) return a.isStatic
        return a.inertiaData.mass >= b.inertiaData.mass
    }

    private fun pairKey(a: Long, b: Long): Long {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return (lo shl 32) xor hi
    }
}
