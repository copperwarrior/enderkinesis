package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.AABB
import org.joml.Vector3f
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.tags.BlockTags

/**
 * Permanent Wohlonnogondonia portal points, managed independently
 * of any block at the position.
 *
 * Architecture:
 *  - A position-keyed [PortalSavedData] (one instance per dimension)
 *    stores the set of portal anchor positions. Persists via
 *    vanilla's `DimensionDataStorage` — saved into the level's
 *    `data/` folder and reloaded on world load.
 *  - The position itself is the portal. There is no block-entity,
 *    no special block requirement at the spot. The heart candle
 *    that triggered the ritual stays as a regular candle; the
 *    player can break / extinguish / replace it without affecting
 *    the portal point. Breaking the candle just removes the
 *    visual anchor.
 *  - One server tick listener walks each dimension's portal set
 *    every tick. Each tick: broadcast a few particles per portal
 *    via `level.sendParticles`. Every [TELEPORT_INTERVAL] ticks:
 *    scan entities inside a small AABB around each portal and
 *    teleport them to Wohlon via
 *    [WohlonnogondoniaPortalRitual.teleportToMotherTree].
 *
 * Thread safety: everything runs on the server thread. No locks
 * needed. Concurrent modifications happen only when [addPortal] is
 * called from the ritual hook (also on the server thread).
 */
object WohlonnogondoniaPortalManager {

    private val LOG = LogUtils.getLogger()

    /** SavedData key. Becomes the filename in the dimension's
     *  `data/` folder. */
    private const val DATA_NAME = "enderkinesis_wohlon_portals"

    /** SavedData key for the per-player entry tracker — kept on
     *  the overworld level so there's one canonical store across
     *  all dimensions. Records "which outbound portal each player
     *  last used", so the Mother Tree heart return-portal can
     *  send them back to it. */
    private const val ENTRY_DATA_NAME = "enderkinesis_wohlon_entries"

    /** Position of the Mother Tree's heart return-portal in Wohlon.
     *  Coincides with the Mother Heart of the Wild that
     *  `WohlonnogondoniaChunkGenerator` places at
     *  `(0, HEART_Y = SEA_LEVEL_Y + 4 = 68, 0)` — every "the heart"
     *  reference in the codebase points at this cell. Hardcoded
     *  singleton rather than registered in [PortalSavedData] —
     *  it's a deterministic dimension fixture, not something the
     *  player builds. */
    private val HEART_RETURN_PORTAL_POS: BlockPos = BlockPos(0, 68, 0)

    /** How often to scan entities for teleport. 2 ticks = 10 Hz —
     *  a player walking through the 1-voxel portal trigger needs
     *  to be caught within their dwell time (~1–2 ticks at normal
     *  walking speed), so a 10-tick poll would miss them. The
     *  per-portal AABB query is cheap (one `getEntities` call into
     *  the chunk's entity index) so polling at 10 Hz costs almost
     *  nothing in practice. Particles tick every server tick
     *  regardless of this interval. */
    private const val TELEPORT_INTERVAL: Long = 2L

    /** Half-extent (in blocks) of the entity-detection AABB around
     *  each ritual portal. 0.7 reaches just past the candle's adjacent
     *  voxels — players walking onto or right next to the portal
     *  trigger; players walking past at distance don't. */
    private const val TELEPORT_RADIUS: Double = 0.7

    /** Larger half-extent for the Mother Tree heart's return portal.
     *  The Mother heart is rendered at 2× scale and its visual
     *  silhouette extends ~1.13 blocks above and ~0.1 blocks beyond
     *  the host cell's 1×1×1 footprint. Bumping the AABB to 1.5
     *  blocks means an entity nudging *any* part of that visual —
     *  the aorta poking up into the cell above, the chambers' very
     *  slight overhang on each side — still triggers return,
     *  matching the player's intuition that "touching the heart"
     *  sends them home rather than "standing in this exact 1×1
     *  cell". */
    private const val MOTHER_TELEPORT_RADIUS: Double = 1.5

    /** Number of converging-sphere particles emitted per portal
     *  per server tick. Bumped from 2 → 10 for a denser halo. */
    private const val PARTICLES_PER_TICK: Int = 10

    /** Number of outward-up plume particles emitted per portal
     *  per server tick. Continuous (every tick) at this density
     *  rather than the previous 1-in-N gate. */
    private const val OUTWARD_PER_TICK: Int = 3

