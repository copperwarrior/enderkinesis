package org.shipwrights.enderkinesis.entity

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import org.joml.Vector3dc
import java.util.Locale

/** Walker diagnostics for playtests: one `[EK walker]` block a second, and a `SPIKE`
 *  block at once when the body moves or spins fast. Turn off with [ENABLED]. */
internal object ConstriliarDebugLog {

    const val ENABLED = false
    const val INTERVAL_TICKS = 20

    /** Body speed (m/s) or spin (rad/s) that logs at once, at most every [SPIKE_GAP_TICKS]. */
    const val SPIKE_SPEED = 8.0
    const val SPIKE_OMEGA = 3.0
    const val SPIKE_GAP_TICKS = 5

    private const val MAP_RADIUS = 3
    private const val MAP_SCAN = 4
    private const val MAP_TOP = 2

    private val LOG = LogUtils.getLogger()

    fun write(lines: List<String>) {
        LOG.info(lines.joinToString("\n  ", prefix = "[EK walker] "))
    }

    fun f(v: Double): String = String.format(Locale.ROOT, "%.2f", v)

    fun v(x: Double, y: Double, z: Double): String = "(${f(x)}, ${f(y)}, ${f(z)})"

    fun v(p: Vector3dc): String = v(p.x(), p.y(), p.z())

    fun v(p: Vec3): String = v(p.x, p.y, p.z)

    fun block(level: ServerLevel, x: Double, y: Double, z: Double): String {
        val pos = BlockPos.containing(x, y, z)
        val id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).path
        return "$id@${pos.x},${pos.y},${pos.z}"
    }

    /**
     * World blocks only (no VS2 ships), with the walker's physics collision. Rows run
     * north to south, columns west to east. Each cell is the highest solid block's Y
     * offset from the brain's block, scanned from +[MAP_TOP] so a canopy does not hide
     * the ground; `.` is none, a trailing `~` a fluid, and `[ ]` the brain's cell.
     */
    fun heightMap(level: ServerLevel, centre: Vec3): List<String> {
        val base = BlockPos.containing(centre)
        val cursor = BlockPos.MutableBlockPos()
        val rows = ArrayList<String>(2 * MAP_RADIUS + 2)
        rows.add("ground (N up, W left, rel to y=${base.y}):")
        for (dz in -MAP_RADIUS..MAP_RADIUS) {
            val row = StringBuilder()
            for (dx in -MAP_RADIUS..MAP_RADIUS) {
                val cell = topCell(level, cursor, base.x + dx, base.y, base.z + dz)
                row.append(if (dx == 0 && dz == 0) "[$cell]" else " $cell ")
            }
            rows.add(row.toString())
        }
        return rows
    }

    private fun topCell(level: ServerLevel, cursor: BlockPos.MutableBlockPos, x: Int, baseY: Int, z: Int): String {
        for (dy in MAP_TOP downTo -MAP_SCAN) {
            cursor.set(x, baseY + dy, z)
            val state = level.getBlockState(cursor)
            val fluid = !state.fluidState.isEmpty
            if (fluid || (!state.getCollisionShape(level, cursor).isEmpty && !ConstriliarCollision.shipPassable(state))) {
                val n = if (dy >= 0) "+$dy" else "$dy"
                return if (fluid) "$n~" else "$n "
            }
        }
        return " . "
    }
}
