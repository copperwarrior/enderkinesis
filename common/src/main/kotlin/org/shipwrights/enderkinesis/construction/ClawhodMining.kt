package org.shipwrights.enderkinesis.construction

import com.mojang.authlib.GameProfile
import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameRules
import org.joml.Vector3d
import org.valkyrienskies.mod.common.getShipManagingPos
import java.util.UUID

private const val DROP_DRIFT = 0.005
private const val ENDER_BURST = 12
private const val ENDER_SPREAD = 0.25
private const val ENDER_SPEED = 0.5

// Half the 0.25 item hitbox, so the item floats at the cell centre.
private const val ITEM_HALF_HEIGHT = 0.125

/**
 * The user's rule: a Clawhod's mined or cut drops float at the cell with no gravity, in ender particles, until a
 * hand takes them. Obeys doTileDrops as Block.popResource does.
 */
internal fun popFloating(level: ServerLevel, pos: BlockPos, stack: ItemStack) {
    if (stack.isEmpty || !level.gameRules.getBoolean(GameRules.RULE_DOBLOCKDROPS)) return
    val at = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
    level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(at)
    val r = level.random
    val item = ItemEntity(
        level, at.x, at.y - ITEM_HALF_HEIGHT, at.z, stack,
        (r.nextDouble() - 0.5) * 2 * DROP_DRIFT, (r.nextDouble() - 0.5) * 2 * DROP_DRIFT, (r.nextDouble() - 0.5) * 2 * DROP_DRIFT,
    )
    item.isNoGravity = true
    item.setDefaultPickUpDelay()
    level.addFreshEntity(item)
    level.sendParticles(ParticleTypes.PORTAL, at.x, at.y, at.z, ENDER_BURST, ENDER_SPREAD, ENDER_SPREAD, ENDER_SPREAD, ENDER_SPEED)
}

/** True when the cell centre offset ([dx], [dy], [dz]) from the gem lies on the facing side ([fx], [fy], [fz]). */
internal fun inFront(dx: Double, dy: Double, dz: Double, fx: Double, fy: Double, fz: Double): Boolean =
    dx * fx + dy * fy + dz * fz > 0.0

/**
 * Walks the cells round a centre nearest first (the user's rule: closest first), a few per call, so a scan of the
 * whole reach spreads over ticks (Rule 2). [wrapped] turns true when a sweep ends; [restart] starts a new one and
 * [rewind] looks again from the centre.
 */
internal class MineCursor(radius: Int) {
    /** (dx, dy, dz) per cell within [radius] + 1 of the centre, nearest first; built once. */
    private val offsets = shellOffsets(radius)
    private val cells = offsets.size / 3
    private var index = 0
    private var cx = 0
    private var cy = 0
    private var cz = 0

    var x = 0
        private set
    var y = 0
        private set
    var z = 0
        private set

    /** The cells this sweep found work in. */
    var hits = 0
    var wrapped = false
        private set

    fun restart(centreX: Int, centreY: Int, centreZ: Int) {
        cx = centreX
        cy = centreY
        cz = centreZ
        rewind()
        wrapped = false
    }

    /** Looks again from the centre out, as a mined block can bare a nearer one. */
    fun rewind() {
        index = 0
        hits = 0
    }

    /** Moves to the next cell; false once the sweep is over. */
    fun next(): Boolean {
        if (index >= cells) {
            wrapped = true
            return false
        }
        x = cx + offsets[index * 3]
        y = cy + offsets[index * 3 + 1]
        z = cz + offsets[index * 3 + 2]
        index++
        return true
    }

    /**
     * The user's rule for redstone mining: the next block is the nearest cell that passes [minable], whatever tool it
     * needs. Looks at [budget] cells at most; on [MineScan.FOUND] the cursor sits on that cell.
     */
    inline fun nextMinable(budget: Int, minable: (Int, Int, Int) -> Boolean): MineScan {
        require(budget > 0) { "Budget must be positive: $budget" }
        repeat(budget) {
            if (!next()) return MineScan.ENDED
            if (minable(x, y, z)) {
                hits++
                return MineScan.FOUND
            }
        }
        return MineScan.BUDGET
    }
}

internal enum class MineScan { FOUND, ENDED, BUDGET }

/** Every offset within [radius] + 1 of the origin (the gem sits inside its cell), sorted nearest first, as triples. */
internal fun shellOffsets(radius: Int): IntArray {
    require(radius >= 0) { "Radius must not be negative: $radius" }
    val limit = (radius + 1) * (radius + 1)
    val cells = ArrayList<IntArray>()
    for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
        if (dx * dx + dy * dy + dz * dz <= limit) cells.add(intArrayOf(dx, dy, dz))
    }
    cells.sortBy { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] }
    val out = IntArray(cells.size * 3)
    for (i in cells.indices) cells[i].copyInto(out, i * 3)
    return out
}

/** The redstone mining log: [ClawhodMine] lines, one per change of reason. */
internal object MineLog {
    private val LOG = LogUtils.getLogger()
    private const val PREFIX = "[ClawhodMine]"

    fun line(worker: UUID, reason: Enum<*>, detail: String) {
        LOG.info("{} worker={} reason={} {}", PREFIX, BuildLog.short(worker), reason.name, detail)
    }
}

internal enum class MineWhy {
    STARTED, STOPPED, MINED, MINED_OUT, SWEEP_EMPTY, NO_TOOL, TOOL_FETCH, TOOL_BROKE, HANDS_FULL, UNLOAD, REFUSED,
    RELAY_OUT, DROPS_WAIT, SKIPPED,
}

/**
 * Mining has no owner, so its permission checks run for this fake player, as MineColonies does. Server owners and
 * claim mods allow it by this fixed name and UUID; never change them.
 */
val MINER_PROFILE = GameProfile(UUID.fromString("c50e28eb-4b3a-3ca9-87d4-f1fd6490ba69"), "[Clawhod]")
