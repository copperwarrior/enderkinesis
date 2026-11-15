package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.valkyrienskies.core.api.ships.ServerShip
import kotlin.math.abs

/** What the walker carries, measured from its plate in the ship's own grid: how far it
 *  reaches on each layer and how high, for the planner. Contact comes from VS2 (ConstriliarContacts). */
internal class ConstriliarCargoShape {

    /** Furthest cell the load fills from the plate's column across the plate (blocks):
     *  the planner works on whole cells, so the load is sized exactly, never rounded up. */
    var radius = 0
        private set

    /** Layers the body and its load fill above the surface; a bare body keeps one layer
     *  of headroom, as it always had. */
    var height = MIN_HEIGHT
        private set

    /** A load block fills the cell straight above the plate: the avoidance rays scan it. */
    var blockAbove = false
        private set

    // Chebyshev reach per layer up from the plate; -1 when the layer holds no load block.
    // A chebyshev square is the same at every quarter turn, and the planner does not know the yaw.
    private val layerReach = IntArray(MAX_LAYERS)
    private var top = 0

    private var scanned = false
    private var minX = 0
    private var minY = 0
    private var minZ = 0
    private var maxX = 0
    private var maxY = 0
    private var maxZ = 0
    private var massBits = 0L

    /** The body's blocks (plate and load) as packed shipyard positions; the first [cellCount] count. */
    val cells = LongArray(MAX_CELLS)
    var cellCount = 0
        private set

    /** Load reach on [layer] (0 = the plate's own layer), at most [MAX_REACH]: -1 there for no load beside the
     *  plate, and at least 0 (the plate's column) on every layer above it, up to [height]. */
    fun radiusAt(layer: Int): Int {
        require(layer >= 0) { "Layer must not be negative: $layer" }
        if (layer >= height) return -1
        val reach = minOf(if (layer < MAX_LAYERS) layerReach[layer] else -1, MAX_REACH)
        return if (layer == 0) reach else maxOf(reach, 0)
    }

    /** Rescans when the ship's blocks change (its box or mass). */
    fun update(level: ServerLevel, ship: ServerShip, platform: BlockPos) {
        val box = ship.shipAABB ?: return
        val mass = ship.inertiaData.mass.toRawBits()
        val sameMin = box.minX() == minX && box.minY() == minY && box.minZ() == minZ
        val sameMax = box.maxX() == maxX && box.maxY() == maxY && box.maxZ() == maxZ
        if (scanned && sameMin && sameMax && mass == massBits) return
        scanned = true
        minX = box.minX(); minY = box.minY(); minZ = box.minZ()
        maxX = box.maxX(); maxY = box.maxY(); maxZ = box.maxZ()
        massBits = mass
        begin()
        ConstriliarCargo.forEachShipBlock(level, ship, SCAN_BUDGET) { pos, _ ->
            if (cellCount < MAX_CELLS) cells[cellCount++] = pos.asLong()
            if (pos != platform) add(pos.x - platform.x, pos.y - platform.y, pos.z - platform.z)
        }
        finish()
    }

    fun begin() {
        cellCount = 0
        layerReach.fill(-1)
        top = 0
        blockAbove = false
    }

    /** One load block at ([dx], [dy], [dz]) from the plate, in the ship's grid. */
    fun add(dx: Int, dy: Int, dz: Int) {
        if (dy < 0) return
        val reach = maxOf(abs(dx), abs(dz))
        val layer = minOf(dy, MAX_LAYERS - 1)
        layerReach[layer] = maxOf(layerReach[layer], reach)
        top = maxOf(top, dy)
        if (dx == 0 && dy == 1 && dz == 0) blockAbove = true
    }

    fun finish() {
        height = maxOf(MIN_HEIGHT, top + 1)
        var reach = 0
        for (r in layerReach) reach = maxOf(reach, r)
        radius = minOf(reach, MAX_REACH)
    }

    companion object {
        private const val SCAN_BUDGET = 4096
        private const val MIN_HEIGHT = 2
        private const val MAX_LAYERS = 8
        private const val MAX_CELLS = 64

        /** Largest load reach (blocks across) and height (layers up) every clearance check allows for. */
        const val MAX_REACH = 4
        const val MAX_HEIGHT = 5
    }
}
