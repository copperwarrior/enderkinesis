package org.shipwrights.enderkinesis.worldgen

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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
    private val LOG = com.mojang.logging.LogUtils.getLogger()

    /** Every non-player entity in the structure's region, with its world position now. */
    fun snapshot(server: ServerLevel, blocks: Set<BlockPos>): List<Pair<Entity, Vec3>> =
        server.getEntitiesOfClass(Entity::class.java, regionOf(blocks)) { it !is Player && it.isAlive }
            .map { it to Vec3(it.x, it.y, it.z) }

    // Worldgen runs on worker threads, several at once under C2ME.
    private val capturingFor = ThreadLocal<BlockPos?>()
    private val captured = ConcurrentHashMap<BlockPos, ConcurrentLinkedQueue<Entity>>()

    /** Called by StructureTemplateCrewCaptureMixin at the start of every `placeInWorld`. */
    @JvmStatic
    fun beginCapture(settings: StructurePlaceSettings, origin: BlockPos) {
        if (settings.processors.any { it is CapturesCrew }) capturingFor.set(origin.immutable()) else capturingFor.remove()
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
        var spawned = 0
        for (entity in crew) {
            (entity as? ShipyardCrew)?.onAssembledToShipyard(offset)
            // moveTo, NOT teleportTo — VS2's teleport mixins assume world-space destinations.
            entity.moveTo(entity.x + offset.x, entity.y + offset.y, entity.z + offset.z, entity.yRot, entity.xRot)
            if (server.tryAddFreshEntityWithPassengers(entity)) spawned++
        }
    }

    private fun regionOf(blocks: Set<BlockPos>): AABB {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (p in blocks) {
            minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z)
        }
        return AABB(
            minX.toDouble(), minY.toDouble(), minZ.toDouble(),
            maxX + 1.0, maxY + 2.0, maxZ + 1.0,
        ).inflate(1.0)
    }

    /** Moves each entity to its shipyard position. */
    fun relocate(placements: List<Pair<Entity, Vec3>>, label: String) {
        var relocated = 0
        for ((e, p) in placements) {
            if (!e.isAlive) continue
            try {
                // moveTo, NOT teleportTo — VS2's teleport mixins assume
                // world-space destinations and mangle shipyard coordinates.
                e.moveTo(p.x, p.y, p.z, e.yRot, e.xRot)
                e.setDeltaMovement(0.0, 0.0, 0.0)
                relocated++
            } catch (t: Throwable) {
                LOG.warn("[EK] {}: failed to relocate {} — discarding it", label, e.type, t)
                e.discard()
            }
        }
    }
}
