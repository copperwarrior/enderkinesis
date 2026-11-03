package org.shipwrights.enderkinesis.worldgen

import org.shipwrights.enderkinesis.compat.distanthorizons.DistantHorizonsCompat
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.joml.Vector3dc

/** A shipify processor whose template entities spawn in the shipyard, never in the world. */
interface CapturesCrew

/** Crew that keeps world block positions from its template. ShipMoves skips world-to-ship
 *  assembly, so [ShipCrew] hands over the assembly offset instead. */
interface ShipyardCrew {
    fun onAssembledToShipyard(offset: BlockPos)
}

/** Entities of a generated structure that must ride the ship its blocks became. VS2 assembly
 *  moves blocks only, so a crew left in the world stays at the generation site. */
object ShipCrew {
    // Worldgen runs on worker threads, several at once under C2ME.
    private val capturingFor = ThreadLocal<BlockPos?>()
    private val captured = ConcurrentHashMap<BlockPos, ConcurrentLinkedQueue<Entity>>()

    /** Called by StructureTemplateCrewCaptureMixin at the start of every `placeInWorld`. */
    @JvmStatic
    fun beginCapture(settings: StructurePlaceSettings, origin: BlockPos) {
        // DH LOD passes never assemble, so their crew would sit here and later double the real crew.
        val captures = settings.processors.any { it is CapturesCrew } && !DistantHorizonsCompat.isLodGeneration()
        if (captures) capturingFor.set(origin.immutable()) else capturingFor.remove()
    }

    @JvmStatic
    fun endCapture() = capturingFor.remove()

    /** Called by WorldGenRegionCrewCaptureMixin. True if [entity] is kept for the shipyard. */
    @JvmStatic
    fun capture(entity: Entity): Boolean {
        val origin = capturingFor.get() ?: return false
        captured.computeIfAbsent(origin) { ConcurrentLinkedQueue() }.add(entity)
        return true
    }

    /** The captured crew of the template placed at [origin], in world coordinates. */
    fun takeCaptured(origin: BlockPos): List<Entity> = captured.remove(origin)?.toList().orEmpty()

    /** Adds each captured entity at its world position + [offset] (shipyard coordinates). */
    fun spawnInShipyard(server: ServerLevel, crew: List<Entity>, offset: BlockPos, label: String) {
        for (entity in crew) {
            (entity as? ShipyardCrew)?.onAssembledToShipyard(offset)
            // moveTo, NOT teleportTo — VS2's teleport mixins assume world-space destinations.
            entity.moveTo(entity.x + offset.x, entity.y + offset.y, entity.z + offset.z, entity.yRot, entity.xRot)
            if (!server.tryAddFreshEntityWithPassengers(entity)) LOG.warn("[EK] {}: {} did not spawn", label, entity.type)
        }
    }

    /** The world-to-shipyard block translation of an assembly of [blocks]. VS2 adds the
     *  center-of-mass offset to `AssembleContext.fromCenter` in place, so only `toCenter` is safe. */
    fun assemblyOffset(blocks: Set<BlockPos>, toCenter: Vector3dc): BlockPos {
        require(blocks.isNotEmpty()) { "assembly has no blocks" }
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (p in blocks) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z)
        }
        return BlockPos(
            Math.round(toCenter.x() - (minX + maxX + 1) / 2.0).toInt(),
            Math.round(toCenter.y() - (minY + maxY + 1) / 2.0).toInt(),
            Math.round(toCenter.z() - (minZ + maxZ + 1) / 2.0).toInt(),
        )
    }

    private val LOG = com.mojang.logging.LogUtils.getLogger()
}
