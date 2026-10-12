package org.shipwrights.enderkinesis.dimension

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import org.shipwrights.enderkinesis.physics.MothClothForceInducer
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.dimensionId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import net.minecraft.core.BlockPos

/**
 * Server-thread registry mapping ship id → block positions where a Moth
 * Cloth is placed on that ship. Block class's placement / removal hooks
 * keep the set current; the per-ship [MothClothForceInducer] reads the
 * count each physics tick and applies constant upward force per block
 * until the ship reaches Y = [TARGET_Y].
 *
 * World-placed moth cloth (not on any ship) is ignored — the lift only
 * makes sense on VS2 rigid bodies.
 */
object MothClothShipRegistry {

    /** shipId → set of block positions (packed longs). ConcurrentSkipListSet
     *  keeps values ordered so serialization is deterministic; the physics
     *  thread only reads the SIZE, not the members, so we avoid any locking
     *  concerns beyond what ConcurrentHashMap gives. */
    private val byShip: ConcurrentHashMap<Long, MutableSet<Long>> = ConcurrentHashMap()

    fun add(level: ServerLevel, pos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(pos)
        if (ship == null) {
            return
        }
        val shipId = ship.id
        val posLong = pos.asLong()
        byShip.computeIfAbsent(shipId) { ConcurrentSkipListSet() }.add(posLong)
        refreshSnapshot(shipId)
        val loaded = ship as? LoadedServerShip
        ensureInducer(loaded)
    }

    fun remove(level: ServerLevel, pos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(pos) ?: return
        val shipId = ship.id
        val posLong = pos.asLong()
        val set = byShip[shipId] ?: return
        set.remove(posLong)
        if (set.isEmpty()) {
            byShip.remove(shipId)
            snapshots.remove(shipId)
            (ship as? LoadedServerShip)?.removeAttachment(MothClothForceInducer::class.java)
        } else {
            refreshSnapshot(shipId)
        }
    }

    /** Called by [MothClothForceInducer] on the physics thread to size the
     *  per-tick force. Safe from any thread. */
    @JvmStatic
    fun countFor(shipId: Long): Int = byShip[shipId]?.size ?: 0

    /** Packed block positions for a ship, as an immutable snapshot the
     *  physics thread can iterate allocation-free. Rebuilt only on block
     *  add/remove (game thread), so reads never see a half-updated set. */
    @JvmStatic
    fun positionsFor(shipId: Long): LongArray = snapshots[shipId] ?: EMPTY

    private fun refreshSnapshot(shipId: Long) {
        val set = byShip[shipId] ?: return
        snapshots[shipId] = set.toLongArray()
    }

    private val snapshots = ConcurrentHashMap<Long, LongArray>()
    private val EMPTY = LongArray(0)

    private fun ensureInducer(ship: LoadedServerShip?) {
        if (ship == null) return
        if (ship.getAttachment(MothClothForceInducer::class.java) != null) return
        ship.getOrPutAttachment(MothClothForceInducer::class.java) {
            MothClothForceInducer(ship.id)
        }
    }


    /** Absolute Y the ship is pulled to. Matches the moths' own altitude. */
    const val TARGET_Y: Double = 134.5

    /** Periodic rescan of every loaded ship's shipyard chunks. This is the
     *  ground-truth path: it catches cloth that arrived WITHOUT a block
     *  placement — ship assembly copies world blocks into shipyard chunks
     *  directly (no onPlace), ship splitting moves them between ships, and
     *  save loads restore them silently. onPlace/onRemove remain as the
     *  instant path between rescans. Called once from mod init. */
    fun init() {
        dev.architectury.event.events.common.TickEvent.SERVER_LEVEL_POST.register { level ->
            if (level.gameTime % RESCAN_INTERVAL_TICKS != 0L) return@register
            rescanShips(level)
        }
    }

    private fun rescanShips(level: ServerLevel) {
        val dimension = level.dimensionId
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimension) continue
            val aabb = ship.shipAABB ?: continue
            val fresh = ConcurrentSkipListSet<Long>()
            val minCX = aabb.minX() shr 4
            val maxCX = aabb.maxX() shr 4
            val minCZ = aabb.minZ() shr 4
            val maxCZ = aabb.maxZ() shr 4
            for (cx in minCX..maxCX) for (cz in minCZ..maxCZ) {
                val chunk = level.getChunk(cx, cz, net.minecraft.world.level.chunk.ChunkStatus.FULL, false)
                    ?: continue
                scanChunkInto(level, chunk, fresh)
            }
            val shipId = ship.id
            if (fresh.isEmpty()) {
                if (byShip.remove(shipId) != null) {
                    snapshots.remove(shipId)
                    ship.removeAttachment(MothClothForceInducer::class.java)
                }
            } else {
                byShip[shipId] = fresh
                snapshots[shipId] = fresh.toLongArray()
                ensureInducer(ship)
            }
        }
    }

    /** Palette-gated section scan. Sections without cloth pay just the
     *  palette predicate — cheap. */
    private fun scanChunkInto(
        level: ServerLevel,
        chunk: net.minecraft.world.level.chunk.ChunkAccess,
        out: MutableSet<Long>,
    ) {
        val chunkPos = chunk.pos
        val sections = chunk.sections
        for (sectionIndex in sections.indices) {
            val section = sections[sectionIndex] ?: continue
            if (section.hasOnlyAir()) continue
            if (!section.maybeHas { it.block is org.shipwrights.enderkinesis.block.MothClothBlock }) continue
            val yOrigin = level.getSectionYFromSectionIndex(sectionIndex) shl 4
            for (ly in 0 until 16) for (lz in 0 until 16) for (lx in 0 until 16) {
                val state = section.getBlockState(lx, ly, lz)
                if (state.block !is org.shipwrights.enderkinesis.block.MothClothBlock) continue
                out.add(BlockPos.asLong(chunkPos.getBlockX(lx), yOrigin + ly, chunkPos.getBlockZ(lz)))
            }
        }
    }

    /** 2 s at 20 TPS — matches the client-side overlay rescan cadence. */
    private const val RESCAN_INTERVAL_TICKS = 40L

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        byShip.clear()
        snapshots.clear()
    }
}
