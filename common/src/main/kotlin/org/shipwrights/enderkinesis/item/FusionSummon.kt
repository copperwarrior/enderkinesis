package org.shipwrights.enderkinesis.item

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CarvedPumpkinBlock
import net.minecraft.world.level.block.WitherSkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.RotationSegment
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.item.FusionGeometry.Lifted
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.valkyrienskies.core.api.util.GameTickOnly

/**
 * When a Staff of Fusion bubble holds the parts of a wither or an iron golem, it builds the vanilla
 * pattern on the ship that a weld would keep. A part alone on its ship is pulled to its cell and welded
 * there; other parts wait for the regular teleport tries. The last head spawns the mob.
 */
@OptIn(GameTickOnly::class)
internal class FusionSummon {

    private class Part(val ship: LoadedServerShip, val pos: Long, val lone: Boolean)

    private class Plan(
        val recipe: Recipe, val anchor: LoadedServerShip, val support: Long, val cells: LongArray,
        val bases: List<Part>, val heads: List<Part>,
    )

    /** A lone part ship's cell. The weld puts [part] on the face of [parent] that points at [cell]. */
    class Slot(
        val anchor: LoadedServerShip, val ship: LoadedServerShip, val part: BlockPos, val cell: BlockPos,
        val parent: BlockPos, val head: Boolean, val open: Boolean,
    ) {
        val partCenter: Vector3d = Vector3d(part.x + 0.5, part.y + 0.5, part.z + 0.5)
        val cellCenter: Vector3d = Vector3d(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5)

        /** How far the weld moves the part, in blocks. */
        fun snap(): Double {
            val local = anchor.worldToShip.transformPosition(ship.shipToWorld.transformPosition(Vector3d(partCenter)))
            return local.distance(cellCenter)
        }
    }

    /** Head cells are (across, up) from the bottom cell, in fill order. */
    private enum class Recipe(val heads: List<IntArray>) {
        WITHER(listOf(intArrayOf(-1, 2), intArrayOf(1, 2), intArrayOf(0, 2))) {
            override fun isBase(state: BlockState): Boolean = state.`is`(BlockTags.WITHER_SUMMON_BASE_BLOCKS)
            override fun isHead(state: BlockState): Boolean =
                state.`is`(Blocks.WITHER_SKELETON_SKULL) || state.`is`(Blocks.WITHER_SKELETON_WALL_SKULL)
        },
        IRON_GOLEM(listOf(intArrayOf(0, 2))) {
            override fun isBase(state: BlockState): Boolean = state.`is`(Blocks.IRON_BLOCK)
            override fun isHead(state: BlockState): Boolean =
                state.`is`(Blocks.CARVED_PUMPKIN) || state.`is`(Blocks.JACK_O_LANTERN)
        };

        abstract fun isBase(state: BlockState): Boolean
        abstract fun isHead(state: BlockState): Boolean
    }

    private var plan: Plan? = null
    private var nextSearch: Int = 0
    private var nextAssign: Int = 0
    /** Lone part ships by id, with the cell that each one welds into. */
    private val slots = Long2ObjectOpenHashMap<Slot>()
    /** Cells of finished patterns. A wither does not spawn on Peaceful, and its parts must not move again. */
    private val done = LongOpenHashSet()
    private val scratch = BlockPos.MutableBlockPos()

    val active: Boolean get() = plan != null

    /** Looks for a full set of parts, and gives each lone part ship an empty cell of its kind. */
    fun update(level: ServerLevel, lifted: List<Lifted>, center: Vector3dc, now: Int) {
        var current = plan
        if (current == null && now >= nextSearch) {
            nextSearch = now + SEARCH_TICKS
            current = start(level, lifted, center)
            plan = current
            nextAssign = now
        }
        if (current == null || now < nextAssign) return
        nextAssign = now + ASSIGN_TICKS
        if (level.shipObjectWorld.loadedShips.getById(current.anchor.id) == null || !assign(level, current)) abandon(now)
    }

