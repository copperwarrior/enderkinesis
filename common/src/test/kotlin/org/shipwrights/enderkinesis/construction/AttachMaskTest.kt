package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ChainBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.WallTorchBlock
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Which plan neighbour an attached block waits for, from the block's own properties. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttachMaskTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `sturdy blocks stand on their own`() {
        attachMask(Blocks.PURPUR_BLOCK.defaultBlockState()) shouldBe 0
        attachMask(Blocks.PURPUR_STAIRS.defaultBlockState()) shouldBe 0
        attachMask(Blocks.PURPUR_SLAB.defaultBlockState()) shouldBe 0
    }

    @Test
    fun `a chain on a vertical axis hangs from the block over it`() {
        attachMask(Blocks.CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, Direction.Axis.Y)) shouldBe attachBit(Direction.UP)
    }

    @Test
    fun `a lantern hangs or stands by its hanging property`() {
        attachMask(Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true)) shouldBe attachBit(Direction.UP)
        attachMask(Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false)) shouldBe attachBit(Direction.DOWN)
    }

    @Test
    fun `a wall torch sticks to the block behind it`() {
        val torch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.NORTH)
        attachMask(torch) shouldBe attachBit(Direction.SOUTH)
    }

    @Test
    fun `a button follows its attach face`() {
        val button = Blocks.STONE_BUTTON.defaultBlockState()
        attachMask(button.setValue(ButtonBlock.FACE, AttachFace.CEILING)) shouldBe attachBit(Direction.UP)
        attachMask(button.setValue(ButtonBlock.FACE, AttachFace.FLOOR)) shouldBe attachBit(Direction.DOWN)
        attachMask(button.setValue(ButtonBlock.FACE, AttachFace.WALL).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)) shouldBe
            attachBit(Direction.WEST)
    }

    @Test
    fun `an end rod sticks to the block it points away from`() {
        attachMask(Blocks.END_ROD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP)) shouldBe attachBit(Direction.DOWN)
    }

    @Test
    fun `a floor dragon head names no support of its own`() {
        attachMask(Blocks.DRAGON_HEAD.defaultBlockState()) shouldBe ATTACH_UNNAMED
    }
}
