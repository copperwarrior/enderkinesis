package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.ChunkPos
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
        abstract val chunkPos: ChunkPos
        data class Cataloger(val x: Int, val y: Int, val z: Int) : Intent() {
            override val chunkPos: ChunkPos get() = ChunkPos(x shr 4, z shr 4)
        }
        data class Painting(val tag: CompoundTag) : Intent() {
            override val chunkPos: ChunkPos
                get() {
                    val pos = tag.getList("Pos", Tag.TAG_DOUBLE.toInt())
                    return ChunkPos(pos.getDouble(0).toInt() shr 4, pos.getDouble(2).toInt() shr 4)
                }
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
     *  stays in the queue and is reconsidered next tick. */
    @Synchronized
    fun drain(level: ServerLevel, budget: Int): Int {
        if (queue.isEmpty()) return 0
        var processed = 0
        // Single linear scan that promotes processable intents past
        // not-yet-loaded ones rather than swapping deques each tick.
        val skipped = ArrayDeque<Intent>()
        while (queue.isNotEmpty() && processed < budget) {
            val intent = queue.poll()
            val cp = intent.chunkPos
            // `hasChunk` is non-forcing — if the player isn't near,
            // we hold the intent until they come back.
            if (!level.hasChunk(cp.x, cp.z)) {
                skipped.add(intent)
                continue
            }
            val handled: Boolean = try {
                when (intent) {
                    is Intent.Cataloger -> { spawnCataloger(level, intent); true }
                    is Intent.Painting -> spawnPainting(level, intent)
                }
            } catch (_: Throwable) {
                // Don't let one bad intent poison the whole queue.
                true
            }
            if (!handled) {
                skipped.add(intent)
                continue
            }
            processed++
        }
        if (skipped.isNotEmpty()) {
            // Re-queue at the tail so subsequent ticks revisit them
            // (FIFO order across cycles).
            for (s in skipped) queue.add(s)
        }
        if (processed > 0) setDirty()
        return processed
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

    /** Walks up then down from `(x, y, z)` looking for a tile where
     *  the feet block and the head block both have empty collision
     *  shapes — i.e., the cataloger can stand without suffocating.
     *  Returns the first such `(x, openY, z)` within `SEARCH_RADIUS`,
     *  or null if no clear column exists nearby. */
    private fun findOpenAirSpawn(
        level: ServerLevel, x: Int, y: Int, z: Int,
    ): Triple<Int, Int, Int>? {
        val pos = BlockPos.MutableBlockPos()
        val passable: (Int) -> Boolean = { ty ->
            pos.set(x, ty, z)
            val feet = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
            pos.set(x, ty + 1, z)
            val head = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
            feet && head
        }
        if (passable(y)) return Triple(x, y, z)
        for (dy in 1..SEARCH_RADIUS) {
            if (passable(y + dy)) return Triple(x, y + dy, z)
            if (passable(y - dy)) return Triple(x, y - dy, z)
        }
        return null
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
                if (!level.hasChunk(scratch.x shr 4, scratch.z shr 4)) return false
            }
        }
        val painting = EntityType.create(tag, level).orElse(null) ?: return true
        level.addFreshEntity(painting)
        return true
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val cataloger = ListTag()
        val paintings = ListTag()
        for (intent in queue) when (intent) {
            is Intent.Cataloger -> {
                val n = CompoundTag()
                n.putInt("X", intent.x); n.putInt("Y", intent.y); n.putInt("Z", intent.z)
                cataloger.add(n)
            }
            is Intent.Painting -> paintings.add(intent.tag.copy())
        }
        tag.put("Cataloger", cataloger)
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
        /** Per-tick limit on inbox→SavedData transfers. Decoupled from
         *  [DRAIN_BUDGET_PER_TICK] (which is per-spawn) so the move
         *  itself doesn't dominate the tick when a burst of chunks
         *  arrive — typical batches are well under this. */
        private const val INBOX_DRAIN_PER_TICK = 64
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

        /** Thread-safe inbox keyed by dimension. Chunk-gen workers
         *  [enqueue] into this map without touching [SavedData] —
         *  vanilla `DimensionDataStorage` uses a plain HashMap, and
         *  concurrent `computeIfAbsent` from multiple worker threads
         *  corrupts the internal state and wedges every subsequent
         *  access (including the server thread's). The server-thread
         *  [tick] drain moves intents from this inbox into the
         *  per-level [SavedData] before processing spawns. */
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
            val paintings = tag.getList("Paintings", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until paintings.size) {
                out.queue.add(Intent.Painting(paintings.getCompound(i).copy()))
            }
            return out
        }

        @JvmStatic
        fun tick(level: ServerLevel) {
            // Fast-path when nothing to do — neither the inbox nor
            // the SavedData has work. Avoids the `DimensionDataStorage`
            // touch for non-Sselith / idle ticks.
            val inbox = INBOX[level.dimension()]
            val inboxHasWork = inbox != null && !inbox.isEmpty()
            if (!inboxHasWork) {
                // Only need to call into SavedData if we know the
                // persistent queue might have work. The SavedData
                // load is itself cheap once cached, but avoiding it
                // entirely for the 99% empty case is worth it.
                val existing = level.dataStorage.get(::load, SAVE_KEY)
                if (existing == null || existing.queue.isEmpty()) return
            }
            val data = get(level)
            if (inbox != null) {
                // Move queued intents from inbox to SavedData. Cap the
                // per-tick move so a giant inbox doesn't stall the
                // server-thread on this alone.
                var moved = 0
                while (moved < INBOX_DRAIN_PER_TICK) {
                    val intent = inbox.poll() ?: break
                    data.addInternal(intent)
                    moved++
                }
            }
            data.drain(level, DRAIN_BUDGET_PER_TICK)
        }
    }
}
