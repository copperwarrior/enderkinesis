package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import org.joml.Matrix4d
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.shipwrights.enderkinesis.item.FusionGeometry.Lifted
import org.shipwrights.enderkinesis.util.ShipMoves
import org.shipwrights.enderkinesis.util.ShipWelderBridge
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.assembly.ShipAssembler

/**
 * The fallback of one Staff of Fusion bubble: when its ships have not welded for [STALL_TICKS], now
 * and then one random block of a lifted ship teleports to a random free spot on the ship that keeps
 * its blocks in a weld: an empty cell next to a sturdy face.
 */
internal class FusionTeleport {

    /** A teleported block. [deletedShip] is true when the move emptied and deleted its ship. */
    class Move(val point: Vector3d, val state: BlockState, val deletedShip: Boolean)

    private var targetId: Long = NO_SHIP
    private var surface: Surface? = null
    private var nextTry: Int = 0
    /** Ships with a block that cannot be moved alone; they give no blocks until the next weld. */
    private val refused = LongOpenHashSet()
    private val blockPos = BlockPos.MutableBlockPos()

    /** A weld changed the ships, so the free spots and the refusals are stale. */
    fun reset() {
        targetId = NO_SHIP
        surface = null
        refused.clear()
    }

    fun tick(level: ServerLevel, lifted: List<Lifted>, lastWeld: Int, now: Int): Move? {
        if (now - lastWeld < STALL_TICKS || now < nextTry) return null
        nextTry = now + TRY_TICKS
        if (level.random.nextFloat() >= CHANCE) return null
        val target = chooseTarget(level, lifted) ?: return null
        val scale = FusionGeometry.uniformScale(target) ?: return null
        val sources = lifted.filter { entry ->
            entry.ship !== target && !refused.contains(entry.ship.id) && !FusionManager.isJointed(level, entry.ship) &&
                FusionGeometry.uniformScale(entry.ship)?.let { FusionGeometry.sameScale(it, scale) } == true
        }
        if (sources.isEmpty()) return null
        if (target.id != targetId || surface == null) {
            targetId = target.id
            surface = buildSurface(level, target) ?: return null
        }
        return moveOne(level, target, sources[level.random.nextInt(sources.size)].ship, surface!!)
    }

    /** The ship that a weld would keep: jointed, then static, then the heaviest. */
    private fun chooseTarget(level: ServerLevel, lifted: List<Lifted>): LoadedServerShip? {
        var best: LoadedServerShip? = null
        var bestJointed = false
        for (entry in lifted) {
            val jointed = FusionManager.isJointed(level, entry.ship)
            val current = best
            if (current == null || FusionManager.prefersAsTarget(entry.ship, jointed, current, bestJointed)) {
                best = entry.ship
                bestJointed = jointed
            }
        }
        return best
    }

    private fun moveOne(level: ServerLevel, target: LoadedServerShip, source: LoadedServerShip, surface: Surface): Move? {
        val cells = FusionGeometry.allBlocks(level, source) ?: return null
        if (cells.isEmpty()) return null
        val cell = cells[level.random.nextInt(cells.size)]
        val from = BlockPos.of(cell)
        val state = level.getBlockState(from)
        // The block turns to the target's nearest grid rotation, as a weld would turn it.
        val turn = FusionGeometry.alignment(target, source).relative
        val xAxis = axis(turn, 1.0, 0.0, 0.0)
        val yAxis = axis(turn, 0.0, 1.0, 0.0)
        val zAxis = axis(turn, 0.0, 0.0, 1.0)
        var placed: BlockPos? = null
        // A block that needs support, such as a plant, tries other spots; the next try picks a new block.
        for (attempt in 0 until PLACE_TRIES) {
            val to = BlockPos.of(pickSpot(level, target, surface) ?: return null)
            val transform = Matrix4d()
                .translation(to.x + 0.5, to.y + 0.5, to.z + 0.5)
                .rotate(turn)
                .translate(-(from.x + 0.5), -(from.y + 0.5), -(from.z + 0.5))
            val refusal = ShipMoves.moveBlock(level, source.id, target.id, from, transform) {
                ShipWelderBridge.moveBlock(level, target, from, to, xAxis, yAxis, zAxis)
            }
            if (refusal == null) {
                placed = to
                break
            }
            if (refusal == ShipWelderBridge.UNSUPPORTED) continue
            refused.add(source.id)
            LOG.debug("Staff of Fusion: cannot teleport a block of ship {} into {}: {}", source.id, target.id, refusal)
            return null
        }
        val to = placed ?: return null
        // The new block can offer spots on its own faces.
        addFaces(level, target, surface, to.asLong())
        val point = target.shipToWorld.transformPosition(Vector3d(to.x + 0.5, to.y + 0.5, to.z + 0.5))
        if (cells.size > 1) return Move(point, state, false)
        // An empty ship crashes the physics thread, so it goes in the same call.
        ShipAssembler.deleteShip(level, source, false, false)
        return Move(point, state, true)
    }

