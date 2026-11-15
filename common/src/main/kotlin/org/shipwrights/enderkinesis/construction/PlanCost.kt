package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.DirtPathBlock
import net.minecraft.world.level.block.EndGatewayBlock
import net.minecraft.world.level.block.EndPortalBlock
import net.minecraft.world.level.block.FarmBlock
import net.minecraft.world.level.block.GameMasterBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.NetherPortalBlock
import net.minecraft.world.level.block.PinkPetalsBlock
import net.minecraft.world.level.block.SeaPickleBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.TurtleEggBlock
import net.minecraft.world.level.block.piston.MovingPistonBlock
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.block.state.properties.SlabType

sealed interface PlanCost {
    /** Nothing to place: air, or the second half of a two-block part. */
    data object Free : PlanCost

    /** v1 never builds it: fluids, fire, portals, game-master blocks, blocks with no item. */
    data object Refused : PlanCost

    data class Items(val item: Item, val count: Int) : PlanCost {
        init {
            require(count > 0) { "plan cost count must be positive: $count" }
        }

        fun stack(): ItemStack = ItemStack(item, count)
    }
}

// Per-state memo; server thread only.
private val PLAN_COSTS = Reference2ObjectOpenHashMap<BlockState, PlanCost>()
private val PLAN_MATCHES = Reference2ObjectOpenHashMap<BlockState, BlockState>()

fun BlockState.planCost(): PlanCost = PLAN_COSTS.getOrPut(this) { computePlanCost() }

private fun BlockState.computePlanCost(): PlanCost {
    if (isAir) return PlanCost.Free
    if (isSecondHalf()) return PlanCost.Free
    val block = block
    if (block is LiquidBlock || block is BaseFireBlock || block is NetherPortalBlock ||
        block is EndPortalBlock || block is EndGatewayBlock || block is GameMasterBlock ||
        block is MovingPistonBlock || block is PistonHeadBlock
    ) {
        return PlanCost.Refused
    }
    if (block is FarmBlock || block is DirtPathBlock) return PlanCost.Items(Items.DIRT, 1)
    val item = block.asItem()
    if (item == Items.AIR) return PlanCost.Refused
    return PlanCost.Items(item, itemCount())
}

/** Create's schematicannon rule: a plan block goes in without water, whatever its plan state says. */
fun BlockState.withoutWater(): BlockState =
    if (hasProperty(BlockStateProperties.WATERLOGGED)) setValue(BlockStateProperties.WATERLOGGED, false) else this

/**
 * A plan captured while powered must not place that power with no source; the placed block reads its real power.
 * A state that the player sets keeps its plan value (the user's rule); buttons and plates revert on a timer instead.
 */
fun BlockState.withoutPower(): BlockState = withoutPower(
    when (val b = block) {
        is LeverBlock -> LEVER_SET_POWER
        is PlayerSetPower -> b.playerSetPower
        else -> emptySet()
    },
)

// Bootstrap freezes the block registry, so a test cannot make a mod block and passes the set here.
internal fun BlockState.withoutPower(playerSet: Set<Property<*>>): BlockState =
    resetUnless(BlockStateProperties.POWERED, playerSet).resetUnless(BlockStateProperties.POWER, playerSet)
        .resetUnless(BlockStateProperties.TRIGGERED, playerSet).resetUnless(BlockStateProperties.ENABLED, playerSet)

private val LEVER_SET_POWER: Set<Property<*>> = setOf(BlockStateProperties.POWERED)

private fun <T : Comparable<T>> BlockState.resetUnless(property: Property<T>, playerSet: Set<Property<*>>): BlockState =
    if (property in playerSet) this else withDefault(property)

/** The world sets power and LIT after the place, so plan matching ignores them; else a fix puts the plan power back. */
fun BlockState.forPlanMatch(): BlockState = PLAN_MATCHES.getOrPut(this) { computePlanMatch() }

private fun BlockState.computePlanMatch(): BlockState = withoutWater().withoutPower().withDefault(BlockStateProperties.LIT)

private fun <T : Comparable<T>> BlockState.withDefault(property: Property<T>): BlockState =
    if (hasProperty(property)) setValue(property, block.defaultBlockState().getValue(property)) else this

/** The half that the other half's placement makes: door and tall-plant tops, bed heads. */
fun BlockState.isSecondHalf(): Boolean =
    (hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) ||
        (block is BedBlock && getValue(BedBlock.PART) == BedPart.HEAD)

/** The lower half or bed foot that places its partner through `setPlacedBy`. */
fun BlockState.isFirstHalf(): Boolean =
    (hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) ||
        (block is BedBlock && getValue(BedBlock.PART) == BedPart.FOOT)

private fun BlockState.itemCount(): Int = when (block) {
    is SlabBlock -> if (getValue(SlabBlock.TYPE) == SlabType.DOUBLE) 2 else 1
    is CandleBlock -> getValue(CandleBlock.CANDLES)
    is SeaPickleBlock -> getValue(SeaPickleBlock.PICKLES)
    is TurtleEggBlock -> getValue(TurtleEggBlock.EGGS)
    is SnowLayerBlock -> getValue(SnowLayerBlock.LAYERS)
    is PinkPetalsBlock -> getValue(PinkPetalsBlock.AMOUNT)
    else -> 1
}
