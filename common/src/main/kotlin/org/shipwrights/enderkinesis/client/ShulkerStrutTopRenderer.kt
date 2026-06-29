package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.blockentity.ShulkerStrutTopBlockEntity

/**
 * Draws the lid (top) shulker shell at the [ShulkerStrutTopBlockEntity]'s block. Uses the
 * vanilla `ShulkerModel.lid` [ModelPart] so the curved dome shape matches a real shulker
 * box — picked through [ModelLayers.SHULKER] which is registered by vanilla so no model
 * layer registration is needed on our side.
 *
 * VS2's render hook applies the ship transform to the pose stack before invoking BERs on
 * ship blocks, so this renderer draws in the ship's local frame and ends up at the lid's
 * world position automatically.
 */
class ShulkerStrutTopRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ShulkerStrutTopBlockEntity> {

    private val lidModel: ModelPart

    init {
        val root = ctx.bakeLayer(ModelLayers.SHULKER)
        lidModel = root.getChild("lid")
    }

    override fun render(
        be: ShulkerStrutTopBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        val facing: Direction = be.blockState.getValue(BlockStateProperties.FACING)
        val vc = buffers.getBuffer(shulkerRenderType(be.dyeColor))

        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.scale(0.9995f, 0.9995f, 0.9995f)
        pose.mulPose(facing.rotation)
        pose.scale(1.0f, -1.0f, -1.0f)
        pose.translate(0.0, -1.0, 0.0)
        lidModel.render(pose, vc, light, overlay)
        pose.popPose()
    }

    private companion object {
        private val SHULKER_TEXTURE = ResourceLocation("textures/entity/shulker/shulker.png")
        private val SHULKER_RENDER_TYPE: RenderType = RenderType.entityCutoutNoCull(SHULKER_TEXTURE)

        private val COLORED_TYPES: Map<DyeColor, RenderType> = DyeColor.values().associateWith { c ->
            RenderType.entityCutoutNoCull(
                ResourceLocation("textures/entity/shulker/shulker_${c.getName()}.png")
            )
        }

        fun shulkerRenderType(color: DyeColor?): RenderType =
            color?.let { COLORED_TYPES[it] } ?: SHULKER_RENDER_TYPE
    }
}
