package org.shipwrights.enderkinesis.client

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import org.shipwrights.enderkinesis.block.WogorBudBlock
import kotlin.math.max
import kotlin.math.min

/** A bud first seen in chunk data has no known start time, so it is drawn settled: AGE size, no roots. */
// Client thread only.
object WogorBudClientTracker {

    class Bud(val pos: Long) {
        var age = 0
        var stageStart = 0L
        /** Game time at which AGE 0 began; estimated from [WogorBudBlock.growDelay] for a late sighting. */
        var rootStart = 0L
        /** Sum of the four AGE delays: the full grow time. */
        var mature = 0
        /** Bit `1 shl direction.ordinal` per sturdy support face. */
        var faces = 0
        var size = 0.0
        var prevSize = 0.0
        var velocity = 0.0
        /** False for a bud first seen in chunk data: its start time is unknown, so it shows no roots. */
        var animated = true
    }

    val buds = Long2ObjectLinkedOpenHashMap<Bud>()
    private var trackedLevel: Level? = null
    private val scratch = BlockPos.MutableBlockPos()
    private val neighbour = BlockPos.MutableBlockPos()

    @JvmStatic
    fun onBlockChanged(level: Level, pos: BlockPos, state: BlockState) {
        if (!level.isClientSide) return
        useLevel(level)
        val key = pos.asLong()
        if (state.block !is WogorBudBlock) {
            buds.remove(key)
            return
        }
        val age = state.getValue(WogorBudBlock.AGE)
        val bud = buds.get(key)
        if (bud == null) {
            add(level, key, age, animated = true)
        } else if (bud.age != age) {
            startStage(bud, age, level.gameTime)
        }
    }

    // Not ClientChunkCache.replaceWithPacketData: VS2 cancels that method at HEAD for shipyard chunks.
    @JvmStatic
    fun onChunkLoaded(chunk: LevelChunk) {
        val level = chunk.level
        if (!level.isClientSide) return
        useLevel(level)
        val sections = chunk.sections
        for (i in sections.indices) {
            val section = sections[i]
            if (section.hasOnlyAir() || !section.maybeHas { it.block is WogorBudBlock }) continue
            val baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i))
            for (y in 0 until SECTION_SIZE) for (z in 0 until SECTION_SIZE) for (x in 0 until SECTION_SIZE) {
                val state = section.getBlockState(x, y, z)
                if (state.block !is WogorBudBlock) continue
                val key = BlockPos.asLong(chunk.pos.minBlockX + x, baseY + y, chunk.pos.minBlockZ + z)
                if (!buds.containsKey(key)) add(level, key, state.getValue(WogorBudBlock.AGE), animated = false)
            }
        }
    }

    fun tick(level: Level) {
        useLevel(level)
        if (buds.isEmpty()) return
        val now = level.gameTime
        val it = buds.values.iterator()
        while (it.hasNext()) {
            val bud = it.next()
            scratch.set(bud.pos)
            val state = level.getBlockState(scratch)
            if (state.block !is WogorBudBlock) {
                it.remove()
                continue
            }
            val age = state.getValue(WogorBudBlock.AGE)
            if (age != bud.age) startStage(bud, age, now)
            bud.faces = WogorBudBlock.supportMask(level, scratch, neighbour)
            integrate(bud, now)
        }
    }

    fun clear() {
        buds.clear()
        trackedLevel = null
    }

    private fun useLevel(level: Level) {
        if (trackedLevel === level) return
        buds.clear()
        trackedLevel = level
    }

    private fun add(level: Level, key: Long, age: Int, animated: Boolean) {
        if (buds.size >= MAX_BUDS) buds.removeFirst()
        val bud = Bud(key)
        scratch.set(key)
        bud.mature = (0..WogorBudBlock.MAX_AGE).sumOf { WogorBudBlock.growDelay(scratch, it) }
        bud.animated = animated
        bud.faces = WogorBudBlock.supportMask(level, scratch, neighbour)
        startStage(bud, age, level.gameTime)
        if (!animated) {
            bud.size = stageEndPx(age)
            bud.prevSize = bud.size
        }
        buds.put(key, bud)
    }

    private fun startStage(bud: Bud, age: Int, now: Long) {
        bud.age = age
        bud.stageStart = now
        scratch.set(bud.pos)
        bud.rootStart = now - (0 until age).sumOf { WogorBudBlock.growDelay(scratch, it) }
    }

    /** Chase this AGE's end size by the planned end of the AGE, with a low-pass on the velocity. */
    private fun integrate(bud: Bud, now: Long) {
        bud.prevSize = bud.size
        scratch.set(bud.pos)
        val end = stageEndPx(bud.age)
        val plannedEnd = bud.stageStart + WogorBudBlock.growDelay(scratch, bud.age)
        for (i in 1..SUBSTEPS) {
            val t = now - 1 + i * DT
            val left = max(MIN_LEFT_TICKS, plannedEnd - t)
            bud.velocity += ((end - bud.size) / left - bud.velocity) * min(1.0, DT / VELOCITY_LAG_TICKS)
            bud.size = max(0.0, bud.size + bud.velocity * DT)
        }
    }

    /** Drawn size (px) at the end of [age]: the swell spreads 0 → 16 px evenly over the four AGEs. */
    private fun stageEndPx(age: Int): Double = FULL_PX * (age + 1) / (WogorBudBlock.MAX_AGE + 1)

    /** Bound on tracked buds; the oldest entry goes first. */
    private const val MAX_BUDS = 8192
    private const val SECTION_SIZE = 16
    private const val FULL_PX = 16.0
    private const val SUBSTEPS = 10
    private const val DT = 1.0 / SUBSTEPS
    private const val MIN_LEFT_TICKS = 0.5
    private const val VELOCITY_LAG_TICKS = 1.2
}