    /** Radius of the sphere where converging particles spawn (in
     *  blocks). Set just outside the ritual portal's teleport AABB
     *  (0.7) so the visible halo sits flush with the trigger
     *  volume. */
    private const val SPHERE_RADIUS: Double = 0.9

    /** Inward velocity applied to converging particles. ~22 ticks
     *  to cross [SPHERE_RADIUS], so the arc is visible. */
    private const val INWARD_SPEED: Double = 0.04

    /** Mother-portal halo scaling factor. Multiplies [SPHERE_RADIUS],
     *  [INWARD_SPEED], [PARTICLES_PER_TICK], and [OUTWARD_PER_TICK]
     *  so the halo around the Mother heart matches its enlarged
     *  [MOTHER_TELEPORT_RADIUS] trigger volume (and the 2× heart's
     *  visual presence) rather than reading as a tiny dot at the
     *  centre of a big AABB. Inward speed scales with the sphere
     *  so the cross-time (22 ticks) stays constant — the arc reads
     *  identically on both portal sizes. Particle counts scale
     *  *linearly* (not by area) so the density falls off slightly
     *  on the bigger halo; full area-scaling at this size produced
     *  a particle cloud dense enough to obscure the heart. */
    private const val MOTHER_PARTICLE_SCALE: Double =
        MOTHER_TELEPORT_RADIUS / TELEPORT_RADIUS

    /** Horizontal drift speed of the outward-up plume. */
    private const val OUTWARD_HORIZ_SPEED: Double = 0.05

    /** Vertical lift speed of the outward-up plume. */
    private const val OUTWARD_UP_SPEED: Double = 0.18

    /** Y of the visual convergence point inside the portal's
     *  voxel, in block-space. Centred (0.5) — the portal voxel
     *  is a clear-air slot 2 blocks above the heart candle and
     *  the converging-sphere effect reads cleanest when it
     *  collapses to the geometric centre of that voxel rather
     *  than tracking the candle's flame down below. */
    private const val PORTAL_Y_OFFSET: Double = 0.5

