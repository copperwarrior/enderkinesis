package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.core.BlockPos
import java.math.BigInteger
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk

/**
 * Tracks which positions in Sselith a player has edited — blocks placed and
 * blocks broken — and random-ticks them.
 *
 * **Attribution.** Placement comes from Architectury's `BlockEvent.PLACE`
 * and breaking from `BlockEvent.BREAK`; both carry the acting entity, and
 * only player actions are recorded, tagged with the player's [SselithParallel]. Non-player destruction of a tracked
 * position (TNT, fluids, mob griefing) instead *forgets* it via
 * [onBlockChanged] — no player did that, so claiming it would be a lie,
 * and dropping it keeps the set from accumulating stale entries.
 *
 * **Random ticking.** [tickChunk] is driven by a `ServerLevel.tickChunk`
 * mixin — the same call vanilla random-ticks from, so tracked positions
 * inherit exactly the right semantics for free: only loaded chunks, only
 * within simulation distance, respecting the `randomTickSpeed` gamerule.
 * Unlike vanilla, the block's own [BlockState.isRandomlyTicking] is never
 * consulted — ANY tracked position ticks, including [SselithEditKind.REMOVED]
 * ones where the block is now air.
 *
 * The per-block rate matches vanilla exactly (`randomTickSpeed / 4096` per
 * block per tick) without ever scanning the whole set: each chunk's
 * expected sample count is `tracked × rate`, and that many uniformly random
 * entries are drawn from a cached flat array. Cost per tick is proportional
 * to loaded tracked chunks — independent of the 1M total.
 *
 * Server-thread only.
 */
object SselithBlockTracker {

    /** Notified for each tracked position selected by the random tick. */
    fun interface RandomTickListener {
        fun onRandomTick(level: ServerLevel, pos: BlockPos, state: BlockState, kind: SselithEditKind, parallel: BigInteger)
    }

    @Volatile
    private var data: SselithPlacementData? = null

    @Volatile
    private var level: ServerLevel? = null

    private val listeners = ArrayList<RandomTickListener>()

    private class PendingEdit(val pos: BlockPos, val kind: SselithEditKind, val parallel: BigInteger, val tick: Long)

    // BREAK, and PLACE on Fabric, fire before the block write, and a later listener can cancel the edit.
    // [onBlockChanged] records the edit when the write occurs in the same tick. Server thread only.
    private var pending: PendingEdit? = null

    /** Registers a random-tick consumer. Call during mod init. */
    fun addRandomTickListener(listener: RandomTickListener) {
        listeners.add(listener)
    }

    fun init() {
        LifecycleEvent.SERVER_LEVEL_LOAD.register { serverLevel ->
            if (serverLevel.dimension() != SselithRepertory.LEVEL_KEY) return@register
            level = serverLevel
            data = serverLevel.dataStorage.computeIfAbsent(
                SselithPlacementData::load,
                ::SselithPlacementData,
                SselithPlacementData.NAME,
            )
        }
        LifecycleEvent.SERVER_LEVEL_UNLOAD.register { serverLevel ->
            if (serverLevel.dimension() != SselithRepertory.LEVEL_KEY) return@register
            level = null
            data = null
            pending = null
        }
        BlockEvent.PLACE.register { placeLevel, pos, state, placer ->
            if (placer is ServerPlayer && placeLevel === level) {
                val parallel = SselithParallel.get(placer)
                // Forge fires PLACE after the block write. Fabric fires it before.
                if (placeLevel.getBlockState(pos) == state) {
                    data?.mark(pos.immutable(), SselithEditKind.PLACED, parallel)
                } else {
                    pending = PendingEdit(pos.immutable(), SselithEditKind.PLACED, parallel, placeLevel.gameTime)
                }
            }
            EventResult.pass()
        }
        BlockEvent.BREAK.register { breakLevel, pos, _, breaker, _ ->
            if (breakLevel === level) {
                pending = PendingEdit(
                    pos.immutable(), SselithEditKind.REMOVED, SselithParallel.get(breaker), breakLevel.gameTime,
                )
            }
            EventResult.pass()
        }
    }

