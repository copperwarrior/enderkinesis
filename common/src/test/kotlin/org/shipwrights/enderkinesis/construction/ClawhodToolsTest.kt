package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClawhodToolsTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `a tool breaks faster by its speed`() {
        breakTicks(1.5f, 1f, canHarvest = false) shouldBe 150
        breakTicks(1.5f, 8f, canHarvest = true) shouldBe 6
        breakTicks(0f, 8f, canHarvest = true) shouldBe 1
        breakTicks(-1f, 8f, canHarvest = true) shouldBe -1
    }

    @Test
    fun `efficiency adds level squared plus one only where the tool is fast`() {
        digSpeed(8f, 0) shouldBe 8f
        digSpeed(8f, 3) shouldBe 18f
        digSpeed(1f, 5) shouldBe 1f
    }

    // Bootstrap binds no block tags, so no tool is fast here: TOOL_MATCH needs a loaded datapack.
    @Test
    fun `a pickaxe is the fallback and other tools are not taken for a block they do not suit`() {
        val stone = Blocks.STONE.defaultBlockState()
        val dirt = Blocks.DIRT.defaultBlockState()
        toolRank(ItemStack(Items.IRON_PICKAXE), dirt) shouldBe TOOL_FALLBACK
        toolRank(ItemStack(Items.IRON_SHOVEL), stone) shouldBe 0
        toolRank(ItemStack(Items.STONE), stone) shouldBe 0
    }

    @Test
    fun `only diggers and shears are tools, and the hand finds its tool`() {
        isClawhodTool(ItemStack(Items.SHEARS)) shouldBe true
        isClawhodTool(ItemStack(Items.STICK)) shouldBe false
        val hand = mutableListOf(ItemStack(Items.COBBLESTONE, 4), ItemStack(Items.IRON_AXE))
        handTool(hand).`is`(Items.IRON_AXE) shouldBe true
        handHasItems(listOf(ItemStack.EMPTY, ItemStack(Items.IRON_AXE))) shouldBe false
    }

    @Test
    fun `chain links count, take and make room by item`() {
        val hand = mutableListOf(ItemStack(Items.COAL, 10), ItemStack(Items.COAL, 5))
        slotCount(hand, Items.COAL) shouldBe 15
        slotTake(hand, Items.COAL, 7) shouldBe 7
        slotCount(hand, Items.COAL) shouldBe 8
        relayRoom(hand, Items.COAL) shouldBe 120
        relayRoom(mutableListOf(ItemStack(Items.STONE, 1), ItemStack.EMPTY), Items.COBBLESTONE) shouldBe 0
    }

    @Test
    fun `a container that holds the item is known for like with like`() {
        val chest = SimpleContainer(3)
        chest.setItem(1, ItemStack(Items.COBBLESTONE, 3))
        chest.holdsSame(ItemStack(Items.COBBLESTONE)) shouldBe true
        chest.holdsSame(ItemStack(Items.DIRT)) shouldBe false
    }

    // No block tags in unit tests, so no tool matches: every block is noted for a later pass.
    @Test
    fun `a pass notes each other block once, and the rest pass takes everything`() {
        val passes = ToolPasses(samples = 2)
        val pick = ItemStack(Items.IRON_PICKAXE)
        passes.takes(pick, Blocks.DIRT.defaultBlockState()) shouldBe false
        passes.takes(pick, Blocks.DIRT.defaultBlockState()) shouldBe false
        passes.takes(pick, Blocks.SAND.defaultBlockState()) shouldBe false
        passes.takes(pick, Blocks.GRAVEL.defaultBlockState()) shouldBe false
        passes.otherCount shouldBe 2
        passes.firstFor { if (it.`is`(Blocks.SAND)) it else null }?.`is`(Blocks.SAND) shouldBe true
        passes.startRest()
        passes.otherCount shouldBe 0
        passes.takes(pick, Blocks.DIRT.defaultBlockState()) shouldBe true
        passes.startPass()
        passes.restPass shouldBe false
    }

    @Test
    fun `an instant block needs no tool and is taken in any pass`() {
        val grass = Blocks.GRASS.defaultBlockState()
        needsNoTool(grass) shouldBe true
        needsNoTool(Blocks.DIRT.defaultBlockState()) shouldBe false
        val passes = ToolPasses()
        passes.takes(ItemStack(Items.IRON_AXE), grass) shouldBe true
        passes.otherCount shouldBe 0
    }

    @Test
    fun `a rest pass cuts with a pickaxe but leaves other tools' misfits to bare hands`() {
        restCuts(ItemStack(Items.IRON_PICKAXE), Blocks.OAK_LOG.defaultBlockState()) shouldBe true
        restCuts(ItemStack(Items.SHEARS), Blocks.STONE.defaultBlockState()) shouldBe false
    }

    // No block tags in unit tests: dirt stands for a shovel block and stone for a pickaxe block.
    @Test
    fun `redstone mining takes the near shovel block before the far pickaxe block while a pickaxe is held`() {
        val pick = ItemStack(Items.IRON_PICKAXE)
        val dirt = Blocks.DIRT.defaultBlockState()
        val blocks = mapOf(BlockPos(0, 0, 3) to Blocks.STONE.defaultBlockState(), BlockPos(1, 0, 0) to dirt)
        val cursor = MineCursor(3)
        cursor.restart(0, 0, 0)
        cursor.nextMinable(shellOffsets(3).size / 3) { x, y, z -> BlockPos(x, y, z) in blocks } shouldBe MineScan.FOUND
        BlockPos(cursor.x, cursor.y, cursor.z) shouldBe BlockPos(1, 0, 0)
        // The held pickaxe is not the dirt's tool type, so the hand swaps for a shovel before it breaks the dirt.
        (toolRank(pick, dirt) == TOOL_MATCH) shouldBe false
    }
}
