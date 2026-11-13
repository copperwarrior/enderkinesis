package org.shipwrights.enderkinesis.ritual

import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.vine.LOG
import org.shipwrights.enderkinesis.vine.VinePoint
import org.shipwrights.enderkinesis.vine.startVineCapture
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.atan2

/** Ritual of Capturing: a Woggak Vine grows from each [anchorCandles] block of the layout and holds the hit ship. */
class Capturing(private val anchorCandles: Set<Block>) : RitualEffect {

    override fun resolve(context: RitualContext, offered: List<ItemStack>): ItemStack? {
        val level = context.level
        val shipId = context.targetShip ?: return null
        val circleShip = level.getShipManagingPos(context.center)?.id
        // Ring order, so each vine's neighbours in the list are its neighbours in the circle.
        val anchors = context.blocks.filter { it.state.block in anchorCandles }
            .sortedBy { atan2(it.offset.z.toDouble(), it.offset.x.toDouble()) }
            .map { block ->
                val candle: BlockPos = context.center.offset(block.offset)
                // The capture moves the start to the side of the candle that faces the ship.
                VinePoint(candle.below(), Vector3d(candle.x + 0.5, candle.y + ANCHOR_LIFT, candle.z + 0.5), circleShip)
            }
        if (anchors.isEmpty() || !level.startVineCapture(anchors, shipId)) {
            LOG.warn("Ritual of Capturing at {}: no vine could start toward ship {} ({} anchor candles)", context.center, shipId, anchors.size)
            return null
        }
        // As a pedestal rite closes: the center goes out, so one lighting fires the circle once.
        val state = level.getBlockState(context.center)
        if (state.hasProperty(BlockStateProperties.LIT)) level.setBlock(context.center, state.setValue(BlockStateProperties.LIT, false), Block.UPDATE_ALL_IMMEDIATE)
        level.playSound(
            null, context.center.x + 0.5, context.center.y + 0.5, context.center.z + 0.5,
            EKSounds.WOHLON_RITUAL_COMPLETE.get(), SoundSource.BLOCKS, 2f, 1f,
        )
        return ItemStack.EMPTY
    }

    companion object {
        /**
         * Over the top face of the block under the candle. VS pushes a new reel node straight away from the anchor; under
         * 0.3 (one radius plus one diameter) the ground holds it closer than a diameter and the reel never adds the next node.
         */
        private const val ANCHOR_LIFT = 0.35

        fun parse(json: JsonObject): Capturing? =
            parseBlocks(json, "anchor_candles")?.takeIf { it.isNotEmpty() }?.let { Capturing(it.toSet()) }
    }
}
