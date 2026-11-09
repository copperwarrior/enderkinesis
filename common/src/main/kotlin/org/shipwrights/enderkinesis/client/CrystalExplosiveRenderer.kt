package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.level.block.Block
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.blockentity.CrystalExplosiveBlockEntity

/** Draws the held end crystal item flat in the socket at the centre of each face. */
class CrystalExplosiveRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CrystalExplosiveBlockEntity> {

    private val itemRenderer: ItemRenderer = ctx.itemRenderer

    private val lightPos = BlockPos.MutableBlockPos()

    override fun render(
        be: CrystalExplosiveBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val crystal = be.crystal
        if (crystal.isEmpty) return
        val level = be.level
        val seed = be.blockPos.asLong().toInt()
        for (i in DIRECTIONS.indices) {
            val face = DIRECTIONS[i]
            // On a ship the BE's level and pos are the shipyard ones, so the neighbour is the ship's own block.
            val light = if (level != null) {
                lightPos.setWithOffset(be.blockPos, face)
                if (!Block.shouldRenderFace(be.blockState, level, be.blockPos, face, lightPos)) continue
                LevelRenderer.getLightColor(level, lightPos)
            } else {
                packedLight
            }
            pose.pushPose()
            pose.translate(0.5, 0.5, 0.5)
            pose.mulPose(FACE_ROTATIONS[i])
            pose.translate(0.0, 0.0, -FACE_OFFSET)
            pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)
            itemRenderer.renderStatic(
                crystal, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, pose, buffers, level, seed,
            )
            pose.popPose()
        }
    }

    private companion object {
        private val DIRECTIONS: Array<Direction> = Direction.values()

        /** A FIXED flat item faces -Z; each rotation turns -Z to the face's outward normal. */
        private val FACE_ROTATIONS: Array<Quaternionf> = Array(DIRECTIONS.size) { i ->
            when (DIRECTIONS[i]) {
                Direction.NORTH -> Quaternionf()
                Direction.SOUTH -> Axis.YP.rotationDegrees(180f)
                Direction.EAST -> Axis.YP.rotationDegrees(-90f)
                Direction.WEST -> Axis.YP.rotationDegrees(90f)
                Direction.UP -> Axis.XP.rotationDegrees(90f)
                Direction.DOWN -> Axis.XP.rotationDegrees(-90f)
            }
        }

        private const val ITEM_SCALE = 0.5f

        /** Depth of the 8x8 socket on each face of the block model (2 px). */
        private const val SOCKET_DEPTH = 2.0 / 16.0

        /** The crystal sits on the socket floor: half a block, less the socket, plus half the scaled item thickness and a gap. */
        private const val FACE_OFFSET = 0.5 - SOCKET_DEPTH + ITEM_SCALE / 32.0 + 0.002
    }
}
