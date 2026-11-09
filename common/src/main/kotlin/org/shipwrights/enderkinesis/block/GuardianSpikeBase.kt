package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.mixin.BlockPropertiesAccessor
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.Optional
import org.shipwrights.enderkinesis.util.damageSource

/** Shape, damage, offset and waterlogging shared by the corner-cluster and outer guardian spike blocks. */
abstract class GuardianSpikeBase(props: Properties) : Block(props), SimpleWaterloggedBlock {

    /** The spikes [state] holds. Implementations read only the state, so this is safe during construction. */
    abstract fun spikes(state: BlockState): List<SpikeAttachment>

    /** Whether the spike is the outer kind, whose plate folds onto the outside of one block. */
    abstract val outer: Boolean

    private class StateInfo(val spikes: List<SpikeAttachment>, val free: BooleanArray)

    /** A spike and its cell-local shift, as rendered. */
    class PlacedSpike(val attach: SpikeAttachment, val shift: Vec3) {
        fun shape(): VoxelShape = SHAPES.getValue(attach).move(shift.x, shift.y, shift.z)

        /** [box] is cell-local. */
        fun touches(box: AABB): Boolean {
            val local = box.move(-shift.x, -shift.y, -shift.z)
            return HIT_BOXES.getValue(attach).any { it.intersects(local) }
        }
    }

    private val infos: Map<BlockState, StateInfo> = IdentityHashMap<BlockState, StateInfo>().apply {
        for (state in stateDefinition.possibleStates) {
            val spikes = spikes(state)
            put(state, StateInfo(spikes, BooleanArray(3) { axis -> spikes.all { it.step(axis) == 0 } }))
        }
    }

    fun cachedSpikes(state: BlockState): List<SpikeAttachment> = infos.getValue(state).spikes

    /** The spikes at [pos] with their shifts. Default: every spike moves with the cell offset. */
    open fun placed(state: BlockState, pos: BlockPos): List<PlacedSpike> {
        val offset = offset(state, pos)
        return cachedSpikes(state).map { PlacedSpike(it, offset) }
    }

    /** Random shift like foliage, but only along axes every spike can slide on, so each stays on its support. */
    open fun offset(state: BlockState, pos: BlockPos): Vec3 {
        val free = infos.getValue(state).free
        if (!free[0] && !free[1] && !free[2]) return Vec3.ZERO
        @Suppress("DEPRECATION") // Mth.getSeed is the vanilla position hash; 1.20.1 has no replacement.
        val seed = Mth.getSeed(pos.x, pos.y, pos.z)
        return Vec3(
            if (free[0]) offsetFromBits(seed) else 0.0,
            if (free[1]) offsetFromBits(seed shr 4) else 0.0,
            if (free[2]) offsetFromBits(seed shr 8) else 0.0,
        )
    }

    // Shifts are already in the spikes, so the vanilla offset (zero or not) is not added again here.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        placed(state, pos).fold(Shapes.empty()) { acc, spike -> Shapes.or(acc, spike.shape()) }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        if (level.isClientSide || entity !is LivingEntity) return
        val box = entity.boundingBox.move(-pos.x.toDouble(), -pos.y.toDouble(), -pos.z.toDouble())
        if (placed(state, pos).none { it.touches(box) }) return
        entity.hurt(level.damageSource(DAMAGE_TYPE), DAMAGE)
    }

    protected fun scheduleWater(state: BlockState, level: LevelAccessor, pos: BlockPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun isPathfindable(state: BlockState, level: BlockGetter, pos: BlockPos, type: PathComputationType): Boolean = false

    companion object {
        val WATERLOGGED = BlockStateProperties.WATERLOGGED

        val DAMAGE_TYPE: ResourceKey<DamageType> =
            ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("guardian_spike"))
        private const val DAMAGE = 2.0f

        /** Half the spike's 2 px width. */
        private const val HALF_WIDTH = 1.0 / 16.0
        /** Boxes along an edge or corner spike; more fit it closer but slow every collision query. */
        private const val DIAGONAL_STEPS = 4
        /** Matches vanilla foliage's 0.25 max offset, which keeps the 8 px plate on its face. */
        const val MAX_OFFSET = 0.25

        /** One box hides a diagonal spike in a cube of air, so edge and corner spikes get a staircase along the axis. */
        private val HIT_BOXES: Map<SpikeAttachment, List<AABB>> = EnumMap<SpikeAttachment, List<AABB>>(SpikeAttachment::class.java).apply {
            val cell = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
            for (attach in SpikeAttachment.entries) {
                val steps = if (attach.order == 1) 1 else DIAGONAL_STEPS
                put(attach, List(steps) { i ->
                    AABB(attach.along(i.toDouble() / steps), attach.along((i + 1.0) / steps)).inflate(HALF_WIDTH).intersect(cell)
                })
            }
        }

        private val SHAPES: Map<SpikeAttachment, VoxelShape> = EnumMap<SpikeAttachment, VoxelShape>(SpikeAttachment::class.java).apply {
            for ((attach, boxes) in HIT_BOXES) put(attach, boxes.fold(Shapes.empty()) { acc, box -> Shapes.or(acc, Shapes.create(box)) }.optimize())
        }

        fun withOffset(props: Properties): Properties = props.also {
            (it as BlockPropertiesAccessor).`enderkinesis$setOffsetFunction`(
                Optional.of(BlockBehaviour.OffsetFunction { state, _, pos -> (state.block as GuardianSpikeBase).offset(state, pos) })
            )
        }

        private fun offsetFromBits(bits: Long): Double = ((bits and 15L) / 15.0 - 0.5) * 2.0 * MAX_OFFSET

        /** True if [level] has a sturdy face toward [pos] on every axis [attach] leans on: the walls of a face or crease spike. */
        fun wallsHold(level: BlockGetter, pos: BlockPos, attach: SpikeAttachment): Boolean {
            for (axis in 0 until 3) {
                val step = attach.step(axis)
                if (step == 0) continue
                val toward = Direction.fromAxisAndDirection(AXES[axis], if (step > 0) Direction.AxisDirection.POSITIVE else Direction.AxisDirection.NEGATIVE)
                val wall = pos.relative(toward)
                if (!level.getBlockState(wall).isFaceSturdy(level, wall, toward.opposite)) return false
            }
            return true
        }

        private val AXES = arrayOf(Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z)
    }
}