    private fun axis(turn: Quaterniondc, x: Double, y: Double, z: Double): Direction {
        val v = turn.transform(Vector3d(x, y, z))
        return Direction.getNearest(v.x, v.y, v.z)
    }

    /** The target's free spots: empty cells next to a sturdy face of one of its blocks. Read once a
     *  stall; null while a chunk is not loaded. */
    private fun buildSurface(level: ServerLevel, target: LoadedServerShip): Surface? {
        val blocks = FusionGeometry.allBlocks(level, target) ?: return null
        val surface = Surface()
        for (block in blocks) addFaces(level, target, surface, block)
        return surface
    }

    private fun addFaces(level: ServerLevel, target: LoadedServerShip, surface: Surface, block: Long) {
        val pos = BlockPos.MutableBlockPos().set(block)
        val state = level.getBlockState(pos)
        for (direction in Direction.values()) {
            if (!state.isFaceSturdy(level, pos.set(block), direction)) continue
            val next = BlockPos.offset(block, direction)
            if (isFreeSpot(level, target, next)) surface.add(next)
        }
    }

    /** A random spot that is still free and still next to a sturdy face; stale spots are dropped. */
    private fun pickSpot(level: ServerLevel, target: LoadedServerShip, surface: Surface): Long? {
        repeat(SPOT_TRIES) {
            if (surface.cells.isEmpty) return null
            val index = level.random.nextInt(surface.cells.size)
            val spot = surface.cells.getLong(index)
            if (isFreeSpot(level, target, spot) && touchesSturdyFace(level, spot)) return spot
            surface.removeAt(index)
        }
        return null
    }

    private fun isFreeSpot(level: ServerLevel, target: LoadedServerShip, spot: Long): Boolean {
        val pos = blockPos.set(spot)
        return target.chunkClaim.contains(pos.x shr 4, pos.z shr 4) && !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).isAir
    }

    private fun touchesSturdyFace(level: ServerLevel, spot: Long): Boolean {
        val pos = BlockPos.MutableBlockPos()
        for (direction in Direction.values()) {
            pos.set(spot).move(direction)
            val neighbor = level.getBlockState(pos)
            if (!neighbor.isAir && neighbor.isFaceSturdy(level, pos, direction.opposite)) return true
        }
        return false
    }

    /** Free spots as a list for random picks and a set against repeats. */
    private class Surface {
        val cells = LongArrayList()
        private val seen = LongOpenHashSet()

        fun add(spot: Long) {
            if (seen.add(spot)) cells.add(spot)
        }

        fun removeAt(index: Int) {
            seen.remove(cells.getLong(index))
            cells.set(index, cells.getLong(cells.size - 1))
            cells.removeLong(cells.size - 1)
        }
    }

    private companion object {
        val LOG = LogUtils.getLogger()
        const val NO_SHIP: Long = Long.MIN_VALUE
        /** Ticks without a weld in the bubble before blocks start to teleport. */
        const val STALL_TICKS: Int = 200
        /** Ticks between teleport tries; each try moves a block with [CHANCE]. */
        const val TRY_TICKS: Int = 10
        const val CHANCE: Float = 0.5f
        /** Random spots tried per move before it waits for the next try. */
        const val SPOT_TRIES: Int = 8
        /** Spots tried per move for a block that needs support at its new cell. */
        const val PLACE_TRIES: Int = 4
    }
}
