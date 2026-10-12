package org.shipwrights.enderkinesis.remnant

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.util.RandomSource
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import java.math.BigInteger
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.AABB
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.dimension.SselithBlockTracker
import org.shipwrights.enderkinesis.dimension.SselithParallel
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import kotlin.math.sqrt

/**
 * Simulation for the **Sselith Remnant**: a pseudo-entity with no Minecraft
 * [net.minecraft.world.entity.Entity], just a pool of particles that the mod
 * integrates itself. Each particle draws as one cube, so a mass reads as a
 * cluster of cubes.
 *
 * **Where it comes from.** Each position a player placed or broke can seep a
 * droplet on [SselithBlockTracker]'s random tick, but only when a player on
 * a *different* parallel is near. The droplet belongs to that
 * player's parallel: only players on it see it, are hunted by it, or take
 * its damage, and particles on different parallels never interact. A
 * particle with no such player near is removed.
 *
 * **Motion.** Particles probe for surfaces and adhere to them, keeping only a
 * trickle of gravity, so the mass slides down walls and oozes over ledges.
 *
 * **Particles are never consolidated.** A mass grows by gaining particles.
 * Touching particles share volume so the middle of a mass is larger than
 * its edge, but every exchange is between two particles, so a mass keeps
 * its total. Repulsion holds the spacing (see [applyParticleForces]).
 *
 * Everything is bounded per CLAUDE.md rules 1–2: a hard particle cap and an
 * iterative spatial-hash sweep (no recursion).
 *
 * Server thread only.
 */
object IrreverantRemnantManager {

    @Volatile
    private var data: IrreverantRemnantData? = null

    @Volatile
    private var level: ServerLevel? = null

    /** Spatial hash, rebuilt each tick and shared by merging, clustering,
     *  and field queries; retained to avoid re-allocating. */
    private val buckets = Long2ObjectOpenHashMap<MutableList<IrreverantRemnantBlob>>()
    private val scratchPos = BlockPos.MutableBlockPos()

    /** Reads real collision shapes for contact and surface normals. */
    private val surface = RemnantSurfaceProbe()

    /** Scratch for tangential steering projection. */
    private val tangent = DoubleArray(3)

    /** Union-find scratch for connected-mass detection, grown as needed. */
    private var parent = IntArray(0)
    private val indexOf = it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<IrreverantRemnantBlob>()
    private val pathfinder = RemnantPathfinder()

    /** One route per cluster root, gathered from whichever member already
     *  holds one so a mass keeps its path across reclustering. */
    private val clusterPath = it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<RemnantPath>()
    private val pathOwner = it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<RemnantPath>()
        .apply { defaultReturnValue(NO_ROOT) }
    private val pathScratch = it.unimi.dsi.fastutil.longs.LongArrayList()
    private val pathStart = BlockPos.MutableBlockPos()
    private val pathGoal = BlockPos.MutableBlockPos()

    private val clusterCount = it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap()
    private val clusterParallel = it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap()

    /** Parallel table index of each player in this tick's player list, or -1 when no particle is on it. */
    private var playerParallels = IntArray(0)


    /** Distinct cluster roots this pass, so a mass can look for others.
     *  There are only ever a handful, so scanning them is free. */
    private val clusterRoots = it.unimi.dsi.fastutil.ints.IntArrayList()
    private var seekX = 0.0
    private var seekY = 0.0
    private var seekZ = 0.0

    private val clusterVolume = it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap()
    private val clusterX = it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap()
    private val clusterY = it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap()
    private val clusterZ = it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap()

    /** Each mass's shared goal, before runners take their own. */
    private val clusterGoalX = it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap()
    private val clusterGoalY = it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap()
    private val clusterGoalZ = it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap()
    private val clusterHeading = it.unimi.dsi.fastutil.ints.IntOpenHashSet()

    /** Relay scratch: member order, each member's mass, and how far ahead it sits. */
    private var relayOrder = IntArray(0)
    private var relayRoot = IntArray(0)
    private var relayAlong = DoubleArray(0)

    /** Heading and side axis of the mass [clusterGoal] last resolved. */
    private var headX = 0.0
    private var headY = 0.0
    private var headZ = 0.0
    private var sideX = 0.0
    private var sideY = 0.0
    private var sideZ = 0.0

    fun init() {
        LifecycleEvent.SERVER_LEVEL_LOAD.register { serverLevel ->
            if (serverLevel.dimension() != SselithRepertory.LEVEL_KEY) return@register
            level = serverLevel
            data = serverLevel.dataStorage.computeIfAbsent(
                IrreverantRemnantData::load,
                ::IrreverantRemnantData,
                IrreverantRemnantData.NAME,
            )
        }
        LifecycleEvent.SERVER_LEVEL_UNLOAD.register { serverLevel ->
            if (serverLevel.dimension() != SselithRepertory.LEVEL_KEY) return@register
            level = null
            data = null
            // Static owners must not keep the old level or its blobs after an integrated server stops.
            surface.unbind()
            buckets.clear()
        }
        // The remnant seeps from player edits — the tracker's
        // random tick is the spawn clock.
        SselithBlockTracker.addRandomTickListener { tickLevel, pos, _, _, parallel ->
            maybeDrip(tickLevel, pos, parallel)
        }
        TickEvent.SERVER_LEVEL_POST.register { serverLevel ->
            if (serverLevel === level) simulate(serverLevel)
        }
    }

    // ------------------------------------------------------------ spawning

    /** A tracked player edit made on [editParallel] just random-ticked. When
     *  a player on another parallel is near, seep a droplet into the open air
     *  beside it. Light does not matter: the parallel rule is the gate. */
    private fun maybeDrip(tickLevel: ServerLevel, pos: BlockPos, editParallel: BigInteger) {
        val store = data ?: return
        if (store.blobs.size >= MAX_BLOBS) return
        if (tickLevel.random.nextFloat() >= DRIP_CHANCE) return
        val witness = nearestOtherParallel(tickLevel, pos, editParallel) ?: return

        // Seep into whichever open face is available — the underside first
        // so it reads as dripping off the edit, then the sides.
        val face = openFaceNear(tickLevel, pos) ?: return
        seepOn(store, pos, face, tickLevel.random, store.internParallel(SselithParallel.get(witness)), 0)
        store.setDirty()
    }

    /** The nearest non-spectator within [DRIP_WITNESS_RANGE] of [pos] whose parallel is not [editParallel]. */
    private fun nearestOtherParallel(tickLevel: ServerLevel, pos: BlockPos, editParallel: BigInteger): ServerPlayer? {
        var best: ServerPlayer? = null
        var bestSq = DRIP_WITNESS_RANGE * DRIP_WITNESS_RANGE
        for (player in tickLevel.players()) {
            if (player.isSpectator) continue
            val distSq = player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            if (distSq >= bestSq || SselithParallel.get(player) == editParallel) continue
            best = player
            bestSq = distSq
        }
        return best
    }

    /** Seeps a cluster of particles on [parallel] out of [face] of the block
     *  at [pos], one after another — a usable lump of remnant. A single
     *  particle is a speck, so a cluster is the meaningful unit for testing. */
    fun spawnCluster(
        spawnLevel: ServerLevel,
        pos: BlockPos,
        face: Direction,
        particles: Int,
        parallel: BigInteger,
    ): Boolean {
        if (spawnLevel !== level) return false
        val store = data ?: return false
        val parallelIndex = store.internParallel(parallel)
        // Make room rather than silently doing nothing: a testing tool that
        // quietly fails once the pool fills up is worse than useless. The
        // oldest particles go first.
        val overflow = store.blobs.size + particles - MAX_BLOBS
        if (overflow > 0) {
            repeat(minOf(overflow, store.blobs.size)) { store.blobs.removeAt(0).evicted = true }
        }
        var spawned = 0
        repeat(particles) {
            if (store.blobs.size >= MAX_BLOBS) return@repeat
            // Each starts to form a little after the one before, so they come out as a run of drops and not as
            // one blob that bursts when the hold ends.
            seepOn(store, pos, face, spawnLevel.random, parallelIndex, -spawned * CLUSTER_STAGGER)
            spawned++
        }
        if (spawned > 0) store.setDirty()
        return spawned > 0
    }

