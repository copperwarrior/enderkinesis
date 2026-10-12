package org.shipwrights.enderkinesis.remnant

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.entity.ConstriliarSurfaceField

/**
 * Feeds real collision geometry to the particle integrator.
 *
 * Wraps [ConstriliarSurfaceField] — the smoothed signed-distance field
 * already used for Constriliar surface-walking — because it does exactly
 * what the remnant needs: it reads the actual voxel collision shapes (so
 * slabs, stairs and fences are solid where they really are) and its
 * gradient rotates continuously around edges rather than snapping to axis
 * directions, which is what lets the mass drape smoothly over geometry.
 *
 * One instance per side; the level handle is swapped in before each tick so
 * the integrator itself stays level-agnostic and identical on client and
 * server.
 */
class RemnantSurfaceProbe : IrreverantRemnantMotion.SurfaceProbe {

    private var level: Level? = null

    /** Entity hulls the remnant treats as surface, refreshed once per tick.
     *  Gathered up front because a probe runs per particle per substep, and
     *  an entity query at each would cost far more than the collision does. */
    private val entityBoxes = ArrayList<AABB>(16)

    /** Collision boxes for one block cell, gathered one time for each tick. Up to 768
     *  particles probe up to 4 times each tick, and a mass sits in few cells; a gather for
     *  each probe did a block collision walk and a ship query each time. */
    private class CellBoxes(val range: Double, val boxes: List<AABB>)

    private val cells = Long2ObjectOpenHashMap<CellBoxes>()
    private val scratch = ConstriliarSurfaceField.SampleScratch()

    /** One time for each tick, before the probes: blocks and ships do not move inside it. */
    fun bind(level: Level) {
        this.level = level
        cells.clear()
    }

    /** Called when the level unloads, so a static owner does not keep it. */
    fun unbind() {
        level = null
        cells.clear()
        entityBoxes.clear()
    }

    /**
     * Rebuilds the entity surfaces the remnant can crawl on, over [bounds].
     *
     * Anything solid enough to stand on or bump into counts — living things
     * and the entities that carry real collision, like boats and minecarts.
     * Spectators are skipped, and so are the loose entities goo has no
     * business climbing.
     */
    fun refreshEntities(level: Level, bounds: AABB) {
        entityBoxes.clear()
        if (bounds.size <= 0.0) return
        for (entity in level.getEntities(null as Entity?, bounds, ::collidable)) {
            entityBoxes.add(entity.boundingBox)
            if (entityBoxes.size >= MAX_ENTITIES) break
        }
    }

    private fun collidable(entity: Entity): Boolean {
        if (!entity.isAlive || entity.isSpectator) return false
        return entity is LivingEntity || entity.canBeCollidedWith()
    }

    override fun probe(
        x: Double,
        y: Double,
        z: Double,
        range: Double,
        out: IrreverantRemnantMotion.Contact,
    ) {
        val world = level
        if (world == null) {
            out.valid = false
            return
        }
        val cellX = Math.floor(x).toInt()
        val cellY = Math.floor(y).toInt()
        val cellZ = Math.floor(z).toInt()
        val key = BlockPos.asLong(cellX, cellY, cellZ)
        var cell = cells.get(key)
        if (cell == null || cell.range < range) {
            // The cell centre with half a block more reach covers each point of the cell.
            val boxes = ConstriliarSurfaceField.boxesNear(
                world, Vec3(cellX + 0.5, cellY + 0.5, cellZ + 0.5), range + 0.5, MAX_BOXES,
                if (entityBoxes.isEmpty()) null else entityBoxes,
            )
            cell = CellBoxes(range, boxes)
            cells.put(key, cell)
        }
        // A box from the wider gather can be past [range]; the caller tests the distance.
        if (!ConstriliarSurfaceField.sampleBoxes(cell.boxes, x, y, z, SMOOTHING, scratch)) {
            out.valid = false
            return
        }
        out.valid = true
        // The RAW nearest-surface distance, not the smoothed one. Smooth-min
        // reads short wherever several boxes contribute — about a quarter of
        // a block over an ordinary flat floor — and contact resolution reads
        // that as penetration and shoves the particle up by exactly that
        // much. That was the levitation. The normal below stays smoothed.
        out.distance = scratch.rawDistance
        out.nx = scratch.up.x
        out.ny = scratch.up.y
        out.nz = scratch.up.z
    }

    private companion object {
        /** Log-sum-exp sharpness. Softer than the Constriliar default so
         *  block edges round off generously — the remnant should drape, not
         *  crease. */
        private const val SMOOTHING = 4.0

        /** Particles are small, so only a handful of boxes are ever in
         *  range; this is a safety bound, not a working limit. */
        private const val MAX_BOXES = 128

        /** Cap on entity surfaces per tick — a crowd cannot turn one probe
         *  into an unbounded box list (Rule 2). */
        private const val MAX_ENTITIES = 32
    }
}
