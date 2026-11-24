package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaidStateTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val lectern = Blocks.LECTERN.defaultBlockState()
    private val lecternWithBook = lectern.setValue(LecternBlock.HAS_BOOK, true)

    @Test
    fun `a lectern costs one lectern and goes in without a book`() {
        lectern.planCost() shouldBe PlanCost.Items(Items.LECTERN, 1)
        lecternWithBook.planCost() shouldBe PlanCost.Items(Items.LECTERN, 1)
        lecternWithBook.paidState() shouldBe lectern
        lectern.paidState() shouldBe lectern
    }

    @Test
    fun `a brewing stand goes in without bottles`() {
        val stand = Blocks.BREWING_STAND.defaultBlockState()
        val full = stand.setValue(BlockStateProperties.HAS_BOTTLE_0, true).setValue(BlockStateProperties.HAS_BOTTLE_1, true)
            .setValue(BlockStateProperties.HAS_BOTTLE_2, true)
        full.paidState() shouldBe stand
        full.planCost() shouldBe PlanCost.Items(Items.BREWING_STAND, 1)
    }

    @Test
    fun `a chiseled bookshelf and a jukebox go in empty`() {
        val shelf = Blocks.CHISELED_BOOKSHELF.defaultBlockState()
        shelf.setValue(BlockStateProperties.CHISELED_BOOKSHELF_SLOT_0_OCCUPIED, true)
            .setValue(BlockStateProperties.CHISELED_BOOKSHELF_SLOT_5_OCCUPIED, true).paidState() shouldBe shelf
        val jukebox = Blocks.JUKEBOX.defaultBlockState()
        jukebox.setValue(BlockStateProperties.HAS_RECORD, true).paidState() shouldBe jukebox
    }

    @Test
    fun `charges, fill levels and frame eyes go in empty`() {
        Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES, 4).paidState() shouldBe
            Blocks.RESPAWN_ANCHOR.defaultBlockState()
        Blocks.COMPOSTER.defaultBlockState().setValue(BlockStateProperties.LEVEL_COMPOSTER, 8).paidState() shouldBe
            Blocks.COMPOSTER.defaultBlockState()
        Blocks.BEEHIVE.defaultBlockState().setValue(BlockStateProperties.LEVEL_HONEY, 5).paidState() shouldBe
            Blocks.BEEHIVE.defaultBlockState()
        Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(BlockStateProperties.EYE, true).paidState() shouldBe
            Blocks.END_PORTAL_FRAME.defaultBlockState()
    }

    @Test
    fun `a filled cauldron goes in as an empty cauldron`() {
        val water = Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3)
        water.paidState() shouldBe Blocks.CAULDRON.defaultBlockState()
        water.planCost() shouldBe PlanCost.Items(Items.CAULDRON, 1)
        Blocks.LAVA_CAULDRON.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.CAULDRON, 1)
    }

    @Test
    fun `crops go in as seeds and berries go in unripe`() {
        val wheat = Blocks.WHEAT.defaultBlockState()
        wheat.setValue(CropBlock.AGE, 7).paidState() shouldBe wheat
        wheat.setValue(CropBlock.AGE, 7).planCost() shouldBe PlanCost.Items(Items.WHEAT_SEEDS, 1)
        Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.BERRIES, true).paidState() shouldBe
            Blocks.CAVE_VINES.defaultBlockState()
    }

    @Test
    fun `item counts follow the state`() {
        Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE).planCost() shouldBe PlanCost.Items(Items.OAK_SLAB, 2)
        Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).planCost() shouldBe PlanCost.Items(Items.CANDLE, 3)
    }

    @Test
    fun `a door and a bed cost one item for both halves`() {
        Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).planCost() shouldBe PlanCost.Items(Items.OAK_DOOR, 1)
        Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).planCost() shouldBe PlanCost.Free
        Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT).planCost() shouldBe PlanCost.Items(Items.RED_BED, 1)
        Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD).planCost() shouldBe PlanCost.Free
    }

    @Test
    fun `a chest costs one chest and its contents never reach the plan state`() {
        val chest = Blocks.CHEST.defaultBlockState()
        chest.planCost() shouldBe PlanCost.Items(Items.CHEST, 1)
        chest.paidState() shouldBe chest
    }

    @Test
    fun `a potted plant costs the pot and the plant`() {
        val cost = Blocks.POTTED_POPPY.defaultBlockState().planCost()
        cost shouldBe PlanCost.Items(Items.FLOWER_POT, 1, Items.POPPY, 1)
        (cost as PlanCost.Items).size shouldBe 2
        cost.itemAt(1) shouldBe Items.POPPY
        cost.countAt(1) shouldBe 1
        Blocks.POTTED_OAK_SAPLING.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.FLOWER_POT, 1, Items.OAK_SAPLING, 1)
        Blocks.FLOWER_POT.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.FLOWER_POT, 1)
    }

    @Test
    fun `a candle cake costs the cake and its own candle`() {
        Blocks.CANDLE_CAKE.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.CAKE, 1, Items.CANDLE, 1)
        Blocks.RED_CANDLE_CAKE.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.CAKE, 1, Items.RED_CANDLE, 1)
    }

    @Test
    fun `a one-item cost has one entry`() {
        val cost = PlanCost.Items(Items.STONE, 3)
        cost.size shouldBe 1
        cost.itemAt(0) shouldBe Items.STONE
        cost.countAt(0) shouldBe 3
        shouldThrow<IllegalStateException> { cost.itemAt(1) }
        shouldThrow<IllegalArgumentException> { PlanCost.Items(Items.FLOWER_POT, 1, Items.POPPY, 0) }
    }

    @Test
    fun `spawners are refused`() {
        Blocks.SPAWNER.defaultBlockState().planCost() shouldBe PlanCost.Refused
    }

    @Test
    fun `contents that a player adds later still match the plan`() {
        planMatches(lecternWithBook, lectern) shouldBe true
        planMatches(lectern, lecternWithBook) shouldBe true
        planMatches(Blocks.WHEAT.defaultBlockState(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 5)) shouldBe true
        planMatches(Blocks.CAULDRON.defaultBlockState(), Blocks.WATER_CAULDRON.defaultBlockState()) shouldBe true
    }

    @Test
    fun `a free fix keeps the world's contents`() {
        val plan = lectern.setValue(LecternBlock.FACING, Direction.NORTH)
        val world = lecternWithBook.setValue(LecternBlock.FACING, Direction.EAST)
        fixableInPlace(plan, world) shouldBe true
        fixedState(plan, world) shouldBe plan.setValue(LecternBlock.HAS_BOOK, true)
        val bare = lectern.setValue(LecternBlock.FACING, Direction.EAST)
        fixedState(lecternWithBook.setValue(LecternBlock.FACING, Direction.NORTH), bare) shouldBe plan
    }

    @Test
    fun `an unpaid state or a wrong price never reaches setBlock`() {
        shouldThrow<IllegalStateException> { checkPaid(lecternWithBook, PlanCost.Items(Items.LECTERN, 1)) }
        shouldThrow<IllegalStateException> { checkPaid(lectern, PlanCost.Items(Items.LECTERN, 2)) }
        checkPaid(lectern, PlanCost.Items(Items.LECTERN, 1))
        val potted = Blocks.POTTED_POPPY.defaultBlockState()
        shouldThrow<IllegalStateException> { checkPaid(potted, PlanCost.Items(Items.FLOWER_POT, 1)) }
        shouldThrow<IllegalStateException> { checkPaid(potted, PlanCost.Items(Items.FLOWER_POT, 1, Items.DANDELION, 1)) }
        checkPaid(potted, PlanCost.Items(Items.FLOWER_POT, 1, Items.POPPY, 1))
    }
}
