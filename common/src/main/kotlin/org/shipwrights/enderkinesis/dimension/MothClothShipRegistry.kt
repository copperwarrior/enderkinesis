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
 * Cloth is placed on that ship. Block placement / removal hooks and a
 * periodic rescan keep the set current and copy it into the ship's saved
 * [MothClothForceInducer], which lifts the ship to Y = [TARGET_Y].
 *
 * World-placed moth cloth (not on any ship) is ignored — the lift only
 * makes sense on VS2 rigid bodies.
 */
object MothClothShipRegistry {

    private val byShip: ConcurrentHashMap<Long, MutableSet<Long>> = ConcurrentHashMap()

    fun add(level: ServerLevel, pos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(pos) as? LoadedServerShip ?: return
        val set = setFor(ship)
        set.add(pos.asLong())
        publish(ship, set)
    }

    fun remove(level: ServerLevel, pos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(pos) as? LoadedServerShip ?: return
        val set = setFor(ship)
        set.remove(pos.asLong())
        if (set.isEmpty()) {
            byShip.remove(ship.id)
            ship.removeAttachment(MothClothForceInducer::class.java)
        } else {
            publish(ship, set)
        }
    }

    /** After a load the registry is empty until the next rescan; seed it from the saved
     *  inducer, or an edit in that window would publish a partial set and drop the ship. */
    private fun setFor(ship: LoadedServerShip): MutableSet<Long> =
        byShip.computeIfAbsent(ship.id) {
            val set = ConcurrentSkipListSet<Long>()
            ship.getAttachment(MothClothForceInducer::class.java)?.positions?.forEach { set.add(it) }
            set
        }

    /** The inducer is saved with the ship, so it keeps its positions across a reload. */
    private fun publish(ship: LoadedServerShip, set: Set<Long>) {
        ship.getOrPutAttachment(MothClothForceInducer::class.java) { MothClothForceInducer() }
            .positions = set.toLongArray()
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
            var allLoaded = true
            for (cx in minCX..maxCX) for (cz in minCZ..maxCZ) {
                val chunk = level.getChunk(cx, cz, net.minecraft.world.level.chunk.ChunkStatus.FULL, false)
                if (chunk == null) {
                    allLoaded = false
                    continue
                }
                scanChunkInto(level, chunk, fresh)
            }
            // A partial scan after a load would replace the saved positions with fewer and drop the ship.
            if (!allLoaded) continue
            val shipId = ship.id
            if (fresh.isEmpty()) {
                byShip.remove(shipId)
                if (ship.getAttachment(MothClothForceInducer::class.java) != null) {
                    ship.removeAttachment(MothClothForceInducer::class.java)
                }
            } else {
                byShip[shipId] = fresh
                publish(ship, fresh)
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
    }
}
