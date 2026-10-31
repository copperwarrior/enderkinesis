package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleHullLog
import org.shipwrights.enderkinesis.block.YgannBarnacleBlock
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity

// Client thread only (the render thread).
object YgannBarnacleClientWork {

    val hullLog = YgannBarnacleHullLog(HULL_LOG_CAPACITY)

    /** This frame's culling frustum, or null before the first frame. */
    var frustum: Frustum? = null
        private set

    private abstract class Job {
        /** True when the job ran a walk, so it counts against the frame's budget. */
        abstract fun run(level: Level): Boolean
    }

    private class BarnacleJob(val be: YgannBarnacleBlockEntity) : Job() {
        override fun run(level: Level): Boolean {
            be.clientWalkQueued = false
            if (be.isRemoved || be.level !== level) return false
            val state = be.blockState
            if (state.block !is YgannBarnacleBlock) return false
            val walk = YgannBarnacleGeometry.walk(level, be.blockPos, be.seed, state.getValue(YgannBarnacleBlock.FACING))
            walk.hull.watch(level, hullLog)
            be.clientPendingWalk = walk
            return true
        }
    }

    private class DeathJob(
        val level: Level, val pos: BlockPos, val seed: Long, val facing: Direction,
        val onReady: (List<YgannBarnacleGeometry.BPath>) -> Unit,
    ) : Job() {
        override fun run(level: Level): Boolean {
            if (level !== this.level) return false
            onReady(YgannBarnacleGeometry.walk(level, pos, seed, facing).paths)
            return true
        }
    }

    private val jobs = ArrayDeque<Job>()

    /** Mixin hook at the start of `LevelRenderer.renderLevel`. */
    @JvmStatic
    fun beginFrame(frustum: Frustum?) {
        this.frustum = frustum
        YgannBarnacleClientSounds.flush()
        val level = Minecraft.getInstance().level
        if (level == null) {
            jobs.clear()
            return
        }
        var budget = WALKS_PER_FRAME
        while (budget > 0) {
            val job = jobs.removeFirstOrNull() ?: break
            if (job.run(level)) budget--
        }
    }

    /** Queues a walk for [be]; it lands in `clientPendingWalk`. The barnacle keeps its current walk until then. */
    fun requestWalk(be: YgannBarnacleBlockEntity) {
        if (be.clientWalkQueued) return
        be.clientWalkQueued = true
        jobs.addLast(BarnacleJob(be))
    }

    /** Queues a death notice's walk ahead of the barnacles' own walks. */
    fun requestDeathWalk(
        level: Level, pos: BlockPos, seed: Long, facing: Direction, onReady: (List<YgannBarnacleGeometry.BPath>) -> Unit,
    ) {
        jobs.addFirst(DeathJob(level, pos, seed, facing, onReady))
    }

    fun clear() {
        for (job in jobs) if (job is BarnacleJob) job.be.clientWalkQueued = false
        jobs.clear()
    }

    @JvmStatic
    fun onBlockChanged(level: Level, pos: BlockPos) = hullLog.blockChanged(level, pos.asLong())

    @JvmStatic
    fun onChunkChanged(level: Level, chunkX: Int, chunkZ: Int) = hullLog.chunkChanged(level, chunkX, chunkZ)

    @JvmStatic
    fun onChunkForgotten(chunkX: Int, chunkZ: Int) {
        val level = Minecraft.getInstance().level ?: return
        hullLog.chunkChanged(level, chunkX, chunkZ)
    }

    /** Walks per frame, so a colony that comes into range does not run all its walks in one frame. */
    private const val WALKS_PER_FRAME = 2
    private const val HULL_LOG_CAPACITY = 4096
}
