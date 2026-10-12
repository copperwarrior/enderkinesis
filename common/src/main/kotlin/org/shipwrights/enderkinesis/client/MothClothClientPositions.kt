package org.shipwrights.enderkinesis.client

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.chunk.LevelChunk
import org.shipwrights.enderkinesis.block.MothClothBlock
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Client-side registry of every Moth Cloth block position currently visible
 * in the ClientLevel — world blocks AND ship-mounted blocks (which are in
 * shipyard-coord chunks that VS2 renders through a per-ship transform).
 *
 * Populated by:
 *   - Chunk scanning on load (via ClientChunkCache mixin — invoked whenever a
 *     new chunk arrives from the server).
 *   - Per-block updates from LevelChunk.setBlockState (via mixin).
 *
 * Read by [MothClothOverlayRenderer] once per frame to draw the glint layer.
 *
 * Positions are packed as longs to avoid the per-entry object churn we'd
 * otherwise pay iterating tens of thousands of blocks in a heavily-clothed
 * level.
 */
object MothClothClientPositions {

    private val positions = LongOpenHashSet(64)

    @JvmStatic
    fun add(pos: BlockPos) {
        synchronized(positions) { positions.add(pos.asLong()) }
    }

    @JvmStatic
    fun remove(pos: BlockPos) {
        synchronized(positions) { positions.remove(pos.asLong()) }
    }

    @JvmStatic
    fun clear() {
        synchronized(positions) { positions.clear() }
    }

    /** Iterate visible positions. The [action] receives a mutable BlockPos —
     *  do not retain it across iterations. */
    @JvmStatic
    fun forEach(action: (BlockPos.MutableBlockPos) -> Unit) {
        val scratch = BlockPos.MutableBlockPos()
        val snapshot = synchronized(positions) { positions.toLongArray() }
        for (packed in snapshot) {
            scratch.set(
                BlockPos.getX(packed),
                BlockPos.getY(packed),
                BlockPos.getZ(packed),
            )
            action(scratch)
        }
    }

    @JvmStatic
    fun size(): Int = synchronized(positions) { positions.size }

    /** Palette-gated section scan. Called on chunk load to seed the
     *  registry with pre-existing moth cloth blocks. */
    @JvmStatic
    fun scanChunk(chunk: LevelChunk) {
        val found = LongOpenHashSet()
        scanChunkInto(chunk, found)
        if (found.isEmpty()) return
        synchronized(positions) { positions.addAll(found) }
    }

    private fun scanChunkInto(chunk: LevelChunk, out: LongOpenHashSet) {
        val sections = chunk.sections
        val chunkPos = chunk.pos
        for (sectionIndex in sections.indices) {
            val section = sections[sectionIndex] ?: continue
            if (section.hasOnlyAir()) continue
            if (!section.maybeHas { it.block is MothClothBlock }) continue
            val yOrigin = (chunk.getSectionYFromSectionIndex(sectionIndex) shl 4)
            for (ly in 0 until 16) for (lz in 0 until 16) for (lx in 0 until 16) {
                val state = section.getBlockState(lx, ly, lz)
                if (state.block !is MothClothBlock) continue
                out.add(BlockPos.asLong(chunkPos.getBlockX(lx), yOrigin + ly, chunkPos.getBlockZ(lz)))
            }
        }
    }

    /** VS2 cancels `ClientChunkCache.replaceWithPacketData` at HEAD for
     *  shipyard chunks and stores them through its own path, so the
     *  chunk-load scan mixin never sees ship chunks. Instead we rescan
     *  ship chunks here, every [SHIP_RESCAN_MS], by walking each loaded
     *  ship's shipyard-space block AABB (a handful of chunks per ship,
     *  palette-gated — cheap). Positions found by the previous ship scan
     *  are replaced wholesale so broken/unloaded ship blocks age out;
     *  live placement/breaking is additionally caught instantly by the
     *  LevelChunk.setBlockState mixin. */
    @JvmStatic
    fun rescanShips(level: ClientLevel) {
        val now = System.currentTimeMillis()
        if (now - lastShipScanMs < SHIP_RESCAN_MS) return
        lastShipScanMs = now
        val fresh = LongOpenHashSet()
        for (ship in level.shipObjectWorld.loadedShips) {
            val aabb = ship.shipAABB ?: continue
            val minCX = aabb.minX() shr 4
            val maxCX = aabb.maxX() shr 4
            val minCZ = aabb.minZ() shr 4
            val maxCZ = aabb.maxZ() shr 4
            for (cx in minCX..maxCX) for (cz in minCZ..maxCZ) {
                val chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
                scanChunkInto(chunk, fresh)
            }
        }
        synchronized(positions) {
            positions.removeAll(lastShipScanResult)
            positions.addAll(fresh)
        }
        lastShipScanResult = fresh
    }

    private var lastShipScanMs = 0L
    private var lastShipScanResult = LongOpenHashSet()
    private const val SHIP_RESCAN_MS = 2000L

    /** Remove every position that lies inside the given chunk. Called on
     *  chunk unload so the registry doesn't grow unbounded. */
    @JvmStatic
    fun forgetChunk(chunkX: Int, chunkZ: Int) {
        val minX = chunkX shl 4
        val minZ = chunkZ shl 4
        val maxX = minX + 15
        val maxZ = minZ + 15
        synchronized(positions) {
            val iterator = positions.iterator()
            while (iterator.hasNext()) {
                val packed = iterator.nextLong()
                val x = BlockPos.getX(packed)
                val z = BlockPos.getZ(packed)
                if (x in minX..maxX && z in minZ..maxZ) iterator.remove()
            }
        }
    }
}
