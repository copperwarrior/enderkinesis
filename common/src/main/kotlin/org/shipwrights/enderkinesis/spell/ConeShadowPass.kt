package org.shipwrights.enderkinesis.spell

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.Ship
import kotlin.math.abs
import kotlin.math.floor

/**
 * Line of sight from [origin] (world) to cells of the world grid and of each ship grid, in one outward pass for each frame.
 * A cell is lit when the next cell toward the origin is lit and has no collision shape, in its own frame and in the
 * other frame at that point. Each cell is resolved one time, so a cone costs about one block read for each cell.
 */
class ConeShadowPass(private val level: Level, private val origin: Vec3, private val ships: List<Ship>) {
    private val memo = HashMap<Ship?, Long2ByteOpenHashMap>()
    private val origins = HashMap<Ship?, Vector3d>()
    private val chain = LongArrayList()
    private val cell = BlockPos.MutableBlockPos()
    private val point = Vector3d()
    private var resolved = 0

    /** True when [pos], a cell of [ship] (null is the world), sees the origin. */
    fun lit(pos: BlockPos, ship: Ship?): Boolean {
        val map = memo.getOrPut(ship) { Long2ByteOpenHashMap() }
        val o = origins.getOrPut(ship) {
            Vector3d(origin.x, origin.y, origin.z).also { ship?.transform?.worldToShip?.transformPosition(it) }
        }
        val ox = floor(o.x).toInt(); val oy = floor(o.y).toInt(); val oz = floor(o.z).toInt()
        chain.clear()
        var x = pos.x; var y = pos.y; var z = pos.z
        var state: Byte
        while (true) {
            if (x == ox && y == oy && z == oz) { state = LIT; break }
            val known = map.get(BlockPos.asLong(x, y, z))
            if (known != UNKNOWN) { state = known; break }
            if (resolved + chain.size >= MAX_RESOLVED) { state = DARK; break }
            chain.add(BlockPos.asLong(x, y, z))
            // One step toward the origin cell on the axis with the largest gap that still differs, so the walk ends.
            val dx = o.x - (x + 0.5); val dy = o.y - (y + 0.5); val dz = o.z - (z + 0.5)
            val ax = if (x != ox) abs(dx) else -1.0
            val ay = if (y != oy) abs(dy) else -1.0
            val az = if (z != oz) abs(dz) else -1.0
            when {
                ax >= ay && ax >= az -> x += if (dx > 0) 1 else -1
                ay >= az -> y += if (dy > 0) 1 else -1
                else -> z += if (dz > 0) 1 else -1
            }
        }
        var predLit = state == LIT
        var px = x; var py = y; var pz = z
        for (i in chain.size - 1 downTo 0) {
            val k = chain.getLong(i)
            val lit = predLit && (px == ox && py == oy && pz == oz || passes(px, py, pz, ship))
            map.put(k, if (lit) LIT else DARK)
            resolved++
            predLit = lit
            px = BlockPos.getX(k); py = BlockPos.getY(k); pz = BlockPos.getZ(k)
        }
        return predLit
    }

    private fun passes(x: Int, y: Int, z: Int, ship: Ship?): Boolean {
        if (!open(cell.set(x, y, z))) return false
        point.set(x + 0.5, y + 0.5, z + 0.5)
        if (ship != null) {
            ship.transform.shipToWorld.transformPosition(point)
            return open(cell.set(point.x, point.y, point.z))
        }
        for (other in ships) {
            other.transform.worldToShip.transformPosition(x + 0.5, y + 0.5, z + 0.5, point)
            if (!open(cell.set(point.x, point.y, point.z))) return false
        }
        return true
    }

    // An unloaded cell blocks, so the pass never loads a chunk.
    private fun open(at: BlockPos): Boolean = level.isLoaded(at) && level.getBlockState(at).getCollisionShape(level, at).isEmpty

    private companion object {
        const val UNKNOWN: Byte = 0
        const val LIT: Byte = 1
        const val DARK: Byte = 2
        const val MAX_RESOLVED = 262_144
    }
}
