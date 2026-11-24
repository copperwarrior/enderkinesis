package org.shipwrights.enderkinesis.entity

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.entity.ConstriliarSurfaceNavigator.Node
import org.shipwrights.enderkinesis.util.classpathBlockTag

/** An in-memory block world: the real planner reads it through its block seam. Each world has its own
 *  mocked [Level], for the game time and path sharing only; [close] releases both. */
internal class NavigatorTestWorld(private val groundTop: Int = 0, private val groundRadius: Int = 24) :
    ConstriliarSurfaceNavigator.BlockSource, AutoCloseable {

    private val blocks = Long2ObjectOpenHashMap<BlockState>()

    val level: Level = mockk(relaxed = true)

    init {
        every { level.gameTime } returns 0L
        excludeRecords { level.gameTime }
    }

    override fun close() {
        ConstriliarPathShare.forget(level)
        clearMocks(level)
    }

    override fun loaded(level: Level, pos: BlockPos): Boolean = true

    override fun state(level: Level, pos: BlockPos): BlockState = stateAt(pos)

    override fun shape(level: Level, pos: BlockPos, state: BlockState): VoxelShape = supportShape(level, pos, state)

    fun stateAt(pos: BlockPos): BlockState = blocks.get(pos.asLong()) ?: when {
        groundTop == NO_GROUND -> AIR
        pos.y <= groundTop && pos.y > groundTop - GROUND_DEPTH &&
            Math.abs(pos.x) <= groundRadius && Math.abs(pos.z) <= groundRadius -> STONE
        else -> AIR
    }

    fun set(x: Int, y: Int, z: Int, state: BlockState = STONE) {
        blocks.put(BlockPos.asLong(x, y, z), state)
    }

    fun fill(x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int, state: BlockState = STONE) {
        for (x in x0..x1) for (y in y0..y1) for (z in z0..z1) set(x, y, z, state)
    }

    fun clear(x0: Int, x1: Int, y0: Int, y1: Int, z0: Int, z1: Int) = fill(x0, x1, y0, y1, z0, z1, AIR)

    /** Lines of "x y z blockstate" from a test resource. */
    fun load(resource: String) {
        val text = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing $resource" }
            .bufferedReader().readText()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split(' ', limit = 4)
            set(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parse(parts[3]))
        }
    }

    /** A planner on this world for a walker with [load] blocks on its back (dx, dy, dz from the plate). */
    fun navigator(vararg load: Triple<Int, Int, Int>): ConstriliarSurfaceNavigator {
        val nav = ConstriliarSurfaceNavigator(this)
        val shape = ConstriliarCargoShape()
        shape.begin()
        for ((dx, dy, dz) in load) shape.add(dx, dy, dz)
        shape.finish()
        nav.setClearance(shape)
        return nav
    }

    /** The first node at [pos] that holds the body, floor first. */
    fun nodeAt(nav: ConstriliarSurfaceNavigator, pos: BlockPos): Node? =
        FACE_ORDER.map { Node(pos, it) }.firstOrNull { nav.nodeHoldsForTest(level, it) }

    companion object {
        const val NO_GROUND = Int.MIN_VALUE
        private const val GROUND_DEPTH = 8
        val STONE: BlockState = Blocks.STONE.defaultBlockState()
        val AIR: BlockState = Blocks.AIR.defaultBlockState()
        val CARRIER = Triple(0, 1, 0)
        private val FACE_ORDER = listOf(
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP,
        )

        val climbableIds by lazy { classpathBlockTag(CONSTRILIAR_CLIMBABLE.location) }

        fun isClimbable(state: BlockState): Boolean = BuiltInRegistries.BLOCK.getKey(state.block) in climbableIds

        // Mirrors ConstriliarCollision.supportShape; that object needs the VS2 mixin accessors.
        // VS2's vs_mass natural/tree.json gives #minecraft:leaves no_collision.
        @Suppress("DEPRECATION") // VS2 uses BlockState.isSolid; 1.20.1 has no replacement.
        fun supportShape(level: BlockGetter, pos: BlockPos, state: BlockState): VoxelShape = when {
            isClimbable(state) -> Shapes.block()
            state.block is LeavesBlock -> Shapes.empty()
            state.isSolid -> state.getShape(level, pos)
            else -> state.getCollisionShape(level, pos)
        }

        /** Mod blocks have no registry in tests: bars and chains map to the vanilla ones, the rest to stone. */
        private fun parse(text: String): BlockState {
            val id = text.substringBefore('[')
            val vanilla = when {
                id.startsWith("minecraft:") -> text
                id.endsWith("_bars") -> "minecraft:iron_bars"
                id.endsWith("_chain") -> "minecraft:chain"
                else -> "minecraft:stone"
            }
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), vanilla, false).blockState()
        }
    }
}