    /**
     * Adds one particle seeping out of [face] of the block at [pos]: resting
     * on the face and stuck to it, so it forms there before gravity gets hold
     * of it. A negative [spawnAge] delays the start of its forming.
     */
    @Suppress("LongParameterList") // One particle's birth; every value differs per caller.
    private fun seepOn(
        store: IrreverantRemnantData,
        pos: BlockPos,
        face: Direction,
        random: RandomSource,
        parallel: Int,
        spawnAge: Int,
    ) {
        val nx = face.stepX.toDouble()
        val ny = face.stepY.toDouble()
        val nz = face.stepZ.toDouble()
        val gap = 0.5 + DRIP_RADIUS * IrreverantRemnantMotion.CONTACT_RADIUS_FACTOR
        val blob = store.newBlob(
            pos.x + 0.5 + nx * gap + faceJitter(nx, random),
            pos.y + 0.5 + ny * gap + faceJitter(ny, random),
            pos.z + 0.5 + nz * gap + faceJitter(nz, random),
            0.0, 0.0, 0.0,
            jitteredRadius(random),
            parallel,
        )
        blob.normalX = nx
        blob.normalY = ny
        blob.normalZ = nz
        blob.onSurface = true
        blob.spawnAge = spawnAge
        store.blobs.add(blob)
    }

    /** Removes every particle on [parallel] within [radius] of a point.
     *  The way to reset a test site while standing in it. Other parallels are left alone because their players can see
     *  those particles and this one cannot. */
    fun clearNear(
        clearLevel: ServerLevel,
        x: Double,
        y: Double,
        z: Double,
        radius: Double,
        parallel: BigInteger,
    ): Int {
        if (clearLevel !== level) return 0
        val store = data ?: return 0
        val parallelIndex = store.parallelIndexOf(parallel)
        if (parallelIndex < 0) return 0
        val rSq = radius * radius
        val before = store.blobs.size
        store.blobs.removeAll { blob ->
            if (blob.parallel != parallelIndex) return@removeAll false
            val dx = blob.x - x
            val dy = blob.y - y
            val dz = blob.z - z
            // The flag stops a hanging drip that still has this blob as its parent.
            (dx * dx + dy * dy + dz * dz <= rSq).also { if (it) blob.evicted = true }
        }
        val removed = before - store.blobs.size
        if (removed > 0) store.setDirty()
        return removed
    }

    /** Centre of an air block adjacent to [pos], preferring below. */
    private fun openFaceNear(tickLevel: ServerLevel, pos: BlockPos): Direction? {
        for (dir in DRIP_FACES) {
            scratchPos.set(pos.x + dir.stepX, pos.y + dir.stepY, pos.z + dir.stepZ)
            if (tickLevel.getBlockState(scratchPos).isAir) return dir
        }
        return null
    }

    /** A particle's size, varied per particle. Uniform radii make a mass
     *  read as a regular lattice. Rest spacing, contact depth and cube size
     *  all derive from radius, so varying it stays self-consistent. */
    private fun jitteredRadius(random: RandomSource): Double =
        DRIP_RADIUS * (RADIUS_MIN + random.nextDouble() * (RADIUS_MAX - RADIUS_MIN))

    /** Scatter across the face, on the two axes that lie in its plane, so
     *  drips do not all seep from the exact centre of a block. */
    private fun faceJitter(component: Double, random: RandomSource): Double =
        if (component != 0.0) 0.0 else (random.nextDouble() - 0.5) * FACE_JITTER

    // ---------------------------------------------------------- simulation

    /** True when a player on [blob]'s parallel is within the simulation range of it. */
    private fun watched(blob: IrreverantRemnantBlob, players: List<ServerPlayer>): Boolean {
        for (p in players.indices) {
            if (playerParallels[p] != blob.parallel) continue
            val player = players[p]
            val dx = blob.x - player.x
            val dy = blob.y - player.y
            val dz = blob.z - player.z
            if (dx * dx + dy * dy + dz * dz <= SIMULATION_RANGE_SQ) return true
        }
        return false
    }

    private fun simulate(tickLevel: ServerLevel) {
        val store = data ?: return
        val all = store.blobs
        if (all.isEmpty()) return

        // A particle exists only while a player on its parallel is near it.
        // The rest are removed, so they cost nothing and cannot fill the pool.
        val players = tickLevel.players()
        if (players.isEmpty()) {
            for (blob in all) blob.evicted = true
            all.clear()
            store.setDirty()
            return
        }
        if (playerParallels.size < players.size) playerParallels = IntArray(players.size * 2)
        for (p in players.indices) playerParallels[p] = store.parallelIndexOf(SselithParallel.get(players[p]))
        var kept = 0
        for (i in all.indices) {
            val blob = all[i]
            if (watched(blob, players)) all[kept++] = blob else blob.evicted = true
        }
        if (kept < all.size) {
            all.subList(kept, all.size).clear()
            store.setDirty()
        }
        if (all.isEmpty()) return
        val blobs = all

        // Cohesion needs the hash, and movement needs cohesion, so the hash
        // is built first and reused by the passes below.
        surface.bind(tickLevel)
        surface.refreshEntities(tickLevel, particleBounds(blobs))
        buildHash(blobs)
        applyParticleForces(blobs)
        for (blob in blobs) integrate(blob, tickLevel.gameTime)
        // Goal selection is the expensive part (clustering + player search),
        // so it runs on an interval; the steering it produces is applied
        // every tick from each blob's cached goal, which keeps motion smooth.
        if (tickLevel.gameTime % BEHAVIOR_INTERVAL == 0L) {
            buildHash(blobs)
            assignGoals(tickLevel, blobs, players)
        }
        damagePlayers(tickLevel, players)
        // Push blob state to nearby clients — the remnant has no entity to ride vanilla's tracking.
        if (tickLevel.gameTime % SYNC_INTERVAL == 0L) {
            IrreverantRemnantNetwork.broadcast(players, playerParallels, blobs)
        }
        store.setDirty()
    }

    // ------------------------------------------------------------ sentience

    /**
     * Groups blobs into connected masses and gives each mass one goal, so a
     * remnant behaves as a single creature rather than a swarm of droplets.
     *
     * Connectivity is by [CONNECT_REACH] within one parallel, resolved with iterative union-find over the
     * spatial hash — no recursion, per CLAUDE.md rule 1.
     *
     * A mass hunts when it is big enough to be a threat and a player is in
     * range; otherwise it wanders. The goal is written onto every member
     * blob, and [integrate] steers toward it each tick.
     */
    private fun assignGoals(
        tickLevel: ServerLevel,
        blobs: MutableList<IrreverantRemnantBlob>,
        players: List<ServerPlayer>,
    ) {
        val n = blobs.size
        if (parent.size < n) parent = IntArray(n * 2)
        for (i in 0 until n) parent[i] = i
        indexOf.clear()
        for (i in 0 until n) indexOf[blobs[i]] = i

        // Union every pair in reach. overlapping() rejects pairs on different parallels.
        for (i in 0 until n) {
            val blob = blobs[i]
            for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
                val neighbours = buckets.get(cellKey(blob, dx, dy, dz)) ?: continue
                for (other in neighbours) {
                    if (other === blob) continue
                    if (!overlapping(blob, other)) continue
                    union(i, indexOf.getInt(other))
                }
            }
        }

        // Accumulate each cluster's centroid and volume.
        clusterVolume.clear()
        clusterCount.clear()
        clusterParallel.clear()
        clusterGoalX.clear()
        clusterGoalY.clear()
        clusterGoalZ.clear()
        clusterHeading.clear()
        clusterRoots.clear()
        clusterX.clear()
        clusterY.clear()
        clusterZ.clear()
        for (i in 0 until n) {
            val root = find(i)
            val blob = blobs[i]
            val v = blob.volume
            if (clusterCount.addTo(root, 1) == 0) {
                clusterRoots.add(root)
                clusterParallel.put(root, blob.parallel)
            }
            clusterVolume.addTo(root, v)
            clusterX.addTo(root, blob.x * v)
            clusterY.addTo(root, blob.y * v)
            clusterZ.addTo(root, blob.z * v)
        }

