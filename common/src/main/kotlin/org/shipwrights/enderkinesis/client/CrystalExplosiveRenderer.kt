package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.blockentity.CrystalExplosiveBlockEntity

/** Renders the vanilla end-crystal model (via `ModelLayers.END_CRYSTAL`) scaled down to fit
 *  inside the crepusculite-glass shell. Rotation matches vanilla (3°/tick Y + 60° tilt). */
class CrystalExplosiveRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CrystalExplosiveBlockEntity> {

    private val cube: ModelPart = ctx.bakeLayer(ModelLayers.END_CRYSTAL)

    override fun render(
        be: CrystalExplosiveBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val time = (be.level?.gameTime ?: 0L).toDouble() + partialTick.toDouble()
        val rotation = (time * VANILLA_ROT_DEG_PER_TICK).toFloat()

        val vc = buffers.getBuffer(END_CRYSTAL_TYPE)

        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.scale(SHELL_FIT_SCALE, SHELL_FIT_SCALE, SHELL_FIT_SCALE)
        // Vanilla EndCrystalRenderer applies these same offsets to position the model
        // correctly around the entity's origin. Keep them so the proportions look the
        // same as a real end crystal entity, just shrunk down by SHELL_FIT_SCALE.
        pose.mulPose(Axis.YP.rotationDegrees(rotation))
        pose.mulPose(Quaternionf().setAngleAxis(TILT_RAD, SIN_45, 0f, SIN_45))
        cube.render(pose, vc, packedLight, OverlayTexture.NO_OVERLAY)
        pose.popPose()
    }

    private companion object {
        private val SIN_45 = Math.sin(Math.PI / 4.0).toFloat()
        private val TILT_RAD = Math.toRadians(60.0).toFloat()
        private const val VANILLA_ROT_DEG_PER_TICK = 3.0
        /** Fit factor so the end-crystal model sits comfortably inside a 1×1×1
         *  crepusculite-glass shell. The vanilla entity model is ~2 blocks tall; this
         *  shrinks it to ~0.6 blocks across, leaving margin around the tilt sweep. */
        private const val SHELL_FIT_SCALE = 0.3f
        private val END_CRYSTAL_TYPE: RenderType =
            RenderType.entityCutoutNoCull(ResourceLocation("textures/entity/end_crystal/end_crystal.png"))
    }
}