    /** Parts moved, so the cells to fill are stale. */
    fun invalidate() {
        nextAssign = 0
    }

    fun slot(shipId: Long): Slot? = slots.get(shipId)

    /** The slot when one ship is the anchor and the other a lone part ship. */
    fun slotFor(a: Long, b: Long): Slot? =
        slots.get(a)?.takeIf { it.anchor.id == b } ?: slots.get(b)?.takeIf { it.anchor.id == a }

    /** True when [cell] is the last empty cell. Read it before the part goes in: the golem spawns at once. */
    fun isLast(level: ServerLevel, cell: BlockPos): Boolean {
        val current = plan ?: return false
        return current.cells.indices.all { current.cells[it] == cell.asLong() || isFilled(level, current, it) }
    }

    /** Called after a part went into [cell]. Returns true if the empty anchor was deleted. */
    fun placed(level: ServerLevel, cell: BlockPos, last: Boolean): Boolean {
        invalidate()
        val current = plan ?: return false
        if (!last) return false
        current.cells.forEach(done::add)
        plan = null
        slots.clear()
        spawn(level, cell)
        return deleteIfEmpty(level, current.anchor)
    }

    /** A regular teleport try while summoning: the first open cell takes a part, preferably one that cannot weld. */
    fun teleport(level: ServerLevel, now: Int): FusionTeleport.Move? {
        val current = plan ?: return null
        val index = current.cells.indices.firstOrNull { !isFilled(level, current, it) && isOpen(level, current, it) }
        val isHead = index != null && index >= BASE.size
        val parts = if (isHead) current.heads else current.bases
        val part = index?.let { parts.firstOrNull { !it.lone && isValid(level, current, it, isHead) } }
            ?: index?.let { parts.firstOrNull { isValid(level, current, it, isHead) } }
        val to = index?.let { BlockPos.of(current.cells[it]) }
        if (part == null || to == null || !current.anchor.isFreeCell(level, to)) {
            abandon(now)
            return null
        }
        val last = isLast(level, to)
        val from = BlockPos.of(part.pos)
        val state = turned(level.getBlockState(from), FusionGeometry.alignment(current.anchor, part.ship).relative)
        val point = current.anchor.shipToWorld.transformPosition(Vector3d(to.x + 0.5, to.y + 0.5, to.z + 0.5))
        level.setBlock(to, state, Block.UPDATE_ALL)
        level.setBlock(from, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        val deleted = part.ship !== current.anchor && deleteIfEmpty(level, part.ship)
        return FusionTeleport.Move(point, state, placed(level, to, last) || deleted)
    }

    private fun abandon(now: Int) {
        plan = null
        slots.clear()
        nextSearch = now + SEARCH_TICKS
    }

    private fun start(level: ServerLevel, lifted: List<Lifted>, center: Vector3dc): Plan? {
        val anchor = FusionManager.keptShip(level, lifted) ?: return null
        val anchorEntry = lifted.first { it.ship === anchor }
        val parts = findParts(level, lifted, anchor)
        for (recipe in Recipe.entries) {
            val (bases, heads) = parts[recipe.ordinal]
            if (bases.size < BASE.size || heads.size < recipe.heads.size) continue
            return place(level, anchorEntry, center, recipe, bases, heads) ?: continue
        }
        return null
    }

    /** For each recipe, its base parts and its head parts among the blocks inside the bubble. */
    private fun findParts(
        level: ServerLevel, lifted: List<Lifted>, anchor: LoadedServerShip,
    ): List<Pair<MutableList<Part>, MutableList<Part>>> {
        val parts = Recipe.entries.map { Pair(ArrayList<Part>(), ArrayList<Part>()) }
        val scale = anchor.uniformScale()
        for (entry in lifted) {
            val lone = entry.ship !== anchor && scale != null && !FusionManager.isJointed(level, entry.ship) &&
                entry.ship.uniformScale()?.let { FusionGeometry.sameScale(it, scale) } == true
            var loneChecked: Boolean? = null
            for (i in entry.inside) {
                val packed = entry.blocks.packed[i]
                if (done.contains(packed)) continue
                val state = level.getBlockState(scratch.set(packed))
                for (recipe in Recipe.entries) {
                    val isBase = recipe.isBase(state)
                    if (!isBase && !recipe.isHead(state)) continue
                    // A scan of the ship, so only for a ship that holds a part.
                    val isLone = loneChecked ?: (lone && entry.ship.countUpTo(level, 2) == 1)
                    loneChecked = isLone
                    val (bases, heads) = parts[recipe.ordinal]
                    (if (isBase) bases else heads).add(Part(entry.ship, packed, isLone))
                }
            }
        }
        return parts
    }

    /** An upright 3×3 of air on the anchor, nearest the bubble center, that stands on an anchor block so
     *  that VS2 does not split the parts off. A base part there becomes the bottom cell. */
    @Suppress("LongParameterList") // The recipe and both part lists, which the plan takes over.
    private fun place(
        level: ServerLevel, anchor: Lifted, center: Vector3dc, recipe: Recipe,
        bases: MutableList<Part>, heads: List<Part>,
    ): Plan? {
        val local = anchor.ship.worldToShip.transformPosition(Vector3d(center))
        val supports = anchor.inside.map { anchor.blocks.packed[it] }.sortedBy {
            local.distanceSquared(BlockPos.getX(it) + 0.5, BlockPos.getY(it) + 0.5, BlockPos.getZ(it) + 0.5)
        }
        for (support in supports) {
            val state = level.getBlockState(scratch.set(support))
            if (state.isAir || recipe.isHead(state)) continue
            val reuse = recipe.isBase(state) && !done.contains(support)
            val bottom = if (reuse) support else BlockPos.offset(support, 0, 1, 0)
            for (across in ACROSS) {
                if (!fits(level, anchor.ship, bottom, across, reuse)) continue
                if (reuse) bases.removeIf { it.pos == support }
                val cells = (BASE + recipe.heads).map { cell(bottom, across, it[0], it[1]) }.toLongArray()
                return Plan(recipe, anchor.ship, BlockPos.offset(bottom, 0, -1, 0), cells, bases, heads)
            }
        }
        return null
    }

    private fun fits(level: ServerLevel, ship: LoadedServerShip, bottom: Long, across: Direction, reuse: Boolean): Boolean {
        for (up in 0..2) for (side in -1..1) {
            if (reuse && up == 0 && side == 0) continue
            if (!ship.isFreeCell(level, scratch.set(cell(bottom, across, side, up)))) return false
        }
        return true
    }

    private fun cell(bottom: Long, across: Direction, side: Int, up: Int): Long =
        BlockPos.offset(bottom, across.stepX * side, up, across.stepZ * side)

    /** Gives each empty cell a lone part ship of its kind. False when some empty cell has no part left. */
    private fun assign(level: ServerLevel, plan: Plan): Boolean {
        slots.clear()
        var nextBase = 0
        var nextHead = 0
        for (index in plan.cells.indices) {
            if (isFilled(level, plan, index)) continue
            val isHead = index >= BASE.size
            val parts = if (isHead) plan.heads else plan.bases
            var start = if (isHead) nextHead else nextBase
            while (start < parts.size && !isValid(level, plan, parts[start], isHead)) start++
            if (start >= parts.size) return false
            val part = parts[start]
            if (isHead) nextHead = start + 1 else nextBase = start + 1
            if (!part.lone) continue
            val cell = BlockPos.of(plan.cells[index])
            slots.put(part.ship.id, Slot(
                plan.anchor, part.ship, BlockPos.of(part.pos), cell, BlockPos.of(parent(plan, index)), isHead,
                isOpen(level, plan, index) && plan.anchor.isFreeCell(level, cell),
            ))
        }
        return true
    }

    private fun isFilled(level: ServerLevel, plan: Plan, index: Int): Boolean {
        val state = level.getBlockState(scratch.set(plan.cells[index]))
        return if (index >= BASE.size) plan.recipe.isHead(state) else plan.recipe.isBase(state)
    }

    /** A cell can take its part once the cell it hangs from is filled. Heads come after every base. */
    private fun isOpen(level: ServerLevel, plan: Plan, index: Int): Boolean {
        if (index >= BASE.size) return BASE.indices.all { isFilled(level, plan, it) }
        if (index == 0) return !level.getBlockState(scratch.set(plan.support)).isAir
        return isFilled(level, plan, BASE_PARENTS[index])
    }

    private fun parent(plan: Plan, index: Int): Long = when {
        index == 0 -> plan.support
        index < BASE.size -> plan.cells[BASE_PARENTS[index]]
        else -> BlockPos.offset(plan.cells[index], 0, -1, 0)
    }

    private fun isValid(level: ServerLevel, plan: Plan, part: Part, isHead: Boolean): Boolean {
        val state = level.getBlockState(scratch.set(part.pos))
        val matches = if (isHead) plan.recipe.isHead(state) else plan.recipe.isBase(state)
        return matches && level.getShipManagingPos(scratch)?.id == part.ship.id
    }

    /** A carved pumpkin spawns the golem from onPlace, a wither skull the wither only from setPlacedBy. A
     *  weld places blocks its own way, so both checks run again; a spawn already done left air. */
    private fun spawn(level: ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (state.block is CarvedPumpkinBlock) state.onPlace(level, pos, Blocks.AIR.defaultBlockState(), false)
        val skull = level.getBlockEntity(pos)
        if (skull is SkullBlockEntity) WitherSkullBlock.checkSpawn(level, pos, skull)
    }

    /** [state] turned by [turn], the grid rotation that a weld gives the part. VS2's rotateState refuses most
     *  turns of a head; MC heads are always upright, so the turned face is flattened to the nearest facing. */
    private fun turned(state: BlockState, turn: Quaterniondc): BlockState {
        val face = faceOf(state) ?: return state
        turn.transform(face)
        if (face.x * face.x + face.z * face.z < FLAT_EPSILON) {
            // Face turned straight up or down: the top of the head now points the way the face reads.
            val sign = -Math.signum(face.y)
            face.set(turn.transform(Vector3d(0.0, 1.0, 0.0))).mul(sign)
        }
        val degrees = Math.toDegrees(atan2(-face.x, face.z))
        return if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            state.setValue(BlockStateProperties.ROTATION_16, RotationSegment.convertToSegment(degrees.toFloat()))
        } else {
            state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.fromYRot(degrees))
        }
    }

    /** The direction a head's face points, in MC yaw convention (0° is south). Null for a block without one. */
    private fun faceOf(state: BlockState): Vector3d? {
        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            val yaw = Math.toRadians(RotationSegment.convertToDegrees(state.getValue(BlockStateProperties.ROTATION_16)).toDouble())
            return Vector3d(-sin(yaw), 0.0, cos(yaw))
        }
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return null
        val facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING)
        return Vector3d(facing.stepX.toDouble(), 0.0, facing.stepZ.toDouble())
    }

    /** An empty ship crashes the physics thread, so it goes in the same call. */
    private fun deleteIfEmpty(level: ServerLevel, ship: LoadedServerShip): Boolean {
        if (ship.countUpTo(level, 1) != 0) return false
        ShipAssembler.deleteShip(level, ship, false, false)
        return true
    }

    private companion object {
        /** The cells that both vanilla patterns share, (across, up) from the bottom cell, in fill order. */
        val BASE: List<IntArray> = listOf(intArrayOf(0, 0), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(1, 1))
        /** For each base cell after the first, the base cell it hangs from. */
        val BASE_PARENTS: IntArray = intArrayOf(-1, 0, 1, 1)
        val ACROSS: List<Direction> = listOf(Direction.EAST, Direction.SOUTH)
        /** Ticks between searches for a full set of parts. Each search reads every block inside the bubble. */
        const val SEARCH_TICKS: Int = 20
        const val ASSIGN_TICKS: Int = 5
        const val FLAT_EPSILON: Double = 1e-6
    }
}
