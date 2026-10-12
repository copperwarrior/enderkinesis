package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.registry.EKEntities
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Per-server-level deferred-spawn queue for Sselith chunk-gen
 * entities (catalogers + gallery paintings).
 *
 *  Chunk gen on the worker thread can't spawn entities without
 *  routing back through the server thread via `ServerLevel.addFresh
 *  Entity`. Doing that synchronously in [SselithRepertoryChunkGenerator.
 *  spawnOriginalMobs] serialised every chunk's worth of spawns onto
 *  whichever tick the chunk completed on — a batch of 100 chunks all
 *  reaching SPAWN status the same tick produced a measurable mspt
 *  spike. This queue holds the spawn intents and drains a small
 *  budget per server-level tick, smearing the cost.
 *
 *  Persisted as `sselith_pending_spawns.dat` under the level data
 *  folder so intents survive a shutdown mid-drain — the alternative
 *  loses chunk-gen-spawned entities for chunks the player never
 *  loaded before quitting.
 */
class SselithPendingSpawns : SavedData() {

    sealed class Intent {
        abstract val chunkX: Int
        abstract val chunkZ: Int
        data class Cataloger(val x: Int, val y: Int, val z: Int) : Intent() {
            override val chunkX: Int get() = x shr 4
            override val chunkZ: Int get() = z shr 4
        }
        data class Archive(val x: Int, val y: Int, val z: Int) : Intent() {
            override val chunkX: Int get() = x shr 4
            override val chunkZ: Int get() = z shr 4
        }
        data class Painting(val tag: CompoundTag) : Intent() {
            override val chunkX: Int get() = posComponent(0) shr 4
            override val chunkZ: Int get() = posComponent(2) shr 4
            private fun posComponent(index: Int): Int =
                tag.getList("Pos", Tag.TAG_DOUBLE.toInt()).getDouble(index).toInt()
        }
    }

    private val queue: ArrayDeque<Intent> = ArrayDeque()

    /** Server-thread only. Workers must go through the static
     *  [enqueue]/[INBOX] path because [SavedData] mutations are
     *  unsafe off the server thread. */
    private fun addInternal(intent: Intent) {
        if (queue.size >= QUEUE_CAP) queue.poll()
        queue.add(intent)
        setDirty()
    }

    /** Drain up to [budget] intents this tick. Each intent is only
     *  processed if its chunk is currently loaded — otherwise it
     *  goes to the tail and is reconsidered on a later tick. */
    @Synchronized
    fun drain(level: ServerLevel, budget: Int): Int {
        // Each intent is polled at most one time per tick, so the tail
        // rotation revisits every intent over successive ticks.
        val scanLimit = minOf(queue.size, MAX_SCAN_PER_TICK)
        var scanned = 0
        var processed = 0
        while (scanned < scanLimit && processed < budget) {
            val intent = queue.poll() ?: break
            scanned++
            // If the player isn't near, we hold the intent until they come back.
            if (!isChunkReady(level, intent.chunkX, intent.chunkZ)) {
                queue.add(intent)
                continue
            }
            val handled: Boolean = try {
                when (intent) {
                    is Intent.Cataloger -> { spawnCataloger(level, intent); true }
                    is Intent.Archive -> { spawnArchive(level, intent); true }
                    is Intent.Painting -> spawnPainting(level, intent)
                }
            } catch (_: Throwable) {
                // Don't let one bad intent poison the whole queue.
                true
            }
            if (!handled) {
                queue.add(intent)
                continue
            }
            processed++
        }
        if (processed > 0) setDirty()
        return processed
    }

    private fun spawnArchive(level: ServerLevel, intent: Intent.Archive) {
        val type = EKEntities.ARCHIVE.get()
        val archive = type.create(level) ?: return
        // Re-verify the chosen spawn tile is still passable (3 tall for the
        // archive's 1×3×1 bbox) — chunk decoration can land blocks on top of
        // a tile that was air during fillFromNoise. If not, search nearby.
        val (sx, sy, sz) = findOpenAirSpawn(level, intent.x, intent.y, intent.z, 3) ?: return
        archive.moveTo(
            sx + 0.5, sy.toDouble(), sz + 0.5,
            level.random.nextFloat() * 360f, 0f,
        )
        archive.finalizeSpawn(
            level, level.getCurrentDifficultyAt(archive.blockPosition()),
            MobSpawnType.CHUNK_GENERATION, null, null,
        )
        level.addFreshEntity(archive)
    }

