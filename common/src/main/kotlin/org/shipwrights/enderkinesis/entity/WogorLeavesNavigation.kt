package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.control.JumpControl
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.pathfinder.BlockPathTypes
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.WogorLeavesBlock
import kotlin.math.abs

/** Entity types that [WogorLeavesBlock] gives no collision. Give each of them [wogorLeavesNavigation] and [installWogorLeavesControls]. */
val PASSES_THROUGH_WOGOR_LEAVES: TagKey<EntityType<*>> =
    TagKey.create(Registries.ENTITY_TYPE, EnderkinesisMod.id("passes_through_wogor_leaves"))

/** [walksOnWater]: the mob stands on the water surface, as a Strider does on lava; it also needs canFloat. */
fun wogorLeavesNavigation(mob: Mob, level: Level, walksOnWater: Boolean = false): PathNavigation =
    object : GroundPathNavigation(mob, level) {
        override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
            nodeEvaluator = WogorLeavesNodeEvaluator()
            nodeEvaluator.setCanPassDoors(true)
            return PathFinder(nodeEvaluator, maxVisitedNodes)
        }

        override fun hasValidPathType(pathType: BlockPathTypes): Boolean =
            (walksOnWater && pathType == BlockPathTypes.WATER) || super.hasValidPathType(pathType)

        override fun isStableDestination(pos: BlockPos): Boolean =
            (walksOnWater && level.getFluidState(pos).`is`(FluidTags.WATER)) || super.isStableDestination(pos)

        // Water nodes sit in the water cell; a mob on a lily pad stands over 1 block above it, past vanilla's
        // 1-block arrival height, and circles the node.
        override fun followThePath() {
            val current = path
            if (walksOnWater && current != null && !current.isDone) {
                val node = current.nextNodePos
                val reach = if (mob.bbWidth > WIDE_MOB) mob.bbWidth / 2f else WIDE_MOB - mob.bbWidth / 2f
                val rise = mob.y - node.y
                if (abs(mob.x - (node.x + 0.5)) < reach && abs(mob.z - (node.z + 0.5)) < reach &&
                    rise >= 0.0 && rise < WATER_NODE_MAX_RISE
                ) {
                    current.advance()
                    if (current.isDone) return
                }
            }
            super.followThePath()
        }
    }

// Vanilla PathNavigation.followThePath waypoint radius rule.
private const val WIDE_MOB = 0.75f
private const val WATER_NODE_MAX_RISE = 1.5

fun installWogorLeavesControls(mob: Mob, setControls: (MoveControl, JumpControl) -> Unit) {
    val jumps = WogorLeavesJumpControl(mob)
    setControls(WogorLeavesMoveControl(mob, jumps), jumps)
}

private class WogorLeavesJumpControl(mob: Mob) : JumpControl(mob) {
    fun cancel() {
        jump = false
    }
}

/** Vanilla MoveControl tests the block it stands in with no entity context, so it jumps to climb passable leaves. */
private class WogorLeavesMoveControl(mob: Mob, private val jumps: WogorLeavesJumpControl) : MoveControl(mob) {
    override fun tick() {
        val inLeaves = operation == Operation.MOVE_TO &&
            mob.level().getBlockState(mob.blockPosition()).block is WogorLeavesBlock
        val dx = wantedX - mob.x
        val dz = wantedZ - mob.z
        val climbsStep = wantedY - mob.y > mob.maxUpStep() && dx * dx + dz * dz < maxOf(1f, mob.bbWidth)
        super.tick()
        if (inLeaves && !climbsStep && operation == Operation.JUMPING) {
            jumps.cancel()
            operation = Operation.WAIT
        }
    }
}

/** Flying counterpart of [wogorLeavesNavigation]: vanilla flight also plans around leaves as walls. */
fun wogorLeavesFlyingNavigation(mob: Mob, level: Level): PathNavigation =
    object : FlyingPathNavigation(mob, level) {
        override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
            nodeEvaluator = WogorLeavesFlyNodeEvaluator()
            nodeEvaluator.setCanPassDoors(true)
            return PathFinder(nodeEvaluator, maxVisitedNodes)
        }
    }

/** Vanilla reads leaves as LEAVES (blocked) and as a floor, so the evaluator must see Wogor leaves as air. */
private class WogorLeavesNodeEvaluator : WalkNodeEvaluator() {
    private val view = LeavesAsAirView()

    override fun getBlockPathType(level: BlockGetter, x: Int, y: Int, z: Int): BlockPathTypes {
        view.source = level
        return super.getBlockPathType(view, x, y, z)
    }
}

private class WogorLeavesFlyNodeEvaluator : FlyNodeEvaluator() {
    private val view = LeavesAsAirView()

    override fun getBlockPathType(level: BlockGetter, x: Int, y: Int, z: Int): BlockPathTypes {
        view.source = level
        return super.getBlockPathType(view, x, y, z)
    }
}

private class LeavesAsAirView : BlockGetter {
    private companion object {
        val AIR: BlockState = Blocks.AIR.defaultBlockState()
        val WATER: BlockState = Blocks.WATER.defaultBlockState()
    }

    lateinit var source: BlockGetter

    override fun getBlockState(pos: BlockPos): BlockState {
        val state = source.getBlockState(pos)
        if (state.block !is WogorLeavesBlock) return state
        // A waterlogged leaf must path as water, or a mob that sinks in it plans as if on land.
        return if (state.getValue(BlockStateProperties.WATERLOGGED)) WATER else AIR
    }

    override fun getFluidState(pos: BlockPos): FluidState = source.getFluidState(pos)

    override fun getBlockEntity(pos: BlockPos): BlockEntity? = source.getBlockEntity(pos)

    override fun getHeight(): Int = source.height

    override fun getMinBuildHeight(): Int = source.minBuildHeight
}
