package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.block.HeartOfTheWildManager
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Block-entity backing a [org.shipwrights.enderkinesis.block.HeartOfTheWildBlock].
 *
 * Each Heart schedules its next growth attempt by rolling a fresh
 * `[GROW_PERIOD_MIN_TICKS, GROW_PERIOD_MAX_TICKS]` delay (currently
 * 15–20 game ticks) via [HeartOfTheWildManager.nextGrowPeriod] and
 * stashing the absolute tick into [nextAttemptTick]. On the tick that
 * matches, it pops one entry from the appropriate growth queue (ship
 * or world) via [HeartOfTheWildManager.tickHeart] and reschedules.
 *
 * Multiple Hearts on the same scope compound naturally: the
 * per-attempt random window desyncs them after a few fires even if
 * they were placed on the same tick, and each one independently
 * pops a queue entry every ~17 ticks on average, so two Hearts on
 * a ship roughly double the growth throughput on that ship's queue.
 *
 * Ship-residence is tracked by the manager via [registerSelf] /
 * [unregisterSelf] — the manager keys its "ship has a Heart" set on
 * the ship's `id`, so a Heart on a ship enables Wild propagation for
 * every block break on that ship's shipyard space.
 */
class HeartOfTheWildBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.HEART_OF_THE_WILD.get(), pos, state) {

    companion object {
        private val LOG = com.mojang.logging.LogUtils.getLogger()
    }

    /** Game-tick at which this Heart's next growth attempt fires.
     *  `-1` means "not yet scheduled" — the next server tick will
     *  roll an initial delay via
     *  [HeartOfTheWildManager.nextGrowPeriod] and stash the result
     *  here. Subsequent ticks reroll a fresh delay every time the
     *  attempt fires, so two Hearts placed on the same tick will
     *  naturally desync after their first few firings. Not
     *  persisted — re-rolling from the load tick is fine since the
     *  inter-attempt interval is short. */
    private var nextAttemptTick: Long = -1L

    /** Tracks whether [registerSelf] has been called on this BE
     *  instance, so [unregisterSelf] only runs if there's something
     *  to unwind. */
    private var registered: Boolean = false

    override fun setLevel(level: Level) {
        super.setLevel(level)
        registerSelf()
    }

    override fun setRemoved() {
        // setRemoved fires for both manual block removal AND chunk
        // unload (vanilla calls it from LevelChunk.unload), so this
        // single override covers every lifecycle exit path the BE
        // sees on this version.
        unregisterSelf()
        super.setRemoved()
    }

    private fun registerSelf() {
        if (registered) return
        val lvl = level as? ServerLevel ?: return
        HeartOfTheWildManager.registerHeart(lvl, blockPos)
        registered = true
    }

    private fun unregisterSelf() {
        if (!registered) return
        val lvl = level as? ServerLevel ?: return
        HeartOfTheWildManager.unregisterHeart(lvl, blockPos)
        registered = false
    }

    /** Per-tick callback wired from the block's ticker. Fires
     *  growth whenever `gameTime` reaches [nextAttemptTick] and
     *  reschedules with a fresh random interval.
     *
     *  Wrapped in try/catch so a throw inside [tickHeart] (e.g. a
     *  setBlock failure into an unloaded neighbour chunk, an
     *  unexpected null in the VS2 ship lookup) doesn't propagate
     *  up and let vanilla's per-BE error-handling disable this
     *  Heart for the rest of the session. We always reschedule
     *  even after a failure so the next attempt window is still
     *  honoured. */
    fun serverTick(level: ServerLevel, pos: BlockPos) {
        try {
            if (nextAttemptTick < 0L) {
                nextAttemptTick = level.gameTime +
                    HeartOfTheWildManager.nextGrowPeriod(level.random)
                return
            }
            if (level.gameTime < nextAttemptTick) return
            LOG.debug("WildHeart BE: tick firing at {} t={}", pos, level.gameTime)
            HeartOfTheWildManager.tickHeart(level, pos)
            nextAttemptTick = level.gameTime +
                HeartOfTheWildManager.nextGrowPeriod(level.random)
        } catch (t: Throwable) {
            LOG.error("WildHeart BE: tick at $pos threw; rescheduling", t)
            nextAttemptTick = level.gameTime +
                HeartOfTheWildManager.nextGrowPeriod(level.random)
        }
    }
}
