package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.SeaPickleBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.block.state.properties.SlabType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanCostTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `a plain block costs its own item`() {
        Blocks.STONE.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.STONE, 1)
    }

    @Test
    fun `air is free`() {
        Blocks.AIR.defaultBlockState().planCost() shouldBe PlanCost.Free
    }

    @Test
    fun `wall variants cost the standing item`() {
        Blocks.WALL_TORCH.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.TORCH, 1)
        Blocks.OAK_WALL_SIGN.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.OAK_SIGN, 1)
    }

    @Test
    fun `a double slab costs two slabs`() {
        val state = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE)
        state.planCost() shouldBe PlanCost.Items(Items.OAK_SLAB, 2)
    }

    @Test
    fun `candles and sea pickles cost one per piece`() {
        Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).planCost() shouldBe PlanCost.Items(Items.CANDLE, 3)
        Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, 4).planCost() shouldBe
            PlanCost.Items(Items.SEA_PICKLE, 4)
    }

    @Test
    fun `redstone wire costs redstone dust`() {
        Blocks.REDSTONE_WIRE.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.REDSTONE, 1)
    }

    @Test
    fun `the second half of a two-block part is free`() {
        Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).planCost() shouldBe PlanCost.Free
        Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).planCost() shouldBe
            PlanCost.Items(Items.OAK_DOOR, 1)
        Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD).planCost() shouldBe PlanCost.Free
        Blocks.TALL_GRASS.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)
            .planCost() shouldBe PlanCost.Free
    }

    @Test
    fun `fire, portals, fluids and game-master blocks are refused`() {
        Blocks.FIRE.defaultBlockState().planCost() shouldBe PlanCost.Refused
        Blocks.NETHER_PORTAL.defaultBlockState().planCost() shouldBe PlanCost.Refused
        Blocks.END_PORTAL.defaultBlockState().planCost() shouldBe PlanCost.Refused
        Blocks.WATER.defaultBlockState().planCost() shouldBe PlanCost.Refused
        Blocks.COMMAND_BLOCK.defaultBlockState().planCost() shouldBe PlanCost.Refused
    }

    @Test
    fun `cell kinds sort gravity and attached blocks`() {
        Blocks.SAND.defaultBlockState().cellKind() shouldBe CellKind.WEAK
        Blocks.TORCH.defaultBlockState().cellKind() shouldBe CellKind.ATTACHED
        Blocks.STONE.defaultBlockState().cellKind() shouldBe CellKind.SOLID
        Blocks.FIRE.defaultBlockState().cellKind() shouldBe CellKind.SKIP
    }

    @Test
    fun `farmland and dirt paths cost dirt`() {
        Blocks.FARMLAND.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.DIRT, 1)
        Blocks.DIRT_PATH.defaultBlockState().planCost() shouldBe PlanCost.Items(Items.DIRT, 1)
    }

    @Test
    fun `leaves that can decay wait for the solids, kept leaves do not`() {
        Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false).cellKind() shouldBe CellKind.WEAK
        Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true).cellKind() shouldBe CellKind.SOLID
    }

    @Test
    fun `a bell is attached`() {
        Blocks.BELL.defaultBlockState().cellKind() shouldBe CellKind.ATTACHED
    }

    @Test
    fun `a waterlogged plan block goes in dry`() {
        val wet = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.WATERLOGGED, true)
        wet.withoutWater() shouldBe Blocks.OAK_STAIRS.defaultBlockState()
        Blocks.STONE.defaultBlockState().withoutWater() shouldBe Blocks.STONE.defaultBlockState()
    }

    @Test
    fun `a powered plan block goes in unpowered and keeps its facing`() {
        val door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
        door.setValue(DoorBlock.POWERED, true).setValue(DoorBlock.OPEN, true).withoutPower() shouldBe door.setValue(DoorBlock.OPEN, true)
        Blocks.REDSTONE_WIRE.defaultBlockState().setValue(BlockStateProperties.POWER, 15).withoutPower() shouldBe
            Blocks.REDSTONE_WIRE.defaultBlockState()
        val dispenser = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN)
        dispenser.setValue(BlockStateProperties.TRIGGERED, true).withoutPower() shouldBe dispenser
        Blocks.HOPPER.defaultBlockState().setValue(BlockStateProperties.ENABLED, false).withoutPower() shouldBe
            Blocks.HOPPER.defaultBlockState()
    }

    @Test
    fun `a lever keeps its planned position but a button goes in off`() {
        val lever = Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.POWERED, true)
        lever.withoutPower() shouldBe lever
        planMatches(lever, Blocks.LEVER.defaultBlockState()) shouldBe false
        Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.POWERED, true).withoutPower() shouldBe
            Blocks.STONE_BUTTON.defaultBlockState()
        Blocks.STONE_PRESSURE_PLATE.defaultBlockState().setValue(BlockStateProperties.POWERED, true).withoutPower() shouldBe
            Blocks.STONE_PRESSURE_PLATE.defaultBlockState()
    }

    @Test
    fun `a player-set power property keeps its plan value and the rest reset`() {
        val dialSet = setOf<Property<*>>(BlockStateProperties.POWER)
        val plate = Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE.defaultBlockState().setValue(BlockStateProperties.POWER, 9)
        plate.withoutPower(dialSet) shouldBe plate
        plate.withoutPower(emptySet()) shouldBe Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE.defaultBlockState()
        Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.POWERED, true).withoutPower(dialSet) shouldBe
            Blocks.STONE_BUTTON.defaultBlockState()
    }

    @Test
    fun `power and LIT do not count against a plan match`() {
        val lamp = Blocks.REDSTONE_LAMP.defaultBlockState()
        planMatches(lamp, lamp.setValue(BlockStateProperties.LIT, true)) shouldBe true
        val rail = Blocks.POWERED_RAIL.defaultBlockState()
        planMatches(rail, rail.setValue(BlockStateProperties.POWERED, true)) shouldBe true
        fixableInPlace(rail, rail.setValue(BlockStateProperties.POWERED, true)) shouldBe false
    }
}
