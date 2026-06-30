package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.model.EntityModel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import org.shipwrights.enderkinesis.block.StatueBlock
import org.shipwrights.enderkinesis.block.StatueKind
import org.shipwrights.enderkinesis.blockentity.StatueBlockEntity
import org.shipwrights.enderkinesis.client.model.StatueBlackGoatModel
import org.shipwrights.enderkinesis.client.model.StatueCatalogerModel
import org.shipwrights.enderkinesis.client.model.StatueCountingModel
import org.shipwrights.enderkinesis.client.model.StatueSteveModel
import org.shipwrights.enderkinesis.client.model.StatueTentacledBeastModel
import org.shipwrights.enderkinesis.client.model.StatueTentaclesModel
import org.shipwrights.enderkinesis.client.model.StatueYellowTowerModel

/**
 * Draws the Blockbench-style entity model for each statue kind at the BE's block,
 * rotated to face the block's [HorizontalDirectionalBlock.FACING] state.
 *
 * The standard "BB entity model in a block" pose: translate to block centre + 1.5
 * blocks up, scale (-1, -1, 1) to flip BB's +Y-up / +X-right convention into MC's
 * world frame. Yaw is applied **before** the scale-flip so it rotates around world
 * +Y; passing [HorizontalDirectionalBlock.FACING].toYRot() unmodified places the
 * statue's front (model -Z after the flip) toward FACING.
 */
class StatueBlockEntityRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<StatueBlockEntity> {

    private val models: Map<StatueKind, EntityModel<Entity>> = mapOf(
        StatueKind.STEVE           to StatueSteveModel(ctx.bakeLayer(StatueSteveModel.LAYER_LOCATION)),
        StatueKind.CATALOGER       to StatueCatalogerModel(ctx.bakeLayer(StatueCatalogerModel.LAYER_LOCATION)),
        StatueKind.TENTACLES       to StatueTentaclesModel(ctx.bakeLayer(StatueTentaclesModel.LAYER_LOCATION)),
        StatueKind.TENTACLED_BEAST to StatueTentacledBeastModel(ctx.bakeLayer(StatueTentacledBeastModel.LAYER_LOCATION)),
        StatueKind.YELLOW_TOWER    to StatueYellowTowerModel(ctx.bakeLayer(StatueYellowTowerModel.LAYER_LOCATION)),
        StatueKind.BLACK_GOAT      to StatueBlackGoatModel(ctx.bakeLayer(StatueBlackGoatModel.LAYER_LOCATION)),
        StatueKind.COUNTING        to StatueCountingModel(ctx.bakeLayer(StatueCountingModel.LAYER_LOCATION)),
    )

    override fun render(
        be: StatueBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val state = be.blockState
        val block = state.block as? StatueBlock ?: return
        val model = models[block.kind] ?: return

        pose.pushPose()
        pose.translate(0.5, 1.5, 0.5)
        val facing = state.getValue(HorizontalDirectionalBlock.FACING)
        // `+ 180` so the statue's *front* (model -Z after the scale flip below) points
        // toward FACING — without it the figure looks the opposite way from the block's
        // facing arrow.
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot() + 180f))
        pose.scale(-1.0f, -1.0f, 1.0f)

        // entityTranslucentCull: alpha-aware (so the texture's transparency is honoured)
        // *and* back-face-culled (vanilla Blockbench models expect single-sided faces;
        // without culling, near-coplanar parts z-fight).
        val consumer = buffers.getBuffer(RenderType.entityTranslucentCull(block.kind.texture))
        model.renderToBuffer(pose, consumer, packedLight, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f)
        pose.popPose()
    }
}
