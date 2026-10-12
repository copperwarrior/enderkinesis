package org.shipwrights.enderkinesis.util

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import org.joml.Matrix3d
import org.joml.Matrix4d
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.HeartOfTheWildManager
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.item.ShipCloakingTracker
import org.shipwrights.enderkinesis.item.WyllandTomeManager
import org.shipwrights.enderkinesis.mixin.LevelBlockEntityTickersAccessor
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.mod.common.assembly.VSAssemblyEvents

/**
 * Tells our systems when VS2 moves blocks between ships. Block entities and entities get the
 * move at the end of the level tick, because VS2 places the new blocks after its paste event.
 * Nothing is kept after that, so a holder that is unloaded during a move does not update.
 */
object ShipMoves {
    private val LOG = LogUtils.getLogger()
    private const val TEMP_KEY = "enderkinesis:moved_blocks"
    private const val POSITIONS = "P"

    /** Called at once: a weld deletes the source ship before the end of the tick. */
    private val MANAGERS: List<ShipMoveListener> =
        listOf(HeartOfTheWildManager, WyllandTomeManager, ShipCloakingTracker)

    // Game thread only, cleared at the end of each level tick.
    private val pending = ArrayList<Pair<ServerLevel, ShipBlockMove>>()

    // Game thread only. VS2 calls onRemove for each old block of a move; our partner
    // cascades must not fire then. The tick bound clears it if VS2 throws mid-move.
    private var movingLevel: ServerLevel? = null
    private var movingTick = -1

    @JvmStatic
    fun isMovingBlocks(level: Level): Boolean =
        level === movingLevel && movingLevel?.server?.tickCount == movingTick

    private fun beginMove(level: ServerLevel, positions: Collection<BlockPos>) {
        movingLevel = level
        movingTick = level.server.tickCount
        OrbOfLinkingBlockEntity.releaseJointsBeforeMove(level, positions)
    }

    @JvmStatic
    fun endMove() {
        movingLevel = null
    }