    /** Called from the `LevelChunk.setBlockState` mixin for every server-side
     *  block write, to forget positions destroyed by something other than a
     *  player. Kept as cheap as possible — see the class doc. */
    @JvmStatic
    fun onBlockChanged(changedLevel: Level, pos: BlockPos, newState: BlockState) {
        if (changedLevel !== level) return
        val store = data ?: return
        // A waterlogged block is still present; only a fluid block replaces it.
        val gone = newState.isAir || newState.block is LiquidBlock
        val edit = pending
        if (edit != null && edit.pos == pos && (edit.kind == SselithEditKind.REMOVED) == gone) {
            pending = null
            if (edit.tick == changedLevel.gameTime) {
                store.mark(edit.pos, edit.kind, edit.parallel)
                return
            }
        }
        // Only a disappearance is interesting; a swap to another solid block
        // leaves a placed position player-owned.
        if (!gone) return
        // A position that a player emptied stays REMOVED when a fluid flows in.
        if (store.kindAt(pos) == SselithEditKind.REMOVED) return
        store.forget(pos)
    }

    /** Called from the `ServerLevel.tickChunk` mixin — vanilla's own
     *  random-tick site. [randomTickSpeed] is the gamerule value vanilla
     *  passes in (0 disables random ticking entirely). */
    @JvmStatic
    fun tickChunk(tickLevel: ServerLevel, chunk: LevelChunk, randomTickSpeed: Int) {
        if (tickLevel !== level || randomTickSpeed <= 0) return
        val store = data ?: return
        val chunkKey = chunk.pos.toLong()
        val tracked = store.countInChunk(chunkKey)
        if (tracked == 0) return

        // Vanilla's per-block odds are randomTickSpeed/4096 per section-tick.
        // Expected samples for this chunk = tracked × those odds; take the
        // whole part plus the fraction as a probability, which reproduces
        // that rate exactly without touching untracked positions.
        val random = tickLevel.random
        val expected = tracked.toFloat() * randomTickSpeed / VANILLA_SECTION_VOLUME
        var samples = expected.toInt()
        if (random.nextFloat() < expected - samples) samples++
        if (samples == 0) return

        val snapshot = store.snapshot(chunkKey) ?: return
        val chunkX = chunk.pos.x
        val chunkZ = chunk.pos.z
        val scratch = BlockPos.MutableBlockPos()
        repeat(samples) {
            val packed = snapshot[random.nextInt(snapshot.size)]
            scratch.set(
                SselithPlacementData.decodeX(chunkX, packed),
                SselithPlacementData.decodeY(packed),
                SselithPlacementData.decodeZ(chunkZ, packed),
            )
            val kind = SselithPlacementData.decodeKind(packed)
            val parallel = store.parallelOf(chunkKey, packed)
            // No isRandomlyTicking gate — every tracked position is eligible,
            // including REMOVED ones sitting on air.
            val state = chunk.getBlockState(scratch)
            for (listener in listeners) {
                listener.onRandomTick(tickLevel, scratch, state, kind, parallel)
            }
        }
    }

    /** The kind of player edit recorded at [pos] in Sselith, or null. */
    fun editAt(queryLevel: Level, pos: BlockPos): SselithEditKind? {
        if (queryLevel !== level) return null
        return data?.kindAt(pos)
    }

    fun isPlayerPlaced(queryLevel: Level, pos: BlockPos): Boolean =
        editAt(queryLevel, pos) == SselithEditKind.PLACED

    /** Total tracked positions in Sselith, O(1). */
    fun count(): Int = data?.count ?: 0

    fun countInChunk(chunkX: Int, chunkZ: Int): Int =
        data?.countInChunk(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ)) ?: 0

    /** Visits tracked positions in one chunk; the [BlockPos] is a shared
     *  mutable instance — copy it to retain. */
    fun forEachInChunk(
        chunkX: Int,
        chunkZ: Int,
        action: (BlockPos.MutableBlockPos, SselithEditKind) -> Unit,
    ) {
        data?.forEachInChunk(chunkX, chunkZ, action)
    }

    /** Chunk keys ([net.minecraft.world.level.ChunkPos.asLong]) holding
     *  tracked positions — the entry point for whole-dimension sweeps. */
    fun trackedChunks(): LongArray = data?.trackedChunks() ?: LongArray(0)

    /** Vanilla samples this many positions per section per tick. */
    private const val VANILLA_SECTION_VOLUME = 4096f
}
