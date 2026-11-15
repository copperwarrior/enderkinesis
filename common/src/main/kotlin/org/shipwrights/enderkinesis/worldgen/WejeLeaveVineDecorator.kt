package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecoratorType
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKFeatures

/** Vanilla `LeaveVineDecorator` with Weje Vines; vanilla `Context.placeVine` hard-codes `Blocks.VINE`. */
class WejeLeaveVineDecorator(private val probability: Float) : TreeDecorator() {
    override fun type(): TreeDecoratorType<*> = EKFeatures.WEJE_LEAVE_VINE.get()

    override fun place(context: Context) {
        val random = context.random()
        for (leaf in context.leaves()) {
            for (dir in SIDES) {
                if (random.nextFloat() >= probability) continue
                val side = leaf.relative(dir)
                if (context.isAir(side)) hang(side, VineBlock.getPropertyForFace(dir.opposite), context)
            }
        }
    }

    private fun hang(top: BlockPos, face: BooleanProperty, context: Context) {
        val vine = EKBlocks.WEJE_VINE.get().defaultBlockState().setValue(face, true)
        context.setBlock(top, vine)
        var pos = top.below()
        var left = HANG_LENGTH
        while (left > 0 && context.isAir(pos)) {
            context.setBlock(pos, vine)
            pos = pos.below()
            left--
        }
    }

    companion object {
        val CODEC: Codec<WejeLeaveVineDecorator> = Codec.floatRange(0f, 1f).fieldOf("probability")
            .xmap(::WejeLeaveVineDecorator) { it.probability }.codec()
        private const val HANG_LENGTH = 4
        private val SIDES = arrayOf(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH)
    }
}