    /** Particle palette for the portal halo + plume. One picked
     *  per particle so the cloud reads as multicoloured. */
    private val COLOURS: List<Vector3f> = listOf(
        Vector3f(0.50f, 0.80f, 1.00f),  // light blue
        Vector3f(0.10f, 0.70f, 0.70f),  // teal
        Vector3f(0.00f, 1.00f, 1.00f),  // cyan
    )

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
        // Per-session caches — wiped on integrated-server stop so the
        // next world loaded into the same JVM doesn't inherit them.
        dev.architectury.event.events.common.LifecycleEvent.SERVER_STOPPED.register { _ ->
            pendingSearches.clear()
            returnCooldowns.clear()
        }
    }

    // ----- Async landing-spot search (one per portal) ---------------

    /** Max wall-clock attempts a single portal's landing search will
     *  consume before giving up and leaving the live fallback in
     *  place. 5 attempts/tick × 600 = ~30 s of search budget per
     *  portal — plenty for the wider 35–65 ring around origin. */
    private const val SEARCH_MAX_ATTEMPTS: Int = 600
    /** How many candidate columns each pending portal probes per
     *  Wohlon-dim tick. Small so a backlog of portals doesn't stall
     *  the tick; the per-portal budget recovers across ticks. */
    private const val SEARCH_ATTEMPTS_PER_TICK: Int = 5

    private data class PendingPortalSearch(
        val sourceDim: ResourceKey<Level>,
        val portalPos: BlockPos,
        var attemptsLeft: Int,
    )

    /** All portals currently waiting for an async landing-spot search
     *  to complete. Lives in-memory only — landings that finish are
     *  persisted on the source dim's [PortalSavedData]; pending
     *  searches restart from scratch on world load (re-enqueued
     *  there for portals without stored landings). */
    private val pendingSearches: MutableList<PendingPortalSearch> = mutableListOf()

    // ----- Return-portal cooldown (anti-bounce) ---------------------

    /** Ticks the heart-return teleport blocks the source-side portal
     *  from re-firing on the same entity. Player lands at the portal
     *  pos by design (return records store the portal as the entry
     *  pos), so without this they'd be in the AABB next tick and
     *  bounce back to Wohlon immediately. */
    private const val RETURN_COOLDOWN_TICKS: Long = 100L

    /** Entity UUID → server gameTime at which the heart return teleport
     *  fired. Entries older than [RETURN_COOLDOWN_TICKS] are pruned
     *  during [tickLevel]. */
    private val returnCooldowns: ConcurrentHashMap<UUID, Long> = ConcurrentHashMap()

    /** Public hook for [WohlonnogondoniaPortalRitual.teleportFromHeart]
     *  to mark an entity as just-returned, so the source-side portal's
     *  next [scanAndTeleport] doesn't immediately ship it back to Wohlon. */
    @JvmStatic
    fun noteReturnedEntity(entityId: UUID, gameTime: Long) {
        returnCooldowns[entityId] = gameTime
    }

    /** Hook for [WohlonnogondoniaCatastrophe] — called at the end of
     *  the live dim wipe so the next portal entry doesn't pick up a
     *  pre-wipe landing or a stuck cooldown. Per-dim
     *  [PortalSavedData] for the Wohlon dim itself is deleted with
     *  the dimension directory; the pending-search queue and the
     *  cooldown map are in-memory and need explicit clearing here. */
    @JvmStatic
    fun resetSessionStateForWipe() {
        synchronized(pendingSearches) {
            pendingSearches.clear()
        }
        returnCooldowns.clear()
    }

    /** Persist a portal anchor at [pos] in [level]. Idempotent —
     *  adding the same position twice is a no-op. Newly-registered
     *  portals also enqueue an async landing-spot search that runs
     *  in the Wohlon dim's tick loop until a valid 3×3×3-clear spot
     *  is found. The result is persisted on this dim's
     *  [PortalSavedData.landings] and consumed by
     *  [WohlonnogondoniaPortalRitual.teleportToMotherTree]. */
    /** Register a portal anchor at [pos] in [level]. Returns `true`
     *  if the position was newly added, `false` if it was already
     *  registered. The boolean lets a caller — most notably
     *  [HeartCandleBlock.onPlace] — skip the costly idempotent
     *  follow-up work (biome seed, tree grow) when the player
     *  re-lights a candle that already anchors a portal here. */
    @JvmStatic
    fun addPortal(level: ServerLevel, pos: BlockPos): Boolean {
        val data = getData(level)
        if (!data.positions.add(pos.asLong())) return false
        data.setDirty()
        LOG.debug("WohlonPortal: registered anchor at {} in {}", pos, level.dimension().location())
        enqueueLandingSearch(level.dimension(), pos)
        return true
    }

    /** Look up the pre-computed landing for [portalPos] in [sourceLevel].
     *  Returns null if the search hasn't finished, the portal is
     *  unregistered, or the data isn't loaded. */
    @JvmStatic
    fun getStoredLanding(sourceLevel: ServerLevel, portalPos: BlockPos): BlockPos? {
        val data = sourceLevel.dataStorage.get(
            { tag -> PortalSavedData.load(tag) }, DATA_NAME,
        ) ?: return null
        val packed = data.landings[portalPos.asLong()] ?: return null
        return BlockPos.of(packed)
    }

    private fun enqueueLandingSearch(sourceDim: ResourceKey<Level>, portalPos: BlockPos) {
        synchronized(pendingSearches) {
            // Dedupe — same portal queued twice would only burn extra
            // search budget; the first to finish wins via the dedup
            // in landings map.
            for (s in pendingSearches) {
                if (s.sourceDim == sourceDim && s.portalPos == portalPos) return
            }
            pendingSearches.add(PendingPortalSearch(sourceDim, portalPos.immutable(), SEARCH_MAX_ATTEMPTS))
        }
    }

    /** XZ radius around a registered portal that callers (currently
     *  the world-root grower) treat as "inside the ritual tree" —
     *  no world-root paint here so the chunkgen-style root network
     *  doesn't intersect canopy / trunk / buttress voxels. Covers
     *  the maximum `canopyRx` (~55 × 0.9 ≈ 50) plus a small margin. */
    private const val TREE_EXCLUSION_RADIUS_XZ = 60
    /** Y-range below the portal anchor inside the exclusion zone. Roots
     *  emerge ~5–15 below the candle, so 15 covers them comfortably. */
    private const val TREE_EXCLUSION_Y_BELOW = 15
    /** Y-range above the portal anchor inside the exclusion zone.
     *  Canopy top at full scale lands ~60–80 above the candle. */
    private const val TREE_EXCLUSION_Y_ABOVE = 90

    /** True iff `pos` falls inside *any* registered portal's
     *  tree-exclusion zone in [level]. Cheap — O(portals) with a
     *  squared-XZ-distance comparison and a Y-range check; no
     *  storage allocation. The exclusion zone is a vertical cylinder
     *  centred on the portal anchor. */
    @JvmStatic
    fun isInsideAnyTreeBounds(level: ServerLevel, pos: BlockPos): Boolean {
        val data = level.dataStorage.get(
            { tag -> PortalSavedData.load(tag) }, DATA_NAME,
        ) ?: return false
        if (data.positions.isEmpty()) return false
        val rXZsq = TREE_EXCLUSION_RADIUS_XZ * TREE_EXCLUSION_RADIUS_XZ
        for (packed in data.positions) {
            val portalX = BlockPos.getX(packed)
            val portalY = BlockPos.getY(packed)
            val portalZ = BlockPos.getZ(packed)
            if (pos.y < portalY - TREE_EXCLUSION_Y_BELOW) continue
            if (pos.y > portalY + TREE_EXCLUSION_Y_ABOVE) continue
            val dx = pos.x - portalX
            val dz = pos.z - portalZ
            if (dx * dx + dz * dz <= rXZsq) return true
        }
        return false
    }

    /** Stash a player's outbound-portal usage so the heart return
     *  portal can send them back to the *same* portal they came in
     *  at. Replaces any prior entry — most recent outbound use
     *  wins, which is what players expect ("I came in over there
     *  most recently, send me back there"). Tracker lives on the
     *  overworld dimension's data storage; one canonical store
     *  regardless of where this gets called from. */
    @JvmStatic
    fun recordPlayerEntry(
        server: MinecraftServer,
        playerId: UUID,
        sourceDim: ResourceKey<Level>,
        sourcePos: BlockPos,
    ) {
        val tracker = getEntryTracker(server)
        tracker.entries[playerId] = EntryRecord(sourceDim, sourcePos.immutable())
        tracker.setDirty()
    }

    /** Read the player's last outbound-portal usage. `null` if the
     *  player got into Wohlon outside the normal portal path —
     *  admin `/tp`, `/execute … run tp`, datapack scripts, etc.
     *  Caller is expected to fall back to the random-portal path
     *  in that case (still gets the player out of Wohlon). */
    @JvmStatic
    fun getPlayerEntry(server: MinecraftServer, playerId: UUID): EntryRecord? {
        return getEntryTracker(server).entries[playerId]
    }

    /** Pick one outbound portal at random from any non-Wohlon
     *  dimension currently loaded. Used for the
     *  random-other-portal fallback in
     *  [WohlonnogondoniaPortalRitual.teleportFromHeart] — mobs
     *  and item entities that step into the heart get sent
     *  somewhere, anywhere, that isn't another corner of Wohlon.
     *  Returns null if no outbound portals exist yet. */
    @JvmStatic
    fun pickRandomNonWohlonPortal(
        server: MinecraftServer,
        random: RandomSource,
    ): Pair<ServerLevel, BlockPos>? {
        val candidates = ArrayList<Pair<ServerLevel, BlockPos>>()
        for (dimLevel in server.allLevels) {
            if (dimLevel.dimension() == Wohlonnogondonia.LEVEL_KEY) continue
            val data = getData(dimLevel)
            for (packed in data.positions) {
                candidates.add(dimLevel to BlockPos.of(packed))
            }
        }
        if (candidates.isEmpty()) return null
        return candidates[random.nextInt(candidates.size)]
    }

    /** Get-or-create the per-dimension data slot. Vanilla
     *  `computeIfAbsent` handles both first-load (constructor) and
     *  re-load (NBT deserialisation) cases. */
    private fun getData(level: ServerLevel): PortalSavedData {
        return level.dataStorage.computeIfAbsent(
            { tag -> PortalSavedData.load(tag) },
            { PortalSavedData() },
            DATA_NAME,
        )
    }

    /** Get-or-create the per-player entry tracker on the overworld
     *  level. One canonical store, no per-dimension duplication. */
    private fun getEntryTracker(server: MinecraftServer): EntryTracker {
        return server.overworld().dataStorage.computeIfAbsent(
            { tag -> EntryTracker.load(tag) },
            { EntryTracker() },
            ENTRY_DATA_NAME,
        )
    }

    private fun tickLevel(level: ServerLevel) {
        val gameTime = level.gameTime
        val doTeleport = gameTime % TELEPORT_INTERVAL == 0L

        val data = getData(level)
        // Re-enqueue searches for any saved portal that doesn't yet
        // have a stored landing (loaded from a pre-feature save, or
        // shutdown mid-search). Cheap O(positions) check per tick —
        // no-op once every portal has a landing or is in the pending
        // queue. Skips the Wohlon dim — portals only live in
        // non-Wohlon dims.
        if (level.dimension() != Wohlonnogondonia.LEVEL_KEY) {
            for (packed in data.positions) {
                if (!data.landings.containsKey(packed)) {
                    enqueueLandingSearch(level.dimension(), BlockPos.of(packed))
                }
            }
        }
        for (packed in data.positions) {
            val pos = BlockPos.of(packed)
            emitParticles(level, pos)
            if (doTeleport) scanAndTeleport(level, pos)
        }

        // The Mother Tree heart return-portal is processed only
        // inside Wohlon and only here — it isn't stored in
        // `PortalSavedData` since it's deterministic per
        // dimension, not built by the player.
        if (level.dimension() == Wohlonnogondonia.LEVEL_KEY) {
            emitParticles(level, HEART_RETURN_PORTAL_POS, MOTHER_PARTICLE_SCALE)
            if (doTeleport) scanAndTeleportHeart(level, HEART_RETURN_PORTAL_POS)
            // Pending-portal landing searches run in the Wohlon dim
            // because that's the dim being scanned. Each pending
            // portal gets up to [SEARCH_ATTEMPTS_PER_TICK] candidate
            // probes per tick until it finds a valid 3×3×3 spot or
            // exhausts its [SEARCH_MAX_ATTEMPTS] budget.
            drainPendingLandingSearches(level)
        }

        // Prune stale return-cooldown entries so the map doesn't grow
        // unbounded across a long session. One sweep every 200 ticks
        // is cheap and keeps the map small (only entities that
        // teleported recently).
        if (gameTime % 200L == 0L) {
            val cutoff = gameTime - RETURN_COOLDOWN_TICKS
            returnCooldowns.entries.removeIf { it.value < cutoff }
        }
    }

    /** Drain a slice of [pendingSearches] in [wohlonLevel]. Each
     *  pending portal probes up to [SEARCH_ATTEMPTS_PER_TICK] random
     *  candidate columns; on the first one that passes
     *  [isLandingValidFor3x3x3], the result is written to the
     *  portal's source-dim [PortalSavedData.landings] and the entry
     *  is removed from the queue. */
    private fun drainPendingLandingSearches(wohlonLevel: ServerLevel) {
        if (pendingSearches.isEmpty()) return
        val server = wohlonLevel.server
        val random = wohlonLevel.random
        synchronized(pendingSearches) {
            val iter = pendingSearches.iterator()
            while (iter.hasNext()) {
                val search = iter.next()
                var triedThisTick = 0
                var landing: BlockPos? = null
                while (triedThisTick < SEARCH_ATTEMPTS_PER_TICK && search.attemptsLeft > 0) {
                    triedThisTick++
                    search.attemptsLeft--
                    val candidate = sampleLandingCandidate(wohlonLevel, random) ?: continue
                    if (isLandingValidFor3x3x3(wohlonLevel, candidate)) {
                        landing = candidate
                        break
                    }
                }
                if (landing != null) {
                    val sourceLevel = server.getLevel(search.sourceDim)
                    if (sourceLevel != null) {
                        val sourceData = sourceLevel.dataStorage.computeIfAbsent(
                            { tag -> PortalSavedData.load(tag) },
                            { PortalSavedData() },
                            DATA_NAME,
                        )
                        sourceData.landings[search.portalPos.asLong()] = landing.asLong()
                        sourceData.setDirty()
                        LOG.info(
                            "WohlonPortal: landing for portal {} {} = {} (attempts used {})",
                            search.sourceDim.location(), search.portalPos, landing,
                            SEARCH_MAX_ATTEMPTS - search.attemptsLeft,
                        )
                    }
                    iter.remove()
                } else if (search.attemptsLeft <= 0) {
                    LOG.warn(
                        "WohlonPortal: search exhausted for {} {} — leaving live fallback",
                        search.sourceDim.location(), search.portalPos,
                    )
                    iter.remove()
                }
            }
        }
    }

    /** Pick a random column in the open swamp around origin and walk
     *  down for the first solid, non-log, non-leaf, non-fluid block.
     *  Returns the *ground* block (player stands on top); caller is
     *  expected to verify the 3×3×3 clearance above. */
    private fun sampleLandingCandidate(wohlonLevel: ServerLevel, random: RandomSource): BlockPos? {
        val theta = random.nextDouble() * 2.0 * Math.PI
        val r = 35.0 + random.nextDouble() * 30.0  // 35–65 blocks from origin
        val x = (Math.cos(theta) * r).toInt()
        val z = (Math.sin(theta) * r).toInt()
        val mut = BlockPos.MutableBlockPos()
        for (y in 150 downTo 0) {
            mut.set(x, y, z)
            val state = wohlonLevel.getBlockState(mut)
            if (state.isAir) continue
            if (state.`is`(BlockTags.LOGS)) continue
            if (state.`is`(BlockTags.LEAVES)) continue
            if (!state.fluidState.isEmpty) return null
            if (!state.canOcclude()) continue
            return BlockPos(x, y, z)
        }
        return null
    }

    /** True iff the 3×3×3 region directly above [ground] is fully
     *  passable for a player-sized (and a bit more, for safety) entity
     *  bounding box. Ground itself must already be solid; callers
     *  pass the result of [sampleLandingCandidate] which guarantees
     *  that. */
    private fun isLandingValidFor3x3x3(wohlonLevel: ServerLevel, ground: BlockPos): Boolean {
        val mut = BlockPos.MutableBlockPos()
        for (dy in 1..3) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    mut.set(ground.x + dx, ground.y + dy, ground.z + dz)
                    val s = wohlonLevel.getBlockState(mut)
                    if (!WohlonnogondoniaPortalRitual.isPassable(s)) return false
                }
            }
        }
        return true
    }

    private fun emitParticles(level: ServerLevel, pos: BlockPos, scale: Double = 1.0) {
        val random = level.random
        val cx = pos.x + 0.5
        val cy = pos.y + PORTAL_Y_OFFSET
        val cz = pos.z + 0.5

        val sphereRadius = SPHERE_RADIUS * scale
        val inwardSpeed = INWARD_SPEED * scale
        val convergeCount = (PARTICLES_PER_TICK * scale).toInt()
        val plumeCount = (OUTWARD_PER_TICK * scale).toInt()

        repeat(convergeCount) {
            val theta = random.nextDouble() * 2.0 * Math.PI
            val phi = Math.acos(2.0 * random.nextDouble() - 1.0)
            val sx = Math.sin(phi) * Math.cos(theta)
            val sy = Math.cos(phi)
            val sz = Math.sin(phi) * Math.sin(theta)
            val color = COLOURS[random.nextInt(COLOURS.size)]
            val options = DustParticleOptions(color, 1.0f)
            // count=0 + non-zero dx/dy/dz tells the client to
            // spawn ONE particle with that exact velocity rather
            // than `count` particles with a random spread. The
            // last param (`speed`) multiplies the velocity.
            level.sendParticles(
                options,
                cx + sx * sphereRadius, cy + sy * sphereRadius, cz + sz * sphereRadius,
                0,
                -sx * inwardSpeed, -sy * inwardSpeed, -sz * inwardSpeed,
                1.0,
            )
        }

        repeat(plumeCount) {
            val outTheta = random.nextDouble() * 2.0 * Math.PI
            val color = COLOURS[random.nextInt(COLOURS.size)]
            level.sendParticles(
                DustParticleOptions(color, 1.0f),
                cx, cy, cz,
                0,
                Math.cos(outTheta) * OUTWARD_HORIZ_SPEED,
                OUTWARD_UP_SPEED,
                Math.sin(outTheta) * OUTWARD_HORIZ_SPEED,
                1.0,
            )
        }
    }

    private fun scanAndTeleport(level: ServerLevel, pos: BlockPos) {
        val aabb = portalAabb(pos)
        val entities = level.getEntities(null, aabb)
        if (entities.isEmpty()) return
        val now = level.gameTime
        val cooldownCutoff = now - RETURN_COOLDOWN_TICKS
        for (entity in entities) {
            if (!entity.isAlive) continue
            // Skip entities that just returned from the heart portal —
            // they always land at the source portal's pos (entry-tracker
            // semantics), so without this they'd bounce right back to
            // Wohlon on the next scan.
            val lastReturn = returnCooldowns[entity.uuid]
            if (lastReturn != null && lastReturn >= cooldownCutoff) continue
            WohlonnogondoniaPortalRitual.teleportToMotherTree(entity, level, pos)
        }
    }

    private fun scanAndTeleportHeart(level: ServerLevel, pos: BlockPos) {
        val aabb = portalAabb(pos, MOTHER_TELEPORT_RADIUS)
        val entities = level.getEntities(null, aabb)
        if (entities.isEmpty()) return
        LOG.info(
            "WohlonPortal: heart-return scan at {} found {} entity(s) — dispatching",
            pos, entities.size,
        )
        for (entity in entities) {
            if (!entity.isAlive) continue
            WohlonnogondoniaPortalRitual.teleportFromHeart(entity, level)
        }
    }

    private fun portalAabb(pos: BlockPos, radius: Double = TELEPORT_RADIUS): AABB = AABB(
        pos.x - radius, pos.y - radius, pos.z - radius,
        pos.x + 1 + radius, pos.y + 1 + radius, pos.z + 1 + radius,
    )

    /** Per-dimension persistent storage. Holds a `LongOpenHashSet`-
     *  equivalent (`MutableSet<Long>` of `BlockPos.asLong`) — the
     *  long-packed form keeps the saved blob compact and lets us
     *  reuse the packed form as the iteration key without
     *  boxing/unboxing per portal. */
    class PortalSavedData : SavedData() {

        @JvmField val positions: MutableSet<Long> = HashSet()
        /** Pre-computed Wohlonnogondonia landing spot for each portal.
         *  Keys are packed portal positions (matching [positions]);
         *  values are packed Wohlon block positions. Populated by
         *  the off-tick search kicked off at portal registration;
         *  consumed by [WohlonnogondoniaPortalRitual.teleportToMotherTree].
         *  A portal whose search hasn't finished yet (or that loaded
         *  from a pre-feature save) has no entry — the teleport
         *  falls back to a live `pickLandingSpot`. */
        @JvmField val landings: MutableMap<Long, Long> = HashMap()

        override fun save(tag: CompoundTag): CompoundTag {
            tag.put("positions", LongArrayTag(positions.toLongArray()))
            if (landings.isNotEmpty()) {
                val flat = LongArray(landings.size * 2)
                var i = 0
                for ((portal, landing) in landings) {
                    flat[i++] = portal
                    flat[i++] = landing
                }
                tag.putLongArray("landings", flat)
            }
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): PortalSavedData {
                val data = PortalSavedData()
                if (tag.contains("positions", Tag.TAG_LONG_ARRAY.toInt())) {
                    for (v in tag.getLongArray("positions")) data.positions.add(v)
                }
                if (tag.contains("landings", Tag.TAG_LONG_ARRAY.toInt())) {
                    val flat = tag.getLongArray("landings")
                    var i = 0
                    while (i + 1 < flat.size) {
                        data.landings[flat[i]] = flat[i + 1]
                        i += 2
                    }
                }
                return data
            }
        }
    }

    /** Player UUID → last outbound-portal entry. One instance per
     *  server, lives on the overworld dimension's SavedData. */
    class EntryTracker : SavedData() {

        @JvmField val entries: MutableMap<UUID, EntryRecord> = HashMap()

        override fun save(tag: CompoundTag): CompoundTag {
            val list = ListTag()
            for ((uuid, record) in entries) {
                val entry = CompoundTag()
                entry.putUUID("uuid", uuid)
                entry.putString("dim", record.dim.location().toString())
                entry.putLong("pos", record.pos.asLong())
                list.add(entry)
            }
            tag.put("entries", list)
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): EntryTracker {
                val data = EntryTracker()
                if (tag.contains("entries", Tag.TAG_LIST.toInt())) {
                    val list = tag.getList("entries", Tag.TAG_COMPOUND.toInt())
                    for (i in 0 until list.size) {
                        val entry = list.getCompound(i)
                        val uuid = entry.getUUID("uuid")
                        val dimKey = ResourceKey.create(
                            Registries.DIMENSION,
                            ResourceLocation(entry.getString("dim")),
                        )
                        val pos = BlockPos.of(entry.getLong("pos"))
                        data.entries[uuid] = EntryRecord(dimKey, pos)
                    }
                }
                return data
            }
        }
    }

    /** One row of the entry tracker: which dimension and which
     *  portal block the player last used to enter Wohlon. */
    data class EntryRecord(val dim: ResourceKey<Level>, val pos: BlockPos)
}

