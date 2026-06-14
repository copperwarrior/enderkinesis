package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.block.VoidHookBlock
import org.shipwrights.enderkinesis.blockentity.VoidHookBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Renders the Void Hook's crystal pair using the same nested-cube style as
 * [VoidHarnessRenderer]. Reuses every visual decision (cube sizes, rotation rate, diagonal
 * tilt, brightness boost on power) so a hook reads as a directional sibling of the harness
 * rather than a different block entirely — only the host shell + functional direction
 * differ at the gameplay layer.
 */
class VoidHookRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<VoidHookBlockEntity> {

    override fun render(
        be: VoidHookBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level ?: return
        val power = be.blockState.getValue(VoidHookBlock.POWER)

        val powerFactor = power / 15.0
        val now = level.gameTime + partialTick.toDouble()
        val last = be.clientLastRenderTime
        val dt = if (last < 0.0) 0.0 else (now - last).coerceAtLeast(0.0)
        be.clientLastRenderTime = now
        if (power > 0) {
            be.clientRotationPhase = (be.clientRotationPhase + dt * BASE_ROT_DEG_PER_TICK * powerFactor) % 360.0
        }

        val rotation = be.clientRotationPhase.toFloat()
        val light = if (power > 0) LightTexture.pack(power, 15) else packedLight

        val br = Minecraft.getInstance().blockRenderer
        val outerState = EKBlocks.ANCRITE_GRATE.get().defaultBlockState()
        val innerState = EKBlocks.CREPUSCULITE_BLOCK.get().defaultBlockState()
        val rotRadians = Math.toRadians(rotation.toDouble()).toFloat()

        ps.pushPose()
        ps.translate(0.5, 0.5, 0.5)
        ps.mulPose(Quaternionf().rotationY(rotRadians))
        ps.mulPose(Quaternionf().setAngleAxis(DIAG_TILT_RAD, SIN_45, 0f, SIN_45))
        ps.scale(OUTER_CUBE_SIZE, OUTER_CUBE_SIZE, OUTER_CUBE_SIZE)
        ps.translate(-0.5, -0.5, -0.5)
        br.renderSingleBlock(outerState, ps, bufferSource, light, packedOverlay)
        ps.popPose()

        ps.pushPose()
        ps.translate(0.5, 0.5, 0.5)
        ps.mulPose(Quaternionf().rotationY(rotRadians))
        ps.mulPose(Quaternionf().setAngleAxis(DIAG_TILT_RAD, SIN_45, 0f, SIN_45))
        ps.mulPose(Quaternionf().setAngleAxis(DIAG_TILT_RAD, SIN_45, 0f, SIN_45))
        ps.mulPose(Quaternionf().rotationY(rotRadians))
        ps.scale(INNER_CUBE_SIZE, INNER_CUBE_SIZE, INNER_CUBE_SIZE)
        ps.translate(-0.5, -0.5, -0.5)
        br.renderSingleBlock(innerState, ps, bufferSource, light, packedOverlay)
        ps.popPose()
    }

    companion object {
        private val SIN_45 = Math.sin(Math.PI / 4.0).toFloat()
        private val DIAG_TILT_RAD = (Math.PI / 4.0).toFloat()
        private const val OUTER_CUBE_SIZE = 0.6f
        private const val INNER_CUBE_SIZE = OUTER_CUBE_SIZE * 0.875f
        private const val BASE_ROT_DEG_PER_TICK = 3.0
    }
}