    private fun spawnCataloger(level: ServerLevel, intent: Intent.Cataloger) {
        val type = EKEntities.CATALOGER.get()
        val cataloger = type.create(level) ?: return
        // Chunk gen picked (intent.x, intent.y, intent.z) one block
        // above the platform floor — but later passes (corridor paint,
        // decor slabs / walls, salt NBT, ring decoration, structure
        // replacements) can land a block on that exact tile by the
        // time we drain. Spawning inside a block traps the entity and
        // suffocates it the moment it ticks. Find a 2-tall passable
        // column near the planned position; prefer up (the player-
        // visible platform surface is below), fall back to down (the
        // planned tile was raised by a stack of slabs), and give up
        // if neither finds clear air within `SEARCH_RADIUS`.
        val (sx, sy, sz) = findOpenAirSpawn(level, intent.x, intent.y, intent.z) ?: return
        cataloger.moveTo(
            sx + 0.5, sy.toDouble(), sz + 0.5,
            level.random.nextFloat() * 360f, 0f,
        )
        cataloger.finalizeSpawn(
            level, level.getCurrentDifficultyAt(cataloger.blockPosition()),
            MobSpawnType.CHUNK_GENERATION, null, null,
        )
        level.addFreshEntity(cataloger)
    }

    /** Finds a [columnTall]-tall passable column near `(x, y, z)` — i.e.,
     *  the entity can stand without suffocating. 2 = cataloger (feet + head),
     *  3 = archive (1×3×1 bbox).
     *
     *  Search order prioritises HORIZONTAL displacement before vertical: a
     *  small XZ step away keeps the entity at the planned platform/floor
     *  height, while a vertical step lifts/drops it onto a different layer
     *  (which for a cataloger means a different platform, and for an archive
     *  means a different cell altogether). We sweep increasing Chebyshev
     *  rings at the seed Y first, only falling back to the vertical ±dy
     *  scan after the horizontal envelope is exhausted. */
    private fun findOpenAirSpawn(
        level: ServerLevel, x: Int, y: Int, z: Int, columnTall: Int = 2,
    ): Triple<Int, Int, Int>? {
        val pos = BlockPos.MutableBlockPos()
        if (isPassable(level, pos, x, y, z, columnTall)) return Triple(x, y, z)
        // Horizontal rings at the seed Y (priority): expand Chebyshev radius.
        for (r in 1..HORIZONTAL_SEARCH_RADIUS) {
            for (dx in -r..r) {
                for (dz in -r..r) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue
                    if (isPassable(level, pos, x + dx, y, z + dz, columnTall)) return Triple(x + dx, y, z + dz)
                }
            }
        }
        // Vertical fallback: only if no same-layer tile worked.
        for (dy in 1..SEARCH_RADIUS) {
            if (isPassable(level, pos, x, y + dy, z, columnTall)) return Triple(x, y + dy, z)
            if (isPassable(level, pos, x, y - dy, z, columnTall)) return Triple(x, y - dy, z)
        }
        return null
    }

    // `hasChunk` is true for a chunk that has a FULL ticket but is still in generation.
    // `getBlockState` there blocks the server thread until generation completes.
    private fun isChunkReady(level: ServerLevel, chunkX: Int, chunkZ: Int): Boolean =
        level.chunkSource.getChunkNow(chunkX, chunkZ) != null

    private fun isPassable(
        level: ServerLevel, pos: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int, columnTall: Int,
    ): Boolean {
        for (dy in 0 until columnTall) {
            pos.set(x, y + dy, z)
            // `getBlockState` on an unloaded chunk loads or generates it on the server thread.
            if (!isChunkReady(level, x shr 4, z shr 4)) return false
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) return false
        }
        return true
    }

    /** Returns `true` if the intent was either spawned or definitively
     *  dropped (junk variant / vanilla rejection), or `false` if a
     *  backer chunk isn't loaded yet and the intent should be
     *  re-queued for a later tick.
     *
     *  We deliberately do NOT pre-check `survives()` here — paintings
     *  are entities and vanilla `HangingEntity.tick` runs its own
     *  survival check every 100 ticks, dropping the entity as an item
     *  if its backer is missing. Letting vanilla handle it means a
     *  painting whose spine wall was carved still appears in the
     *  gallery briefly and drops as an item the player can collect,
     *  rather than silently vanishing during chunk gen. */
    private fun spawnPainting(level: ServerLevel, intent: Intent.Painting): Boolean {
        // Backer chunks MUST be loaded before we resolve the painting's
        // wall footprint with `level.getBlockState`. `ServerLevel.get
        // BlockState` force-loads the chunk if it's missing, which on
        // the server thread runs the full chunk-gen pipeline
        // synchronously — for Sselith that's multi-second per chunk
        // and can chain through several neighbour chunks, producing
        // the "total server thread freeze" observed when a painting's
        // backer footprint spans a chunk boundary into not-yet-
        // generated territory. Returning false re-queues the intent;
        // by the time the player gets close enough to actually see the
        // painting, the neighbour chunks will have been loaded by
        // normal exploration and the drain will succeed.
        val tag = intent.tag
        val tileX = tag.getInt("TileX")
        val tileY = tag.getInt("TileY")
        val tileZ = tag.getInt("TileZ")
        // Vanilla `Painting` (1.20.1) writes/reads the lowercase
        // `"facing"` NBT key; capitalized `"Facing"` is silently 0
        // on read — the painting would load facing SOUTH.
        val facing = tag.getByte("facing").toInt() and 0xFF
        val dir = Direction.from2DDataValue(facing)
        val variantStr = tag.getString("variant")
        val variantLoc = net.minecraft.resources.ResourceLocation.tryParse(variantStr) ?: return true
        val variantKey = net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.PAINTING_VARIANT, variantLoc,
        )
        val variant = net.minecraft.core.registries.BuiltInRegistries.PAINTING_VARIANT.get(variantKey) ?: return true
        val w = kotlin.math.max(1, variant.width / 16)
        val h = kotlin.math.max(1, variant.height / 16)
        val mk = (w - 1) / -2
        val ml = (h - 1) / -2
        val backerBase = BlockPos(tileX, tileY, tileZ).relative(dir.opposite)
        val ccw = dir.counterClockWise
        val scratch = BlockPos.MutableBlockPos()
        for (k in 0 until w) {
            for (l in 0 until h) {
                scratch.set(backerBase).move(ccw, k + mk).move(Direction.UP, l + ml)
                if (!isChunkReady(level, scratch.x shr 4, scratch.z shr 4)) return false
            }
        }
        val painting = EntityType.create(tag, level).orElse(null) ?: return true
        level.addFreshEntity(painting)
        return true
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val cataloger = ListTag()
        val archives = ListTag()
        val paintings = ListTag()
        for (intent in queue) when (intent) {
            is Intent.Cataloger -> {
                val n = CompoundTag()
                n.putInt("X", intent.x); n.putInt("Y", intent.y); n.putInt("Z", intent.z)
                cataloger.add(n)
            }
            is Intent.Archive -> {
                val n = CompoundTag()
                n.putInt("X", intent.x); n.putInt("Y", intent.y); n.putInt("Z", intent.z)
                archives.add(n)
            }
            is Intent.Painting -> paintings.add(intent.tag.copy())
        }
        tag.put("Cataloger", cataloger)
        tag.put("Archive", archives)
        tag.put("Paintings", paintings)
        return tag
    }

    companion object {
        private const val SAVE_KEY = "sselith_pending_spawns"
        /** Per-tick budget. Tuned to keep mspt impact below ~1ms even
         *  if every intent is a painting (the heavier of the two
         *  spawn paths). Larger values drain a backlog faster at the
         *  cost of bigger per-tick spikes. */
        private const val DRAIN_BUDGET_PER_TICK = 8
        /** Max queued intents examined per tick, so a queue of
         *  unloaded-chunk intents does not cost a full scan each tick. */
        private const val MAX_SCAN_PER_TICK = 256
        /** Hard cap on queued intents. A player teleporting into a
         *  fresh region can generate hundreds of chunks in seconds;
         *  the cap prevents unbounded memory growth in the worst
         *  case. When full, the oldest intent is dropped — those
         *  entities just don't spawn (acceptable for decorative mobs
         *  and paintings; the maze itself is unaffected). */
        private const val QUEUE_CAP = 8192
        /** Vertical search radius for [findOpenAirSpawn]. 6 covers the
         *  full procedural decor stack (slab + wall + cap) plus a
         *  small NBT replacement buffer; deeper search would risk
         *  spawning the cataloger several blocks away from its
         *  intended platform on a quirky tile. */
        private const val SEARCH_RADIUS = 6
        /** Horizontal Chebyshev radius for [findOpenAirSpawn]. Smaller
         *  than [SEARCH_RADIUS] because the chosen tile is usually very
         *  close to a passable neighbour at the same Y — a 4-block envelope
         *  catches almost every "blocked by decor" case without wandering
         *  to unintended platforms. Each radius ring scans an
         *  `8r`-tile perimeter, so r=4 is 8+16+24+32 = 80 max checks. */
        private const val HORIZONTAL_SEARCH_RADIUS = 4

        /** Thread-safe inbox keyed by dimension. Chunk-gen workers
         *  [enqueue] into this map without touching [SavedData] —
         *  vanilla `DimensionDataStorage` uses a plain HashMap, and
         *  concurrent `computeIfAbsent` from multiple worker threads
         *  corrupts the internal state and wedges every subsequent
         *  access (including the server thread's). The server thread
         *  moves intents from this inbox into the per-level [SavedData]
         *  each tick and before a save. Workers only [enqueue]. */
        private val INBOX: ConcurrentHashMap<ResourceKey<Level>, ConcurrentLinkedQueue<Intent>> =
            ConcurrentHashMap()

        /** Worker-safe enqueue. Adds the intent to the dimension's
         *  inbox; the next server-thread [tick] will move it into
         *  the persistent queue and start counting against the
         *  drain budget. Safe to call from any thread. */
        @JvmStatic
        fun enqueue(level: ServerLevel, intent: Intent) {
            INBOX.computeIfAbsent(level.dimension()) { ConcurrentLinkedQueue() }.offer(intent)
        }

        @JvmStatic
        fun get(level: ServerLevel): SselithPendingSpawns =
            level.dataStorage.computeIfAbsent(::load, ::SselithPendingSpawns, SAVE_KEY)

        @JvmStatic
        fun load(tag: CompoundTag): SselithPendingSpawns {
            val out = SselithPendingSpawns()
            val cataloger = tag.getList("Cataloger", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until cataloger.size) {
                val n = cataloger.getCompound(i)
                out.queue.add(Intent.Cataloger(n.getInt("X"), n.getInt("Y"), n.getInt("Z")))
            }
            val archives = tag.getList("Archive", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until archives.size) {
                val n = archives.getCompound(i)
                out.queue.add(Intent.Archive(n.getInt("X"), n.getInt("Y"), n.getInt("Z")))
            }
            val paintings = tag.getList("Paintings", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until paintings.size) {
                out.queue.add(Intent.Painting(paintings.getCompound(i).copy()))
            }
            return out
        }

        fun init() {
            // Per-level tick so every dim drains its own queue; non-Sselith dims exit at once.
            TickEvent.SERVER_LEVEL_POST.register(::tick)
            // Fabric fires SERVER_LEVEL_SAVE after the SavedData write, so this flush
            // persists on the next save. SERVER_STOPPING runs before the final save.
            LifecycleEvent.SERVER_LEVEL_SAVE.register(::flushInbox)
            LifecycleEvent.SERVER_STOPPING.register { server -> server.allLevels.forEach(::flushInbox) }
            // INBOX is JVM-wide; a second singleplayer world must not get these intents.
            LifecycleEvent.SERVER_STOPPED.register { INBOX.clear() }
        }

        /** Server-thread only. Moves inbox intents into the level's
         *  [SavedData], which enforces [QUEUE_CAP]. */
        private fun flushInbox(level: ServerLevel) {
            val inbox = INBOX[level.dimension()] ?: return
            if (inbox.isEmpty()) return
            val data = get(level)
            // Workers can offer during the loop; past QUEUE_CAP each add only evicts an older intent.
            var moved = 0
            while (moved < QUEUE_CAP) {
                val intent = inbox.poll() ?: break
                data.addInternal(intent)
                moved++
            }
        }

        @JvmStatic
        fun tick(level: ServerLevel) {
            flushInbox(level)
            // `get`, not `computeIfAbsent`: idle and non-Sselith levels skip SavedData creation.
            val existing = level.dataStorage.get(::load, SAVE_KEY) ?: return
            if (existing.queue.isEmpty()) return
            existing.drain(level, DRAIN_BUDGET_PER_TICK)
        }
    }
}