    fun init() {
        // Split, fracture and assembly share tempData between these two events.
        VSAssemblyEvents.beforeCopy.on { event ->
            beginMove(event.level, event.blockPositions)
            if (event.shipAssembledFrom == null) return@on
            val packed = LongArray(event.blockPositions.size)
            event.blockPositions.forEachIndexed { i, pos -> packed[i] = pos.asLong() }
            event.tempData[TEMP_KEY] = CompoundTag().apply { putLongArray(POSITIONS, packed) }
        }
        VSAssemblyEvents.onPasteBeforeBlocksAreLoaded.on { event ->
            endMove()
            val from = event.oldShip ?: return@on
            val to = event.newShip ?: return@on
            val packed = event.tempData[TEMP_KEY]?.getLongArray(POSITIONS) ?: return@on
            val (oldCenter, newCenter) = event.centerPosition
            val transform = Matrix4d().translation(
                newCenter.x() - oldCenter.x(), newCenter.y() - oldCenter.y(), newCenter.z() - oldCenter.z(),
            )
            queue(event.level, from.id, to.id, false, transform, LongOpenHashSet(packed))
        }
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick(::deliver))
    }

    /** Called by ShipWelderMoveMixin at the start of `ShipWelder.merge`. Never throws (see [onWeld]). */
    @JvmStatic
    fun beginWeld(level: ServerLevel, plan: Any) {
        try {
            beginMove(level, (invoke(plan, "getMoves") as List<*>).map { invoke(it!!, "getFrom") as BlockPos })
        } catch (e: Exception) {
            movingLevel = level
            movingTick = level.server.tickCount
            LOG.error("Could not read the VS2 weld plan before the weld", e)
        }
    }

    /**
     * Called by ShipWelderMoveMixin after VS2 moved every block of [source] into [target].
     * [plan] is VS2's private `ShipWelder.Plan`, read by reflection. This runs inside the VS2
     * weld `try`, and an exception there rolls the weld back, so it never throws.
     */
    @JvmStatic
    fun onWeld(level: ServerLevel, target: ServerShip, targetPos: BlockPos, targetFace: Direction,
               source: ServerShip, sourcePos: BlockPos, plan: Any) {
        try {
            val rotation = invoke(plan, "getRotation")
            val axes = listOf("getXAxis", "getYAxis", "getZAxis").map { invoke(rotation, it) as Direction }
            val moves = invoke(plan, "getMoves") as List<*>
            val moved = LongOpenHashSet(moves.size)
            for (move in moves) moved.add((invoke(move!!, "getFrom") as BlockPos).asLong())
            val transform = weldTransform(axes, sourcePos, targetPos.relative(targetFace))
            moves.firstOrNull()?.let { first ->
                val from = invoke(first, "getFrom") as BlockPos
                val to = invoke(first, "getTo") as BlockPos
                val mapped = transform.transformPosition(Vector3d(from.x + 0.5, from.y + 0.5, from.z + 0.5))
                if (BlockPos.containing(mapped.x, mapped.y, mapped.z) != to) {
                    LOG.error("Weld transform disagrees with VS2 ($from -> $to); move ignored")
                    return
                }
            }
            queue(level, source.id, target.id, true, transform, moved)
        } catch (e: Exception) {
            LOG.error("Could not read the VS2 weld of ship ${source.id} into ${target.id}", e)
        }
    }

    /** p' = R·(p − (sourceAnchor + ½)) + (destination + ½), as in VS2 `WeldRotation.destination`. */
    private fun weldTransform(axes: List<Direction>, sourceAnchor: BlockPos, destination: BlockPos): Matrix4d {
        val (x, y, z) = axes
        val rotation = Matrix3d(
            x.stepX.toDouble(), x.stepY.toDouble(), x.stepZ.toDouble(),
            y.stepX.toDouble(), y.stepY.toDouble(), y.stepZ.toDouble(),
            z.stepX.toDouble(), z.stepY.toDouble(), z.stepZ.toDouble(),
        )
        return Matrix4d()
            .translation(destination.x + 0.5, destination.y + 0.5, destination.z + 0.5)
            .mul(Matrix4d().set(rotation))
            .translate(-(sourceAnchor.x + 0.5), -(sourceAnchor.y + 0.5), -(sourceAnchor.z + 0.5))
    }

    // VS2's weld classes are private, but their Kotlin getters are public methods.
    private fun invoke(target: Any, getter: String): Any =
        target.javaClass.getMethod(getter).apply { isAccessible = true }.invoke(target)

    private fun queue(level: ServerLevel, fromShipId: Long, toShipId: Long, fromShipDeleted: Boolean,
                      transform: Matrix4d, moved: LongOpenHashSet) {
        if (moved.isEmpty() || fromShipId == toShipId) return
        val move = ShipBlockMove(fromShipId, toShipId, fromShipDeleted, transform, moved)
        for (manager in MANAGERS) notify(level, manager, move)
        pending.add(level to move)
    }

    private fun notify(level: ServerLevel, listener: ShipMoveListener, move: ShipBlockMove) {
        try {
            listener.onShipBlocksMoved(level, move)
        } catch (e: Exception) {
            LOG.error("{} failed to follow ship move {} -> {}", listener, move.fromShipId, move.toShipId, e)
        }
    }

    private fun deliver(level: ServerLevel) {
        if (pending.isEmpty()) return
        val moves = pending.filter { it.first === level }.map { it.second }
        if (moves.isEmpty()) return
        pending.removeIf { it.first === level }
        val listeners = ArrayList<ShipMoveListener>()
        for (entity in level.allEntities) if (entity is ShipMoveListener) listeners.add(entity)
        // getBlockEntity can create a missing BE, and outside BE ticking its ticker is added
        // straight to this list.
        for (ticker in (level as LevelBlockEntityTickersAccessor).blockEntityTickers.toList()) {
            val be = level.getBlockEntity(ticker.pos)
            if (be is ShipMoveListener && !be.isRemoved) listeners.add(be)
        }
        for (move in moves) for (listener in listeners) notify(level, listener, move)
    }
}
