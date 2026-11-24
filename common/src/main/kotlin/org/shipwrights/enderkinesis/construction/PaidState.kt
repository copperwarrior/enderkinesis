package org.shipwrights.enderkinesis.construction

import net.minecraft.world.level.block.AbstractCauldronBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.PitcherCropBlock
import net.minecraft.world.level.block.StemBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property

// Plans hold no block entity data, so these show contents that nobody paid for (a book, bottles, a record, charges).
private val CONTENT_PROPERTIES: Set<Property<*>> = setOf(
    BlockStateProperties.HAS_BOOK,
    BlockStateProperties.HAS_BOTTLE_0, BlockStateProperties.HAS_BOTTLE_1, BlockStateProperties.HAS_BOTTLE_2,
    BlockStateProperties.CHISELED_BOOKSHELF_SLOT_0_OCCUPIED, BlockStateProperties.CHISELED_BOOKSHELF_SLOT_1_OCCUPIED,
    BlockStateProperties.CHISELED_BOOKSHELF_SLOT_2_OCCUPIED, BlockStateProperties.CHISELED_BOOKSHELF_SLOT_3_OCCUPIED,
    BlockStateProperties.CHISELED_BOOKSHELF_SLOT_4_OCCUPIED, BlockStateProperties.CHISELED_BOOKSHELF_SLOT_5_OCCUPIED,
    BlockStateProperties.HAS_RECORD,
    BlockStateProperties.EYE,
    BlockStateProperties.RESPAWN_ANCHOR_CHARGES,
    BlockStateProperties.LEVEL_COMPOSTER,
    BlockStateProperties.LEVEL_HONEY,
    BlockStateProperties.BERRIES,
)

private const val AGE = "age"

// A grown crop drops more than its seed, so its age is a free harvest.
private fun Block.harvestsByAge(): Boolean =
    this is CropBlock || this is StemBlock || this is CocoaBlock || this is NetherWartBlock ||
        this is SweetBerryBushBlock || this is PitcherCropBlock

/** True when [property] of [block] stands for items that a plan cannot pay for. */
fun isContentProperty(block: Block, property: Property<*>): Boolean =
    property in CONTENT_PROPERTIES || (property.name == AGE && block.harvestsByAge())

/**
 * The state that [planCost] pays for: every content property empty, a filled cauldron as an empty one.
 * Placement, cost and plan match all go through this, so a plan can never place items it did not charge for.
 */
fun BlockState.paidState(): BlockState {
    val block = block
    if (block is AbstractCauldronBlock && block !== Blocks.CAULDRON) return Blocks.CAULDRON.defaultBlockState()
    var paid = this
    for (property in properties) {
        if (isContentProperty(block, property)) paid = paid.withDefaultOf(property)
    }
    return paid
}

/** [plan] with the contents that the world block already holds, so a free fix never clears a real book or bottle. */
fun BlockState.withContentsOf(world: BlockState): BlockState {
    require(world.block == block) { "contents come from the same block: $world vs $this" }
    var kept = this
    for (property in properties) {
        if (isContentProperty(block, property)) kept = kept.withValueOf(property, world)
    }
    return kept
}

/** Boundary check before a paid place: no unpaid contents, and [cost] is the full price of [state]. */
internal fun checkPaid(state: BlockState, cost: PlanCost.Items) {
    check(state == state.paidState()) { "unpaid contents reach setBlock: $state" }
    check(state.planCost() == cost) { "cost $cost is not the price of $state (${state.planCost()})" }
}

private fun <T : Comparable<T>> BlockState.withDefaultOf(property: Property<T>): BlockState =
    setValue(property, block.defaultBlockState().getValue(property))

private fun <T : Comparable<T>> BlockState.withValueOf(property: Property<T>, from: BlockState): BlockState =
    setValue(property, from.getValue(property))
