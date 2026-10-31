package org.shipwrights.enderkinesis.client

import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongArrays
import java.util.function.Predicate
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.Holder
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.chunk.LevelChunk
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.dimension.WohlonBiomePresence

/** Keeps [WohlonBiomePresence.nearby] current, one scan each second. */
object WohlonBiomePresenceScanner {

    private const val SCAN_INTERVAL_TICKS = 20L

    // Remeshing all found chunks in one tick made the light floor appear in one step.
    private const val REMESH_CHUNKS_PER_TICK = 4

    private val IS_WOHLON = Predicate<Holder<Biome>> { it.`is`(Wohlonnogondonia.BIOME_KEY) }

    // Found chunks that still need a remesh, nearest first; [remeshCursor] is the next one. Client thread only.
    private val found = LongArrayList()
    private var remeshCursor = 0
    private var remeshLevel: ClientLevel? = null

    fun tick(mc: Minecraft) {
        val level = mc.level
        val player = mc.player
        if (level == null || player == null) {
            WohlonBiomePresence.nearby = false
            clearRemesh()
            return
        }
        drainRemesh(mc, level)
        if (level.gameTime % SCAN_INTERVAL_TICKS != 0L) return

        val radius = mc.options.effectiveRenderDistance
        val centre = player.chunkPosition()
        if (WohlonBiomePresence.nearby) {
            WohlonBiomePresence.nearby = scan(level, centre, radius, collect = false)
            if (!WohlonBiomePresence.nearby) clearRemesh()
            return
        }
        clearRemesh()
        if (!scan(level, centre, radius, collect = true)) return
        WohlonBiomePresence.nearby = true
        // These chunks were meshed while the flag was off, so their mesh has no light floor.
        val cx = centre.x
        val cz = centre.z
        LongArrays.quickSort(found.elements(), 0, found.size) { a, b ->
            Integer.compare(chebyshev(a, cx, cz), chebyshev(b, cx, cz))
        }
        remeshLevel = level
    }

    fun onDisconnect() {
        WohlonBiomePresence.nearby = false
        clearRemesh()
    }

    private fun drainRemesh(mc: Minecraft, level: ClientLevel) {
        if (remeshCursor >= found.size) return
        if (level !== remeshLevel) {
            clearRemesh()
            return
        }
        val end = minOf(found.size, remeshCursor + REMESH_CHUNKS_PER_TICK)
        while (remeshCursor < end) markDirty(mc, level, found.getLong(remeshCursor++))
        if (remeshCursor >= found.size) clearRemesh()
    }

    private fun clearRemesh() {
        found.clear()
        remeshCursor = 0
        remeshLevel = null
    }

    private fun chebyshev(packed: Long, cx: Int, cz: Int): Int =
        maxOf(Math.abs(ChunkPos.getX(packed) - cx), Math.abs(ChunkPos.getZ(packed) - cz))

    /** With [collect], each Wohlon chunk goes into [found]; without it, the first hit ends the scan. */
    private fun scan(level: ClientLevel, centre: ChunkPos, radius: Int, collect: Boolean): Boolean {
        var any = false
        for (cx in centre.x - radius..centre.x + radius) {
            for (cz in centre.z - radius..centre.z + radius) {
                val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
                if (!hasWohlon(chunk)) continue
                if (!collect) return true
                any = true
                found.add(ChunkPos.asLong(cx, cz))
            }
        }
        return any
    }

    // `maybeHas` reads the palette only, not the 64 cells of the section.
    private fun hasWohlon(chunk: LevelChunk): Boolean {
        for (section in chunk.sections) {
            if (section.biomes.maybeHas(IS_WOHLON)) return true
        }
        return false
    }

    private fun markDirty(mc: Minecraft, level: ClientLevel, packed: Long) {
        val cx = ChunkPos.getX(packed)
        val cz = ChunkPos.getZ(packed)
        for (sy in level.minSection until level.maxSection) {
            mc.levelRenderer.setSectionDirtyWithNeighbors(cx, sy, cz)
        }
    }
}
