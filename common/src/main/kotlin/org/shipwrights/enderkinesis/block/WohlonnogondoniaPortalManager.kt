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
     *  Two blocks below the heart sea-lantern (which lives at
     *  WorldOrigin Y = SEA_LEVEL_Y + 4 = 68 — see
     *  `WohlonnogondoniaChunkGenerator.HEART_Y`), placing the
     *  portal at Y 66. Hardcoded singleton rather than registered
     *  in [PortalSavedData] — it's a deterministic dimension
     *  fixture, not something the player builds. */
    private val HEART_RETURN_PORTAL_POS: BlockPos = BlockPos(0, 66, 0)

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
     *  each portal. 0.7 reaches just past the candle's adjacent
     *  voxels — players walking onto or right next to the portal
     *  trigger; players walking past at distance don't. */
    private const val TELEPORT_RADIUS: Double = 0.7

    /** Number of converging-sphere particles emitted per portal
     *  per server tick. Bumped from 2 → 10 for a denser halo. */
    private const val PARTICLES_PER_TICK: Int = 10

    /** Number of outward-up plume particles emitted per portal
     *  per server tick. Continuous (every tick) at this density
     *  rather than the previous 1-in-N gate. */
    private const val OUTWARD_PER_TICK: Int = 3

    /** Radius of the sphere where converging particles spawn (in
     *  blocks). */
    private const val SPHERE_RADIUS: Double = 0.9

    /** Inward velocity applied to converging particles. ~22 ticks
     *  to cross [SPHERE_RADIUS], so the arc is visible. */
    private const val INWARD_SPEED: Double = 0.04

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
    }

    /** Persist a portal anchor at [pos] in [level]. Idempotent —
     *  adding the same position twice is a no-op. */
    @JvmStatic
    fun addPortal(level: ServerLevel, pos: BlockPos) {
        val data = getData(level)
        if (data.positions.add(pos.asLong())) {
            data.setDirty()
            LOG.debug("WohlonPortal: registered anchor at {} in {}", pos, level.dimension().location())
        }
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
            emitParticles(level, HEART_RETURN_PORTAL_POS)
            if (doTeleport) scanAndTeleportHeart(level, HEART_RETURN_PORTAL_POS)
        }
    }

    private fun emitParticles(level: ServerLevel, pos: BlockPos) {
        val random = level.random
        val cx = pos.x + 0.5
        val cy = pos.y + PORTAL_Y_OFFSET
        val cz = pos.z + 0.5

        repeat(PARTICLES_PER_TICK) {
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
                cx + sx * SPHERE_RADIUS, cy + sy * SPHERE_RADIUS, cz + sz * SPHERE_RADIUS,
                0,
                -sx * INWARD_SPEED, -sy * INWARD_SPEED, -sz * INWARD_SPEED,
                1.0,
            )
        }

        repeat(OUTWARD_PER_TICK) {
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
        for (entity in level.getEntities(null, aabb)) {
            if (!entity.isAlive) continue
            WohlonnogondoniaPortalRitual.teleportToMotherTree(entity, level, pos)
        }
    }

    private fun scanAndTeleportHeart(level: ServerLevel, pos: BlockPos) {
        val aabb = portalAabb(pos)
        for (entity in level.getEntities(null, aabb)) {
            if (!entity.isAlive) continue
            WohlonnogondoniaPortalRitual.teleportFromHeart(entity, level)
        }
    }

    private fun portalAabb(pos: BlockPos): AABB = AABB(
        pos.x - TELEPORT_RADIUS, pos.y - TELEPORT_RADIUS, pos.z - TELEPORT_RADIUS,
        pos.x + 1 + TELEPORT_RADIUS, pos.y + 1 + TELEPORT_RADIUS, pos.z + 1 + TELEPORT_RADIUS,
    )

    /** Per-dimension persistent storage. Holds a `LongOpenHashSet`-
     *  equivalent (`MutableSet<Long>` of `BlockPos.asLong`) — the
     *  long-packed form keeps the saved blob compact and lets us
     *  reuse the packed form as the iteration key without
     *  boxing/unboxing per portal. */
    class PortalSavedData : SavedData() {

        @JvmField val positions: MutableSet<Long> = HashSet()

        override fun save(tag: CompoundTag): CompoundTag {
            tag.put("positions", LongArrayTag(positions.toLongArray()))
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): PortalSavedData {
                val data = PortalSavedData()
                if (tag.contains("positions", Tag.TAG_LONG_ARRAY.toInt())) {
                    for (v in tag.getLongArray("positions")) data.positions.add(v)
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