        // Carry each cluster's existing route over. Roots are rebuilt from
        // scratch every pass, so identity comes from the members: whichever
        // one still holds a route donates it to its new root.
        clusterPath.clear()
        pathOwner.clear()
        for (i in 0 until n) {
            val held = blobs[i].path ?: continue
            val root = find(i)
            // After a split, two roots hold the same route object. One of them keeps it;
            // the other gets a new one below, or the two search again for each other.
            val owner = pathOwner.getInt(held)
            if (owner == NO_ROOT) {
                pathOwner.put(held, root)
                clusterPath.putIfAbsent(root, held)
            } else if (owner == root) {
                clusterPath.putIfAbsent(root, held)
            }
        }
        var pathBudget = MAX_PATHS_PER_TICK

        // One goal per cluster, then stamped onto its members.
        for (i in 0 until n) {
            val blob = blobs[i]
            val root = find(i)
            val volume = clusterVolume.get(root)
            if (volume <= 0.0) continue
            val cx = clusterX.get(root) / volume
            val cy = clusterY.get(root) / volume
            val cz = clusterZ.get(root) / volume

            // What a mass wants depends on how much of it there is. Below
            // [HUNT_PARTICLES] it is not a threat to anything, so it goes
            // looking for other remnant to join — which is also the only way
            // it ever becomes big enough to be one. At or above, it hunts.
            val members = clusterCount.get(root)
            val prey = if (members >= HUNT_PARTICLES) {
                nearestPrey(players, blob.parallel, cx, cy, cz)
            } else {
                null
            }
            val seeking = prey == null && members < HUNT_PARTICLES &&
                nearestOtherCluster(root, blob.parallel, cx, cy, cz)
            var path = clusterPath.get(root)
            val hunting = prey != null || seeking
            val tx: Double
            val ty: Double
            val tz: Double
            if (prey != null) {
                tx = prey.x; ty = prey.y; tz = prey.z
            } else if (seeking) {
                tx = seekX; ty = seekY; tz = seekZ
            } else if (path != null && (!path.exhausted || tickLevel.gameTime < path.failedUntil)) {
                // Also during a failure hold: the members then steer at one point, the last
                // goal of the cluster, and not each at a new random one.
                tx = path.targetX; ty = path.targetY; tz = path.targetZ
            } else {
                val random = tickLevel.random
                tx = cx + (random.nextDouble() - 0.5) * 2.0 * WANDER_RANGE
                ty = cy + (random.nextDouble() - 0.5) * WANDER_RANGE
                tz = cz + (random.nextDouble() - 0.5) * 2.0 * WANDER_RANGE
            }

            if (path == null) {
                path = RemnantPath()
                clusterPath.put(root, path)
            }
            if (pathBudget > 0 && needsRepath(path, tickLevel.gameTime, tx, ty, tz)) {
                pathBudget--
                repath(tickLevel, path, cx, cy, cz, tx, ty, tz)
            }
            advance(path, cx, cy, cz)

            blob.path = path
            blob.hunting = hunting
            updateSeep(path, cx, cy, cz, tickLevel.gameTime)
            blob.seeping = path.seeping
            if (path.exhausted) {
                blob.goalX = tx; blob.goalY = ty; blob.goalZ = tz
            } else {
                val node = path.nodes.getLong(path.index)
                blob.goalX = BlockPos.getX(node) + 0.5
                blob.goalY = BlockPos.getY(node) + 0.5
                blob.goalZ = BlockPos.getZ(node) + 0.5
            }
            if (clusterHeading.add(root)) {
                clusterGoalX.put(root, blob.goalX)
                clusterGoalY.put(root, blob.goalY)
                clusterGoalZ.put(root, blob.goalZ)
            }
        }
        relay(blobs)
    }

    /**
     * Moves each mass by leapfrogging: a few of its rear-most particles become
     * runners, which drop cohesion and viscosity and race for a point just past
     * the front, on the side they already sit. A runner that gets ahead of the
     * front rejoins, and the new rear-most particles take over.
     *
     * A plain rear speed boost does not work: viscosity spreads the extra speed
     * through the pack and a rear particle only pushes its front neighbour on,
     * so nothing ever overtakes.
     */
    private fun relay(blobs: List<IrreverantRemnantBlob>) {
        val n = blobs.size
        if (relayOrder.size < n) relayOrder = IntArray(n * 2)
        if (relayAlong.size < n) relayAlong = DoubleArray(n * 2)
        if (relayRoot.size < n) relayRoot = IntArray(n * 2)
        for (i in 0 until n) {
            relayOrder[i] = i
            relayRoot[i] = find(i)
            relayAlong[i] = alongHeading(blobs[i], relayRoot[i])
        }
        // Grouped by mass, rear-most first within each.
        it.unimi.dsi.fastutil.ints.IntArrays.quickSort(relayOrder, 0, n) { a, b ->
            val byRoot = relayRoot[a].compareTo(relayRoot[b])
            if (byRoot != 0) byRoot else relayAlong[a].compareTo(relayAlong[b])
        }
        var start = 0
        while (start < n) {
            val root = relayRoot[relayOrder[start]]
            var end = start
            while (end < n && relayRoot[relayOrder[end]] == root) end++
            relayMass(blobs, root, start, end)
            start = end
        }
    }

    /** Runs the relay for one mass, whose members are `relayOrder[start until end]`, rear-most first. */
    private fun relayMass(blobs: List<IrreverantRemnantBlob>, root: Int, start: Int, end: Int) {
        val members = end - start
        if (members < RELAY_MIN_PARTICLES || !clusterGoal(root)) {
            for (k in start until end) blobs[relayOrder[k]].endRun(1.0)
            return
        }
        var front = -Double.MAX_VALUE
        for (k in start until end) {
            if (!blobs[relayOrder[k]].running) front = maxOf(front, relayAlong[relayOrder[k]])
        }
        var runners = 0
        for (k in start until end) {
            val blob = blobs[relayOrder[k]]
            if (!blob.running) continue
            blob.runTicks += BEHAVIOR_INTERVAL.toInt()
            if (relayAlong[relayOrder[k]] >= front || blob.runTicks >= RUN_TIMEOUT) blob.endRun(FOLLOWER_PACE) else runners++
        }
        val wanted = maxOf(1, (members * RUNNER_FRACTION).toInt())
        for (k in start until end) {
            if (runners >= wanted) break
            val blob = blobs[relayOrder[k]]
            if (blob.running) continue
            blob.running = true
            blob.runTicks = 0
            runners++
        }
        val volume = clusterVolume.get(root)
        val cx = clusterX.get(root) / volume
        val cy = clusterY.get(root) / volume
        val cz = clusterZ.get(root) / volume
        for (k in start until end) {
            val blob = blobs[relayOrder[k]]
            if (!blob.running) {
                blob.steerScale = FOLLOWER_PACE
                continue
            }
            blob.steerScale = RUN_BOOST
            // Past the front, out to the side it already sits on, so it goes round the pack.
            val side = if ((blob.x - cx) * sideX + (blob.y - cy) * sideY + (blob.z - cz) * sideZ >= 0.0) 1.0 else -1.0
            val reach = front + PASS_LEAD
            blob.goalX = cx + headX * reach + sideX * side * PASS_OFFSET
            blob.goalY = cy + headY * reach + sideY * side * PASS_OFFSET
            blob.goalZ = cz + headZ * reach + sideZ * side * PASS_OFFSET
        }
    }

    private fun IrreverantRemnantBlob.endRun(scale: Double) {
        running = false
        runTicks = 0
        steerScale = scale
    }

    /**
     * Writes the unit heading from mass [root]'s centre to its goal into
     * [headX]/[headY]/[headZ], and a sideways axis across it into
     * [sideX]/[sideY]/[sideZ]. False when the mass has no goal to head for.
     */
    private fun clusterGoal(root: Int): Boolean {
        val volume = clusterVolume.get(root)
        if (volume <= 0.0 || !clusterHeading.contains(root)) return false
        val gx = clusterGoalX.get(root) - clusterX.get(root) / volume
        val gy = clusterGoalY.get(root) - clusterY.get(root) / volume
        val gz = clusterGoalZ.get(root) - clusterZ.get(root) / volume
        val length = sqrt(gx * gx + gy * gy + gz * gz)
        if (length < 1.0e-6) return false
        headX = gx / length; headY = gy / length; headZ = gz / length
        // Across the heading and level where possible; a vertical heading takes X.
        var ax = -headZ
        var az = headX
        var ay = 0.0
        var sideLength = sqrt(ax * ax + az * az)
        if (sideLength < 1.0e-3) {
            ax = 0.0; ay = headZ; az = -headY
            sideLength = sqrt(ay * ay + az * az)
        }
        sideX = ax / sideLength; sideY = ay / sideLength; sideZ = az / sideLength
        return true
    }

    /** How far ahead of its mass's centre a particle sits, toward the mass's goal. */
    private fun alongHeading(blob: IrreverantRemnantBlob, root: Int): Double {
        if (!clusterGoal(root)) return 0.0
        val volume = clusterVolume.get(root)
        return (blob.x - clusterX.get(root) / volume) * headX +
            (blob.y - clusterY.get(root) / volume) * headY +
            (blob.z - clusterZ.get(root) / volume) * headZ
    }

    /** This particle's speed now, as a multiple of the base steering rate: a
     *  fixed per-particle speed with a slight surge over it. Both come from
     *  the id, so they are free and survive a reload. The surge phase is
     *  offset per particle, so the mass never lurches in unison. */
    private fun pace(blob: IrreverantRemnantBlob, now: Long): Double {
        val speed = SPEED_MIN + hash01(blob.id) * (SPEED_MAX - SPEED_MIN)
        val phase = now.toDouble() / PULSE_TICKS + hash01(blob.id * 31 + 7)
        return speed * (1.0 + PULSE_DEPTH * Math.sin(phase * 2.0 * Math.PI))
    }

    /** Deterministic 0..1 from an id, by the golden-ratio multiplier — well
     *  spread for consecutive ids, which is exactly what blob ids are. */
    private fun hash01(value: Int): Double {
        val x = value * 0.6180339887498949
        return x - Math.floor(x)
    }


    /** The nearest non-spectator on [parallel] within [AGGRO_RANGE]. */
    private fun nearestPrey(
        players: List<ServerPlayer>,
        parallel: Int,
        x: Double,
        y: Double,
        z: Double,
    ): ServerPlayer? {
        var best: ServerPlayer? = null
        var bestSq = AGGRO_RANGE * AGGRO_RANGE
        for (p in players.indices) {
            if (playerParallels[p] != parallel) continue
            val player = players[p]
            if (player.isSpectator) continue
            val distSq = player.distanceToSqr(x, y, z)
            if (distSq >= bestSq) continue
            best = player
            bestSq = distSq
        }
        return best
    }

    /**
     * Finds the nearest other mass on the same parallel, writing its centre to the seek scratch.
     *
     * Small masses converge on each other rather than wandering apart, so
     * scattered drips gather into something that can act instead of dissolving
     * into the dimension one particle at a time.
     */
    private fun nearestOtherCluster(root: Int, parallel: Int, cx: Double, cy: Double, cz: Double): Boolean {
        var bestSq = MERGE_SEEK_RANGE * MERGE_SEEK_RANGE
        var found = false
        for (n in 0 until clusterRoots.size) {
            val other = clusterRoots.getInt(n)
            if (other == root || clusterParallel.get(other) != parallel) continue
            val volume = clusterVolume.get(other)
            if (volume <= 0.0) continue
            val ox = clusterX.get(other) / volume
            val oy = clusterY.get(other) / volume
            val oz = clusterZ.get(other) / volume
            val dx = ox - cx
            val dy = oy - cy
            val dz = oz - cz
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq >= bestSq) continue
            bestSq = distSq
            seekX = ox; seekY = oy; seekZ = oz
            found = true
        }
        return found
    }

    /**
     * Keeps or replaces a particle's parent.
     *
     * Hysteresis is the point. An existing parent is kept while it is still
     * in reach, even when something nearer turns up — link topology was
     * rebuilt from scratch every pass, so a drip's connection flicked between
     * neighbours as positions shifted by fractions of a block, and the
     * tendril snapped from one to another with it. The drop range is wider
     * than the adopt range so the link cannot chatter at the boundary.
     *
     * Candidates must sit above and be better supported than the particle
     * itself, which is what makes a clump of drips link back to the body
     * rather than to each other.
     */
    private fun adoptParent(
        blob: IrreverantRemnantBlob,
        candidate: IrreverantRemnantBlob?,
        candidateDist: Double,
    ) {
        if (blob.parentCooldown > 0) {
            blob.parentCooldown--
            return
        }
        var current = blob.parent
        if (current != null && current.evicted) {
            blob.parent = null
            current = null
        }
        if (current != null) {
            val dx = current.x - blob.x
            val dy = current.y - blob.y
            val dz = current.z - blob.z
            val distSq = dx * dx + dy * dy + dz * dz
            // A filament under sustained strain thins and parts. Without a
            // break the tension simply holds a drip at its hanging length
            // forever, and chains accumulate off the body instead of
            // shedding — impressive once, then permanent scenery.
            if (distSq > PARENT_SNAP * PARENT_SNAP) {
                if (++blob.stretchTicks >= SNAP_PATIENCE) {
                    blob.parent = null
                    blob.stretchTicks = 0
                    // Kept from reattaching immediately, or it grabs the same
                    // neighbour on the next tick and nothing has happened.
                    blob.parentCooldown = SNAP_COOLDOWN
                    return
                }
            } else {
                blob.stretchTicks = 0
            }
            if (distSq <= PARENT_DROP * PARENT_DROP) return
            blob.parent = null
        }
        if (candidate != null && candidateDist < PARENT_RANGE) blob.parent = candidate
    }

    /**
     * Tension along the parent link — a damped spring, past a rest length.
     *
     * The damping is not optional. An undamped spring at this stiffness rings
     * with a period near two seconds against nothing but the feeble airborne
     * drag, which shows as a run of particles dropping out of the body,
     * hanging, and being hauled back in on a metronome. Bleeding off the
     * velocity *along the link* settles the drip at its hanging length
     * instead, and leaves motion across the link alone so it can still swing
     * beneath its parent.
     *
     * The pull is capped, because a spring whose force grows without bound is
     * one that explodes.
     */
    private fun hangFrom(blob: IrreverantRemnantBlob, anchor: IrreverantRemnantBlob) {
        val dx = anchor.x - blob.x
        val dy = anchor.y - blob.y
        val dz = anchor.z - blob.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1.0e-6) return
        val ux = dx / dist
        val uy = dy / dist
        val uz = dz / dist
        val rest = (blob.radius + anchor.radius) * REST_FACTOR
        if (dist > rest) {
            // Weakens the longer the drip has been off a surface, so a long
            // fall progressively loses its hold rather than hanging at a
            // fixed length until the link happens to strain past breaking.
            val hold = IrreverantRemnantMotion.grip(blob)
            val pull = Math.min((dist - rest) * TENSION_ACCEL * hold, MAX_TENSION)
            blob.vx += ux * pull
            blob.vy += uy * pull
            blob.vz += uz * pull
        }
        // Radial velocity only — relative to the parent, so a whole mass
        // moving together is not braked by its own internal links.
        val closing = (blob.vx - anchor.vx) * ux +
            (blob.vy - anchor.vy) * uy +
            (blob.vz - anchor.vz) * uz
        blob.vx -= ux * closing * TENSION_DAMPING
        blob.vy -= uy * closing * TENSION_DAMPING
        blob.vz -= uz * closing * TENSION_DAMPING
    }

    /**
     * Pushes an over-crowded particle apart from its neighbours, as a
     * position correction rather than a force.
     *
     * As an impulse this did not work. Pairwise repulsion cancels inside a
     * uniform pack — only the boundary feels a net push — so a compressed
     * mass can expand only as a rarefaction wave travelling inward. Sliding
     * friction and viscosity between them strip over 60% of the velocity
     * every tick, which damps that wave to nothing, and the pack sits
     * over-dense indefinitely. Five spawns landing in one small volume then
     * produced a denser mass rather than a bigger one.
     *
     * Correcting position directly is immune to that: nothing damps it away,
     * so the pack relaxes to its rest spacing within a few ticks and volume
     * tracks particle count. This is the separation step of position-based
     * fluids, and the reason those solvers use projections instead of forces.
     *
     * Capped per tick so a badly overlapped spawn eases apart rather than
     * detonating.
     */
    private fun separate(blob: IrreverantRemnantBlob, dx: Double, dy: Double, dz: Double) {
        var sx = dx * SEPARATION_GAIN
        var sy = dy * SEPARATION_GAIN
        var sz = dz * SEPARATION_GAIN
        val length = Math.sqrt(sx * sx + sy * sy + sz * sz)
        if (length <= 1.0e-9) return
        if (length > MAX_SEPARATION) {
            val scale = MAX_SEPARATION / length
            sx *= scale; sy *= scale; sz *= scale
        }
        // Never into the surface it is resting on. This moves position
        // directly, so it bypasses collision entirely — a crowded particle
        // was being shoved bodily into the block behind it, and once far
        // enough in, contact resolution ejects it out the far side. Sliding
        // the correction along the surface spreads the mass without ever
        // pushing it through anything.
        if (blob.onSurface && !blob.seeping) {
            IrreverantRemnantMotion.projectOntoSurface(
                sx, sy, sz, blob.normalX, blob.normalY, blob.normalZ, tangent,
            )
            sx = tangent[0]; sy = tangent[1]; sz = tangent[2]
        }
        blob.x += sx
        blob.y += sy
        blob.z += sz
        // Woken, or it would sleep through its own crowding.
        blob.restTicks = 0
    }

    /**
     * Decides whether a whole mass has given up going around and should start
     * going through. Evaluated once per mass per pass, not per particle.
     *
     * Per particle it was wrong twice over. A particle deep in a crowd cannot
     * move because its neighbours are in the way — it is not stuck, it is
     * simply where the crowd is — so an interior particle registered failure
     * every check while the mass travelled perfectly well around it. And the
     * measure itself was distance to the goal, which is the current waypoint,
     * and that jumps forward each time one is consumed, so travelling well
     * looked like losing ground.
     *
     * The body's own displacement has neither problem: a mass that is
     * genuinely blocked does not move, and one that is flowing does,
     * regardless of what any individual particle is doing or where the
     * waypoint went.
     */
    private fun updateSeep(path: RemnantPath, cx: Double, cy: Double, cz: Double, now: Long) {
        if (path.evaluatedAt == now) return
        path.evaluatedAt = now

        if (path.seepTicks > 0) {
            path.seepTicks -= BEHAVIOR_INTERVAL.toInt()
            if (path.seepTicks > 0) return
            path.seeping = false
            path.stuckTicks = 0
            path.lastX = Double.NaN
            return
        }
        // Nothing to compare against on the first pass for a new mass.
        if (path.lastX.isNaN()) {
            path.lastX = cx; path.lastY = cy; path.lastZ = cz
            path.stuckTicks = 0
            return
        }
        val dx = cx - path.lastX
        val dy = cy - path.lastY
        val dz = cz - path.lastZ
        val moved = Math.sqrt(dx * dx + dy * dy + dz * dz)
        path.lastX = cx; path.lastY = cy; path.lastZ = cz

        val gx = path.targetX - cx
        val gy = path.targetY - cy
        val gz = path.targetZ - cz
        val arrived = gx * gx + gy * gy + gz * gz <= WAYPOINT_REACH * WAYPOINT_REACH
        if (arrived || moved >= STUCK_PROGRESS) {
            path.stuckTicks = 0
        } else {
            path.stuckTicks += BEHAVIOR_INTERVAL.toInt()
        }
        if (path.stuckTicks >= STUCK_PATIENCE) {
            path.seeping = true
            path.seepTicks = SEEP_DURATION
        }
    }

    /** A route is redone when it has run out, gone stale, or its target has
     *  moved far enough that following the old one wastes the trip. */
    private fun needsRepath(path: RemnantPath, now: Long, tx: Double, ty: Double, tz: Double): Boolean {
        // A search that found no route costs its full expansion budget. Without the hold, the
        // same search ran again on each pass for as long as the goal stayed out of reach.
        if (now < path.failedUntil) return false
        if (path.nodes.isEmpty() || path.exhausted) return true
        if (now - path.builtAt >= PATH_TTL) return true
        val dx = tx - path.targetX
        val dy = ty - path.targetY
        val dz = tz - path.targetZ
        return dx * dx + dy * dy + dz * dz > REPATH_DISTANCE * REPATH_DISTANCE
    }

    /** Runs one search for a whole cluster. Failure leaves the route empty,
     *  and the mass falls back to steering straight at the target — which is
     *  what it did before there was any pathing at all. */
    private fun repath(
        tickLevel: ServerLevel,
        path: RemnantPath,
        cx: Double, cy: Double, cz: Double,
        tx: Double, ty: Double, tz: Double,
    ) {
        pathStart.set(Math.floor(cx).toInt(), Math.floor(cy).toInt(), Math.floor(cz).toInt())
        pathGoal.set(Math.floor(tx).toInt(), Math.floor(ty).toInt(), Math.floor(tz).toInt())
        pathScratch.clear()
        val found = pathfinder.findPath(tickLevel, pathStart, pathGoal, pathScratch)
        path.nodes.clear()
        if (found) {
            pathfinder.straighten(tickLevel, pathScratch)
            path.nodes.addAll(pathScratch)
            path.failedUntil = Long.MIN_VALUE
        } else {
            path.failedUntil = tickLevel.gameTime + PATH_FAIL_HOLD
        }
        path.reset(tx, ty, tz, tickLevel.gameTime)
    }

    /** Consumes waypoints the mass has already reached. Bounded, so a route
     *  laid back through the mass cannot spin here. */
    private fun advance(path: RemnantPath, cx: Double, cy: Double, cz: Double) {
        var steps = 0
        while (!path.exhausted && steps++ < path.nodes.size) {
            val node = path.nodes.getLong(path.index)
            val dx = BlockPos.getX(node) + 0.5 - cx
            val dy = BlockPos.getY(node) + 0.5 - cy
            val dz = BlockPos.getZ(node) + 0.5 - cz
            if (dx * dx + dy * dy + dz * dz > WAYPOINT_REACH * WAYPOINT_REACH) return
            path.index++
        }
    }

    /** Connectivity test: are these two particles one mass? */
    private fun overlapping(a: IrreverantRemnantBlob, b: IrreverantRemnantBlob): Boolean {
        if (a.parallel != b.parallel) return false
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        val reach = (a.radius + b.radius) * CONNECT_REACH
        return dx * dx + dy * dy + dz * dz <= reach * reach
    }

    private fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        // Path compression, iterative.
        var walk = i
        while (parent[walk] != root) {
            val next = parent[walk]
            parent[walk] = root
            walk = next
        }
        return root
    }

    private fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[rb] = ra
    }

    // -------------------------------------------------------------- damage

    /** The cubes are the weapon: damage scales with how much of the
     *  player's hitbox they fill, so brushing the edge stings and being
     *  engulfed hurts. */
    private fun damagePlayers(tickLevel: ServerLevel, players: List<ServerPlayer>) {
        if (tickLevel.gameTime % DAMAGE_INTERVAL != 0L) return
        for (p in players.indices) {
            val player = players[p]
            if (player.isCreative || player.isSpectator) continue
            val parallel = playerParallels[p]
            if (parallel < 0) continue
            val box = player.boundingBox
            val overlap = cubeOverlap(box, parallel)
            if (overlap <= 0.0) continue
            // Overlapping cubes are summed twice where they overlap each other; the clamp absorbs that.
            val depth = (overlap / (box.xsize * box.ysize * box.zsize)).coerceIn(0.0, 1.0)
            val damage = DAMAGE_MIN + (DAMAGE_MAX - DAMAGE_MIN) * depth
            player.hurt(EKDamageSources.irreverantRemnant(tickLevel), damage.toFloat())
        }
    }

    /** Summed intersection volume of [box] with the cubes on [parallel], from
     *  the spatial hash. The query is padded so cubes that moved since the
     *  hash was built still count. */
    private fun cubeOverlap(box: AABB, parallel: Int): Double {
        val c0x = Math.floorDiv(Math.floor(box.minX - CUBE_QUERY_PAD).toInt(), CELL_SIZE)
        val c0y = Math.floorDiv(Math.floor(box.minY - CUBE_QUERY_PAD).toInt(), CELL_SIZE)
        val c0z = Math.floorDiv(Math.floor(box.minZ - CUBE_QUERY_PAD).toInt(), CELL_SIZE)
        val c1x = Math.floorDiv(Math.floor(box.maxX + CUBE_QUERY_PAD).toInt(), CELL_SIZE)
        val c1y = Math.floorDiv(Math.floor(box.maxY + CUBE_QUERY_PAD).toInt(), CELL_SIZE)
        val c1z = Math.floorDiv(Math.floor(box.maxZ + CUBE_QUERY_PAD).toInt(), CELL_SIZE)
        var sum = 0.0
        for (cx in c0x..c1x) for (cy in c0y..c1y) for (cz in c0z..c1z) {
            val cell = buckets.get(cellKeyAt(cx, cy, cz)) ?: continue
            for (blob in cell) {
                if (blob.parallel != parallel) continue
                val h = blob.radius * IrreverantRemnantMotion.CUBE_HALF_EXTENT
                val ox = Math.min(box.maxX, blob.x + h) - Math.max(box.minX, blob.x - h)
                if (ox <= 0.0) continue
                val oy = Math.min(box.maxY, blob.y + h) - Math.max(box.minY, blob.y - h)
                if (oy <= 0.0) continue
                val oz = Math.min(box.maxZ, blob.z + h) - Math.max(box.minZ, blob.z - h)
                if (oz <= 0.0) continue
                sum += ox * oy * oz
            }
        }
        return sum
    }

    /** Steering (server-only, needs goals) then the shared integrator. */
    private fun integrate(blob: IrreverantRemnantBlob, now: Long) {
        if (blob.spawnAge < IrreverantRemnantNetwork.SPAWN_TICKS) {
            // Still forming on its face: held where it seeped out.
            blob.spawnAge++
            blob.vx = 0.0
            blob.vy = 0.0
            blob.vz = 0.0
            return
        }
        steer(blob, now)
        IrreverantRemnantMotion.integrate(blob, surface)
    }

    /**
     * Accelerates a particle toward its goal, **along** whatever it is stuck
     * to. The desired direction is projected onto the surface's tangent
     * plane, so a mass on a wall climbs the wall and a mass on a slope runs
     * up the slope, instead of driving into the geometry and stalling.
     * Airborne particles steer freely.
     *
     * Server-only: the client has no goals and picks the result up through
     * synced velocity.
     */
    private fun steer(blob: IrreverantRemnantBlob, now: Long) {
        if (!blob.hasGoal) return
        var dx = blob.goalX - blob.x
        var dy = blob.goalY - blob.y
        var dz = blob.goalZ - blob.z
        val accel = (if (blob.hunting) HUNT_ACCEL else WANDER_ACCEL) * pace(blob, now) * blob.steerScale

        if (blob.onSurface) {
            // Follow the surface toward the goal rather than pressing into
            // it — this is the difference between crawling and shoving.
            val raw = dx * dx + dy * dy + dz * dz
            IrreverantRemnantMotion.projectOntoSurface(
                dx, dy, dz, blob.normalX, blob.normalY, blob.normalZ, tangent,
            )
            val flat = tangent[0] * tangent[0] + tangent[1] * tangent[1] + tangent[2] * tangent[2]
            // Unless the goal lies straight through the surface, in which
            // case projection cancels it to nothing and the mass sits still
            // on a route it never acts on — a target directly overhead being
            // the obvious case. Keep the raw direction there so it presses
            // outward, finds an edge, and starts climbing.
            if (flat > raw * TANGENT_COLLAPSE) {
                dx = tangent[0]
                dy = tangent[1]
                dz = tangent[2]
            }
        } else {
            // In free air, don't try to climb; just home in horizontally.
            dy = 0.0
        }

        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1.0e-6) return
        // Falling goo has almost no purchase on anything. Steering at full
        // authority in free air let a drip swim toward its goal, which reads
        // as intent where there should only be gravity and what it is still
        // attached to.
        val authority = if (blob.onSurface) 1.0 else AIRBORNE_STEER
        val scale = accel * authority / len
        blob.vx += dx * scale
        blob.vy += dy * scale
        blob.vz += dz * scale
    }

    /**
     * Particle interaction — repulsion, cohesion, and viscosity, between
     * particles on the same parallel only.
     *
     * Three forces per neighbour pair:
     *  - **Repulsion** below the rest distance. Without it, cohesion alone
     *    collapses every particle onto a single point. This is what holds the body open and distributed.
     *  - **Cohesion** beyond the rest distance, pulling toward the
     *    neighbourhood centre of mass, so the body holds together and
     *    stretches rather than scattering.
     *  - **Viscosity**, blending velocity toward the neighbourhood's, so the
     *    mass flows as one substance instead of as independent particles.
     *
     * Repulsion + cohesion around a rest spacing is the same balance that
     * makes a fluid behave like a fluid; the result is a body that spreads,
     * necks, and reforms.
     *
     * Neighbours come from the shared spatial hash, so this stays
     * near-linear rather than the O(n^2) every-pair comparison.
     */
    private fun applyParticleForces(blobs: List<IrreverantRemnantBlob>) {
        for (blob in blobs) {
            // A forming drip is not pushed or gathered; its neighbours still feel it.
            if (blob.spawnAge < IrreverantRemnantNetwork.SPAWN_TICKS) continue
            var pushX = 0.0
            var pushY = 0.0
            var pushZ = 0.0
            var cx = 0.0
            var cy = 0.0
            var cz = 0.0
            var packed = 0
            var sharing = 0
            var bestParent: IrreverantRemnantBlob? = null
            var bestParentDist = PARENT_RANGE
            var avx = 0.0
            var avy = 0.0
            var avz = 0.0
            var weight = 0.0
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val cell = buckets.get(cellKey(blob, dx, dy, dz)) ?: continue
                for (other in cell) {
                    if (other === blob || other.parallel != blob.parallel) continue
                    val ox = other.x - blob.x
                    val oy = other.y - blob.y
                    val oz = other.z - blob.z
                    val distSq = ox * ox + oy * oy + oz * oz
                    val reach = (blob.radius + other.radius) * COHESION_REACH
                    if (distSq > reach * reach || distSq < 1.0e-9) continue
                    val dist = sqrt(distSq)
                    val rest = (blob.radius + other.radius) * REST_FACTOR
                    if (dist < rest) {
                        // Raw overlap, not a force — resolved as a position
                        // correction below.
                        val overlap = rest - dist
                        pushX -= ox / dist * overlap
                        pushY -= oy / dist * overlap
                        pushZ -= oz / dist * overlap
                    }
                    if (dist < rest * PACKED_BAND) packed++
                    // A fixed reach, not one from the current radii: a reach that grows with a particle lets
                    // it count more neighbours, claim a bigger share, and grow without end.
                    if (dist < SHARE_REACH) {
                        sharing++
                        // Each pair once, by id.
                        if (other.id > blob.id) exchangeVolume(blob, other)
                    }
                    // Denser, or simply anchored to something. Requiring
                    // strictly greater density left a freshly separated drip
                    // with no eligible parent at all — and so no tendril —
                    // whenever its neighbours were no more crowded than it.
                    val supported = other.packedCount > blob.packedCount ||
                        (other.onSurface && !blob.onSurface)
                    if (other.y > blob.y + PARENT_RISE && dist < bestParentDist && supported) {
                        bestParentDist = dist
                        bestParent = other
                    }
                    val w = other.volume
                    cx += other.x * w
                    cy += other.y * w
                    cz += other.z * w
                    avx += other.vx * w
                    avy += other.vy * w
                    avz += other.vz * w
                    weight += w
                }
            }
            separate(blob, pushX, pushY, pushZ)
            blob.shareCount = sharing
            if (weight <= 0.0) continue
            // Cohesion toward the neighbourhood centre of mass — but only
            // ALONG a surface the particle is stuck to. For a particle at the
            // bottom of a mound that centre sits above it, and cohesion is
            // several times stronger than the adhesion and residual gravity
            // holding it down, so an unprojected pull lifts the whole mass
            // clear of the ground. Projecting gathers the body without ever
            // peeling it off.
            var toX = cx / weight - blob.x
            var toY = cy / weight - blob.y
            var toZ = cz / weight - blob.z
            if (blob.onSurface) {
                IrreverantRemnantMotion.projectOntoSurface(
                    toX, toY, toZ, blob.normalX, blob.normalY, blob.normalZ, tangent,
                )
                toX = tangent[0]; toY = tangent[1]; toZ = tangent[2]
            }
            blob.packedCount = packed
            adoptParent(blob, bestParent, bestParentDist)

            // A separating particle hangs from its parent instead of being
            // drawn toward the crowd's centre. Cohesion pulls at a centroid
            // that is up AND sideways, so a drip never settles beneath the
            // thing it came off — tendrils came out slanted where gravity
            // would hang them straight. Tension along the link behaves as a
            // pendulum: any sideways offset is unbalanced, so it swings until
            // the drip sits directly below its parent.
            val anchor = blob.parent
            if (!blob.onSurface && anchor != null) {
                hangFrom(blob, anchor)
                continue
            }
            // A runner is not held to the pack, or the pack's speed is its speed and it never overtakes.
            if (blob.running) continue
            // A drip whose link has just parted falls free. Cohesion and viscosity would otherwise hold it
            // near the mass for a second with no link drawn.
            if (!blob.onSurface && blob.parentCooldown > 0) continue

            // Cohesion fades out as the neighbourhood fills.
            //
            // Without this it has no rest length: an interior particle with a
            // full set of neighbours is pulled toward the centre exactly as
            // hard as a lone one, and only short-range repulsion pushes back.
            // The pack settles compressed, and it compresses further the more
            // particles it has — so a mass that gains particles gets denser
            // rather than bigger, which is why it never looked like it grew.
            //
            // Fading it leaves surface tension intact, since a surface
            // particle is by definition short of neighbours and still gets
            // pulled in, while the interior is left at the spacing repulsion
            // wants. Volume then tracks particle count.
            // Airborne particles are gathered less the longer they have been
            // falling, for the same reason.
            val gather = (1.0 - packed.toDouble() / PACKED_NEIGHBOURS).coerceAtLeast(0.0) *
                if (blob.onSurface) 1.0 else IrreverantRemnantMotion.grip(blob)
            if (gather > 0.0) {
                val pull = COHESION_ACCEL * gather
                blob.vx += toX * pull
                blob.vy += toY * pull
                blob.vz += toZ * pull
            }
            blob.vx += (avx / weight - blob.vx) * VISCOSITY
            blob.vy += (avy / weight - blob.vy) * VISCOSITY
            blob.vz += (avz / weight - blob.vz) * VISCOSITY
        }
        applySharedVolume(blobs)
    }

    /**
     * Moves volume between two touching particles toward the split their
     * packing asks for: a well-surrounded particle claims a larger share than
     * one at the edge. Equal and opposite, so the pair's total is unchanged.
     * Uses last pass's counts, which both particles have by now.
     */
    private fun exchangeVolume(a: IrreverantRemnantBlob, b: IrreverantRemnantBlob) {
        val wa = shareWeight(a.shareCount)
        val wb = shareWeight(b.shareCount)
        val va = a.volume
        val total = va + b.volume
        val flow = (total * wa / (wa + wb) - va) * SHARE_RATE
        a.shareDelta += flow
        b.shareDelta -= flow
    }

    /** A power of the neighbour count, so the split depends on how two particles' counts compare, not on their
     *  size. */
    private fun shareWeight(neighbours: Int): Double = Math.pow(1.0 + neighbours, SHARE_POWER)

    /** Applies the volume exchanged this pass. The radius bounds are a safety
     *  net for physics and the sync byte; inside a mass the weights keep
     *  shares well within them. */
    private fun applySharedVolume(blobs: List<IrreverantRemnantBlob>) {
        for (blob in blobs) {
            val delta = blob.shareDelta
            if (delta == 0.0) continue
            blob.shareDelta = 0.0
            val volume = (blob.volume + delta).coerceAtLeast(MIN_SHARED_RADIUS * MIN_SHARED_RADIUS * MIN_SHARED_RADIUS)
            blob.radius = Math.cbrt(volume).coerceIn(MIN_SHARED_RADIUS, MAX_SHARED_RADIUS)
        }
    }

    /** Bounds enclosing the simulated particles, padded by the furthest a
     *  probe reaches, so an entity just outside still counts as surface. */
    private fun particleBounds(blobs: List<IrreverantRemnantBlob>): AABB {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (blob in blobs) {
            if (blob.x < minX) minX = blob.x
            if (blob.y < minY) minY = blob.y
            if (blob.z < minZ) minZ = blob.z
            if (blob.x > maxX) maxX = blob.x
            if (blob.y > maxY) maxY = blob.y
            if (blob.z > maxZ) maxZ = blob.z
        }
        return AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(ENTITY_SURFACE_PADDING)
    }

    /** Rebuilds the spatial hash shared by the force pass, clustering, and
     *  field queries — built once per tick rather than per consumer. */
    private fun buildHash(blobs: List<IrreverantRemnantBlob>) {
        buckets.clear()
        for (blob in blobs) {
            buckets.computeIfAbsent(cellKey(blob)) { ArrayList(4) }.add(blob)
        }
    }

    private fun cellKey(blob: IrreverantRemnantBlob, dx: Int = 0, dy: Int = 0, dz: Int = 0): Long =
        cellKeyAt(
            Math.floorDiv(Math.floor(blob.x).toInt(), CELL_SIZE) + dx,
            Math.floorDiv(Math.floor(blob.y).toInt(), CELL_SIZE) + dy,
            Math.floorDiv(Math.floor(blob.z).toInt(), CELL_SIZE) + dz,
        )

    private fun cellKeyAt(cx: Int, cy: Int, cz: Int): Long =
        (cx.toLong() and 0x1FFFFF shl 42) or
            (cy.toLong() and 0x1FFFFF shl 21) or
            (cz.toLong() and 0x1FFFFF)

    // ----------------------------------------------------------- visuals

    // ------------------------------------------------------------- queries

    /** Live blob count — the pseudo-entity's "size" in the crudest sense. */
    fun blobCount(): Int = data?.blobs?.size ?: 0

    /** Particle radius is uniform now that nothing consolidates, so a
     *  remnant's size is its particle count and total volume — see
     *  [blobCount] and [totalVolume]. Hunting keys off cluster volume in
     *  [assignGoals]. */
    fun largestRadius(): Double = data?.blobs?.maxOfOrNull { it.radius } ?: 0.0

    /** Total volume of remnant present in the dimension. */
    fun totalVolume(): Double = data?.blobs?.sumOf { it.volume } ?: 0.0

    // ------------------------------------------------------------- tuning

    /** Radius around a player on its parallel within which a particle
     *  exists; past it the particle is removed. Wider than the sync range, so
     *  a mass does not vanish while it is in view. */
    private const val SIMULATION_RANGE_SQ = 96.0 * 96.0

    /** Hard ceiling on simulated blobs (CLAUDE.md rule 2). Merging keeps the
     *  real count far below this in practice. */
    private const val MAX_BLOBS = 768

    /**
     * Odds a random-ticked player edit seeps, on top of the tracker's own
     * vanilla random-tick rate.
     *
     * Every tracked edit near a witness is a potential source, so this
     * multiplies by how much a player has built: about a hundred edits give
     * a drip every 5 to 6 seconds. A few thousand fill the pool in minutes,
     * as 0.35 once did; the cap and the removal of unwatched particles bound
     * that cost.
     */
    private const val DRIP_CHANCE = 0.15f

    /** How near an edit a player on another parallel must be for it to seep. */
    private const val DRIP_WITNESS_RANGE = 48.0

    /** Nominal particle radius; its cube is [IrreverantRemnantMotion.CUBE_HALF_EXTENT] times this each way. */
    private const val DRIP_RADIUS = 0.30

    /** Range of per-particle size, as a fraction of the nominal radius. */
    private const val RADIUS_MIN = 0.80
    private const val RADIUS_MAX = 1.34

    /** Spread of a drip's birthplace across the face it seeps from. */
    private const val FACE_JITTER = 0.5

    /** Ticks between one spawn-egg particle starting to form and the next. With the egg's count this must
     *  keep the first delay above -128, because the age travels as a signed byte. */
    private const val CLUSTER_STAGGER = 4

    /** Faces probed for somewhere to seep into, underside first. */
    private val DRIP_FACES = arrayOf(
        Direction.DOWN, Direction.NORTH, Direction.SOUTH,
        Direction.EAST, Direction.WEST, Direction.UP,
    )



    /** How far apart particles still feel each other, as a multiple of
     *  their combined radii. */
    private const val COHESION_REACH = 3.0

    /** Spacing particles settle at, as a multiple of their combined radii.
     *  Under the cube side, so neighbouring cubes overlap into one cluster. */
    private const val REST_FACTOR = 0.85

    /** Short-range push that holds the rest spacing. Must out-muscle
     *  cohesion at close range or the mass collapses into a single ball —
     *  and it is what gives the body volume, stacking particles into a
     *  mound instead of letting them settle into one flat layer. */
    /** Fraction of the measured overlap resolved per tick, and the ceiling
     *  on how far one particle may be moved by it. */
    private const val SEPARATION_GAIN = 0.45
    private const val MAX_SEPARATION = 0.12

    /**
     * Pull toward the neighbourhood centre of mass, per tick.
     *
     * Deliberately weak. Strong cohesion drags every particle along with the
     * leading edge, so the mass crosses ground as one rigid lump — no
     * stretching, no residue, no trail. Weak cohesion lets stuck particles
     * stay stuck while the rest flows on, which is what leaves an oozing
     * tail behind a moving body.
     */
    private const val COHESION_ACCEL = 0.022

    /** Neighbour count at which a particle counts as fully surrounded and
     *  stops being gathered inward, and how far out neighbours are counted
     *  toward it. Twelve is close packing; a particle with that many is
     *  interior and has nothing to be pulled toward. */
    private const val PACKED_NEIGHBOURS = 12.0
    private const val PACKED_BAND = 1.15

    /**
     * Volume sharing: the fraction of a pair's imbalance moved per tick, kept
     * low so a particle's many exchanges cannot overshoot; the reach within
     * which particles share, which is the packing band at nominal size; and
     * the radius bounds.
     */
    private const val SHARE_RATE = 0.02

    /** Contrast between middle and edge. At 4, middle particles settle at about 1.7-2.1x an edge particle's
     *  radius; at 2 it read as too weak. */
    private const val SHARE_POWER = 4.0
    private const val SHARE_REACH = 2.0 * DRIP_RADIUS * REST_FACTOR * PACKED_BAND
    private const val MIN_SHARED_RADIUS = 0.1
    private const val MAX_SHARED_RADIUS = 0.6

    /** Parent selection and the tension that hangs from it. A candidate must
     *  sit at least [PARENT_RISE] above and within [PARENT_RANGE]; an
     *  existing one is only released past the wider [PARENT_DROP]. Tension is
     *  sized against airborne gravity (0.018) so a single link settles about
     *  a block below its parent. */
    private const val PARENT_RISE = 0.05
    private const val PARENT_RANGE = 1.6
    private const val PARENT_DROP = 2.6

    /** Separation at which a link counts as straining, how long it may
     *  strain before it parts, and how long the particle then falls free
     *  before it may attach to anything again. */
    private const val PARENT_SNAP = 1.2
    private const val SNAP_PATIENCE = 12
    private const val SNAP_COOLDOWN = 50
    private const val TENSION_ACCEL = 0.026
    private const val MAX_TENSION = 0.06

    /** Radial velocity bled off per tick along a parent link. Near critical
     *  for this stiffness (2*sqrt(k)), so a drip reaches its hanging length
     *  and stays there rather than oscillating about it. */
    private const val TENSION_DAMPING = 0.32

    /** Fraction of the neighbourhood velocity adopted per tick — the mass
     *  moves as one instead of blobs rolling independently. */
    private const val VISCOSITY = 0.30

    /** Reach at which two particles count as one mass, as a multiple of
     *  their combined radii. Carried over from the metaball field's
     *  influence, so masses group and reach [HUNT_PARTICLES] as before. */
    private const val CONNECT_REACH = 2.4

    /** Padding on a damage query, past the largest cube half-extent plus a tick of motion. */
    private const val CUBE_QUERY_PAD = 1.0

    /** Spatial-hash cell edge, in blocks. Must exceed the largest merge
     *  reach so a pair can never straddle more cells than are checked. */
    private const val CELL_SIZE = 4

    /** Ticks between client syncs. Every tick: client prediction lacks the
     *  server's steering and cohesion, so friction bleeds its speed away
     *  between syncs, and any gap shows as a speed sawtooth. */
    private const val SYNC_INTERVAL = 1L

    // --- sentience ---

    /** Ticks between goal re-evaluations. Steering runs every tick from the
     *  cached goal, so this only paces the expensive clustering + search. */
    private const val BEHAVIOR_INTERVAL = 10L

    /** Combined volume a connected mass needs before it hunts rather than
     *  wanders — a lone drip is harmless, an accumulation is not. */
    /** Fraction of its steering a particle keeps once off a surface. */
    private const val AIRBORNE_STEER = 0.2

    /** How far past the mass an entity is still gathered as surface. */
    private const val ENTITY_SURFACE_PADDING = 2.0

    /** Particles a mass needs before it will go after a player. Below this
     *  it seeks other remnant instead, out to [MERGE_SEEK_RANGE]. */
    /** Per-particle speed spread, and the depth and period of the slight
     *  surge over it. Kept shallow: at 0.6 it read as stutter. */
    private const val SPEED_MIN = 0.65
    private const val SPEED_MAX = 1.45
    private const val PULSE_DEPTH = 0.15
    private const val PULSE_TICKS = 20.0

    /** Leapfrog relay: the share of a mass running at once, the smallest mass
     *  that relays, a runner's steering boost and the rest's easing, how far
     *  past the front and out to the side a runner aims, and how long it may
     *  run before giving up. 3 runners at 3x with the rest at 0.7x kept a
     *  14-particle mass overtaking about once every 3 seconds at full speed. */
    private const val RUNNER_FRACTION = 0.2
    private const val RELAY_MIN_PARTICLES = 4
    private const val RUN_BOOST = 3.0
    private const val FOLLOWER_PACE = 0.7
    private const val PASS_LEAD = 0.3
    private const val PASS_OFFSET = 0.6
    private const val RUN_TIMEOUT = 100



    private const val HUNT_PARTICLES = 10
    private const val MERGE_SEEK_RANGE = 32.0

    /** How far a hunting mass can sense a player. */
    private const val AGGRO_RANGE = 24.0

    /** Wander target spread, and how close counts as "arrived" (which is
     *  when a new target is rolled). */
    private const val WANDER_RANGE = 8.0
    private const val WANDER_ARRIVE = 1.5

    /** How near the centroid must come before a waypoint is consumed, how
     *  far a target may drift before the route is redone, how long a route
     *  survives regardless, and the cap on searches per behaviour pass —
     *  one A* per mass, and only a couple of masses per pass. */
    /** Fraction of the goal direction that must survive projection onto the
     *  surface before the projected version is used at all. */
    private const val TANGENT_COLLAPSE = 0.04

    /** How much closer a particle must get between checks to count as
     *  making progress, how long it may fail to before it starts seeping,
     *  and how long a spell of seeping lasts. */
    private const val STUCK_PROGRESS = 0.10
    private const val STUCK_PATIENCE = 60
    private const val SEEP_DURATION = 40

    private const val WAYPOINT_REACH = 1.6
    private const val REPATH_DISTANCE = 3.0
    private const val PATH_TTL = 60L

    /** Ticks with no new search after one that found no route. */
    private const val PATH_FAIL_HOLD = 100L
    private const val MAX_PATHS_PER_TICK = 2
    private const val NO_ROOT = -1

    private const val WANDER_ACCEL = 0.004
    private const val HUNT_ACCEL = 0.012

    // --- damage ---

    /** Ticks between contact-damage checks. */
    private const val DAMAGE_INTERVAL = 10L

    /** Damage per check, scaled by how deep inside the surface the player
     *  is — brushing the edge stings, being engulfed hurts. */
    private const val DAMAGE_MIN = 1.0
    private const val DAMAGE_MAX = 4.0
}
