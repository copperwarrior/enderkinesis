package org.shipwrights.enderkinesis.construction

import com.google.gson.JsonParser
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClearRulesTest {
    private companion object {
        const val TAG_DEPTH = 8
    }

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val stone = Blocks.STONE.defaultBlockState()
    private val air = Blocks.AIR.defaultBlockState()
    private val stairs = Blocks.OAK_STAIRS.defaultBlockState()

    // Bootstrap binds no tags, so the test merges every copy of the tag on the classpath, as a data load does.
    private val dirtIds: Set<ResourceLocation> by lazy { blockTag(ResourceLocation("minecraft", "dirt"), TAG_DEPTH) }

    private fun blockTag(tag: ResourceLocation, depth: Int): Set<ResourceLocation> {
        check(depth > 0) { "tag nesting too deep at $tag" }
        val ids = HashSet<ResourceLocation>()
        val copies = javaClass.classLoader.getResources("data/${tag.namespace}/tags/blocks/${tag.path}.json")
        for (url in copies) {
            val json = url.openStream().reader().use { JsonParser.parseReader(it).asJsonObject }
            if (json.get("replace")?.asBoolean == true) ids.clear()
            for (value in json.getAsJsonArray("values")) {
                val id = if (value.isJsonObject) value.asJsonObject.get("id").asString else value.asString
                if (id.startsWith("#")) ids += blockTag(ResourceLocation(id.substring(1)), depth - 1) else ids += ResourceLocation(id)
            }
        }
        return ids
    }

    private fun matches(plan: Block, world: Block) =
        planMatches(plan.defaultBlockState(), world.defaultBlockState()) { BuiltInRegistries.BLOCK.getKey(it.block) in dirtIds }

    @Test
    fun `any dirt counts as dirt, and grass and dirt count as each other`() {
        matches(Blocks.DIRT, Blocks.COARSE_DIRT) shouldBe true
        matches(Blocks.DIRT, Blocks.PODZOL) shouldBe true
        matches(Blocks.DIRT, Blocks.GRASS_BLOCK) shouldBe true
        matches(Blocks.GRASS_BLOCK, Blocks.DIRT) shouldBe true
        matches(Blocks.PODZOL, Blocks.DIRT) shouldBe false
        matches(Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT) shouldBe false
        matches(Blocks.DIRT, Blocks.STONE) shouldBe false
    }

    @Test
    fun `the Wohlon soils are in the dirt tag, so they count as dirt`() {
        // Mod blocks are not registered in a unit test, so this checks the merged tag by id.
        dirtIds.contains(ResourceLocation("enderkinesis", "wohlon_mud")) shouldBe true
        dirtIds.contains(ResourceLocation("enderkinesis", "wohlon_moss")) shouldBe true
    }

    @Test
    fun `every kind of air is air, and water in the world block is ignored`() {
        planMatches(air, Blocks.CAVE_AIR.defaultBlockState()) shouldBe true
        planMatches(air, stone) shouldBe false
        planMatches(stairs, stairs.setValue(StairBlock.WATERLOGGED, true)) shouldBe true
    }

    @Test
    fun `the right block in the wrong state is fixed in place`() {
        val turned = stairs.setValue(StairBlock.FACING, Direction.EAST)
        fixableInPlace(stairs, turned) shouldBe true
        fixableInPlace(stairs, stairs) shouldBe false
        fixableInPlace(stairs, stone) shouldBe false
    }

    @Test
    fun `a fix that would change the item count or a two-part block is no fix`() {
        val single = Blocks.OAK_SLAB.defaultBlockState()
        val double = single.setValue(SlabBlock.TYPE, SlabType.DOUBLE)
        fixableInPlace(double, single) shouldBe false
        fixableInPlace(single.setValue(SlabBlock.TYPE, SlabType.TOP), single) shouldBe true
        val door = Blocks.OAK_DOOR.defaultBlockState()
        fixableInPlace(door, door.setValue(DoorBlock.FACING, Direction.EAST)) shouldBe false
    }

    @Test
    fun `a fix keeps the world's water`() {
        val wetTurned = stairs.setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.WATERLOGGED, true)
        fixedState(stairs, wetTurned) shouldBe stairs.setValue(StairBlock.WATERLOGGED, true)
    }

    @Test
    fun `plan air clears solids and plants and scoops fluid sources only`() {
        clearKind(null, air) shouldBe ClearKind.NONE
        clearKind(null, stone) shouldBe ClearKind.CUT
        clearKind(null, Blocks.GRASS.defaultBlockState()) shouldBe ClearKind.CUT
        clearKind(null, Blocks.WATER.defaultBlockState()) shouldBe ClearKind.SCOOP
        clearKind(null, Blocks.LAVA.defaultBlockState()) shouldBe ClearKind.SCOOP
        clearKind(null, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3)) shouldBe ClearKind.NONE
        clearKind(null, stairs.setValue(StairBlock.WATERLOGGED, true)) shouldBe ClearKind.CUT
    }

    @Test
    fun `a plan block clears a solid in its way but goes over replaceable blocks and fluids`() {
        clearKind(stone, Blocks.DIRT.defaultBlockState()) shouldBe ClearKind.CUT
        clearKind(stone, stone) shouldBe ClearKind.NONE
        clearKind(stone, Blocks.GRASS.defaultBlockState()) shouldBe ClearKind.NONE
        clearKind(stone, Blocks.WATER.defaultBlockState()) shouldBe ClearKind.NONE
        clearKind(stairs, stairs.setValue(StairBlock.FACING, Direction.EAST)) shouldBe ClearKind.NONE
    }

    @Test
    fun `a plan block that is never built keeps the world block`() {
        clearKind(Blocks.WATER.defaultBlockState(), stone) shouldBe ClearKind.NONE
    }

    @Test
    fun `the other half of a door and a bed`() {
        val pos = BlockPos(0, 5, 0)
        Blocks.OAK_DOOR.defaultBlockState().otherHalf(pos) shouldBe pos.above()
        Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).otherHalf(pos) shouldBe pos.below()
        val foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
        foot.otherHalf(pos) shouldBe pos.north()
        foot.setValue(BedBlock.PART, BedPart.HEAD).otherHalf(pos) shouldBe pos.south()
        stone.otherHalf(pos) shouldBe null
    }

    @Test
    fun `a world position maps back to its plan cell under every rotation and mirror`() {
        val local = BlockPos(3, 2, 5)
        for (rotation in Rotation.entries) for (mirror in Mirror.entries) {
            val anchor = PlanAnchor(Level.OVERWORLD, BlockPos(100, 64, -40), rotation, mirror)
            anchor.cellLocalPos(anchor.cellWorldPos(local)) shouldBe local
        }
    }

    @Test
    fun `a cut carries one plain block stack and drops the rest`() {
        val y = splitYield(listOf(ItemStack(Items.COBBLESTONE, 1), ItemStack(Items.FLINT, 1), ItemStack(Items.COBBLESTONE, 2)))
        y.carry.item shouldBe Items.COBBLESTONE
        y.carry.count shouldBe 3
        y.rest.map { it.item } shouldBe listOf(Items.FLINT)
    }

    @Test
    fun `a cut with no block drop carries nothing`() {
        val y = splitYield(listOf(ItemStack(Items.DIAMOND)))
        y.carry.isEmpty shouldBe true
        y.rest.size shouldBe 1
        splitYield(emptyList()).carry.isEmpty shouldBe true
    }

    @Test
    fun `a drop with data stays out of the clawhod`() {
        val named = ItemStack(Items.WHITE_BANNER).also { it.orCreateTag.putInt("Probe", 1) }
        val y = splitYield(listOf(named))
        y.carry.isEmpty shouldBe true
        y.rest.single() shouldBe named
    }

    @Test
    fun `a carry past the stack size drops the excess`() {
        val y = splitYield(listOf(ItemStack(Items.DIRT, 64), ItemStack(Items.DIRT, 10)))
        y.carry.count shouldBe 64
        y.rest.single().count shouldBe 10
    }
}
