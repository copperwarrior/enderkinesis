package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.joml.Vector3d
import org.shipwrights.enderkinesis.body.ClientOrbRegistry
import org.shipwrights.enderkinesis.body.OrbDynamicLightMap
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Per-client-tick publisher for [OrbDynamicLightMap] — the [MAX_ORBS] closest active orbs
 * within [MAX_RANGE], read by [BlockLightEngineOrbDynamicLightMixin] and
 * [WorldSliceOrbDynamicLightMixin].
 *
 * Section invalidation must be explicit (`setSectionDirtyWithNeighbors`) when an orb
 * crosses a block boundary or enters/leaves the active set — vanilla's `onLightUpdate`
 * chain only fires from the light-engine BFS, and our hook bypasses that engine.
 */
object OrbDynamicLightDriver {

    /** Block-light level injected at each active orb's position. 13
     *  matches a bright torch (vanilla torches are 14); slightly
     *  lower so the orb's glow stays distinct from "this room has
     *  a torch". */
    private const val LIGHT_LEVEL: Int = 13

    /** Max simultaneous emitting orbs. Sselith clusters can produce
     *  dozens within a chunk; the closest [MAX_ORBS] to the camera
     *  win. */
    private const val MAX_ORBS: Int = 8

    /** Camera → orb distance beyond which an orb contributes nothing.
     *  Matches the visible reach of a [LIGHT_LEVEL] emitter (light
     *  fades to 0 at Manhattan distance == [LIGHT_LEVEL]). */
    private const val MAX_RANGE: Double = 32.0
    private const val MAX_RANGE_SQR: Double = MAX_RANGE * MAX_RANGE

    /** bodyId → packedPos most-recently emitted from. Used to detect
     *  cross-block moves so the old position's section can be
     *  invalidated alongside the new one. */
    private val lastBodyPos: HashMap<Long, Long> = HashMap()

    @JvmStatic
    fun tick(mc: Minecraft) {
        val level = mc.level ?: run {
            if (lastBodyPos.isNotEmpty()) {
                lastBodyPos.clear()
                OrbDynamicLightMap.setActive(emptyList(), LIGHT_LEVEL)
            }
            return
        }

        val cam = mc.gameRenderer.mainCamera.position
        val cx = cam.x; val cy = cam.y; val cz = cam.z

        // Collect candidate orbs within range, sorted by distance.
        val orbs = ClientOrbRegistry.snapshot()
        val candidates = ArrayList<LongArray>(orbs.size)  // bodyId, packedPos, d² bits
        val scratch = Vector3d()
        // Same dim guard as [OrbOfPotentialRenderer] — VS2's allBodies
        // map spans every dim the client has loaded, so a Sselith orb
        // can still be looked up after the player teleports out. Without
        // this check, dynamic light bleeds into the overworld at the
        // same XYZ the Sselith orb occupies.
        val currentDimId = level.dimensionId
        for (bodyId in orbs) {
            if (bodyId == 0L) continue
            val body = level.shipObjectWorld.allBodies.getById(bodyId) as? ClientVsBody ?: continue
            if (body.dimension != currentDimId) continue
            val pos = body.renderTransform.toWorld.transformPosition(scratch.set(0.0, 0.0, 0.0))
            val dx = pos.x - cx; val dy = pos.y - cy; val dz = pos.z - cz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > MAX_RANGE_SQR) continue
            val bp = BlockPos.containing(pos.x, pos.y, pos.z).asLong()
            candidates += longArrayOf(bodyId, bp, java.lang.Double.doubleToRawLongBits(d2))
        }
        candidates.sortBy { java.lang.Double.longBitsToDouble(it[2]) }
        val selected = if (candidates.size <= MAX_ORBS) candidates
                       else candidates.subList(0, MAX_ORBS)

        // Compute the new active emission set + collect positions
        // whose surrounding sections need re-mesh (orb entered or
        // left this position, or moved across a block boundary).
        val newActive = ArrayList<Long>(selected.size)
        val needsRemesh = HashSet<Long>(selected.size * 2)
        val selectedIds = HashSet<Long>(selected.size)
        for (c in selected) {
            val bodyId = c[0]
            val packedPos = c[1]
            selectedIds += bodyId
            newActive += packedPos
            val prev = lastBodyPos.put(bodyId, packedPos)
            if (prev == null) {
                needsRemesh += packedPos
            } else if (prev != packedPos) {
                needsRemesh += prev
                needsRemesh += packedPos
            }
        }
        // Bodies that were active last tick but no longer in selected.
        val iter = lastBodyPos.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in selectedIds) {
                needsRemesh += e.value
                iter.remove()
            }
        }

        OrbDynamicLightMap.setActive(newActive, LIGHT_LEVEL)

        // Force re-mesh for affected sections. `setSectionDirtyWithNeighbors`
        // invalidates the 3x3x3 sections around the given section
        // — for a `LIGHT_LEVEL` of 13, the contribution radius is
        // 13 blocks ≈ one section in each direction, so the 3x3x3
        // window covers the whole visible glow.
        val levelRenderer = mc.levelRenderer
        for (packed in needsRemesh) {
            val sx = BlockPos.getX(packed) shr 4
            val sy = BlockPos.getY(packed) shr 4
            val sz = BlockPos.getZ(packed) shr 4
            levelRenderer.setSectionDirtyWithNeighbors(sx, sy, sz)
        }
    }
}
