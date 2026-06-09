package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.Util
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.HeartOfTheWildBlock
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity
import org.shipwrights.enderkinesis.client.model.HeartOfTheWildModel

/**
 * Mother heart: fixed pose + scale. Normal hearts: position-hashed yaw/rate/size (client-side,
 * no server sync). Animation clock is wall-clock [Util.getMillis] (continues during pause,
 * gives each viewer a slightly different phase — reads as "alive").
 */
class HeartOfTheWildRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<HeartOfTheWildBlockEntity> {

    private val model = HeartOfTheWildModel(ctx.bakeLayer(HeartOfTheWildModel.LAYER_LOCATION))

    override fun render(
        be: HeartOfTheWildBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val isMother = be.blockState.getValue(HeartOfTheWildBlock.MOTHER)

        val sizeScalar: Float
        val rateScalar: Float
        val yawRad: Float
        if (isMother) {
            sizeScalar = MOTHER_SIZE_SCALE
            rateScalar = 1.0f
            yawRad = 0f
        } else {
            val h = hashPos(be.blockPos)
            // Three independent 20-bit windows so yaw/rate/size aren't correlated.
            val u1 = (h and 0xFFFFFL).toFloat() / 0x100000L
            val u2 = ((h ushr 20) and 0xFFFFFL).toFloat() / 0x100000L
            val u3 = ((h ushr 40) and 0xFFFFFL).toFloat() / 0x100000L
            yawRad = u1 * (2f * Mth.PI)
            rateScalar = NORMAL_RATE_MIN + u2 * (NORMAL_RATE_MAX - NORMAL_RATE_MIN)
            sizeScalar = NORMAL_SIZE_MIN + u3 * (NORMAL_SIZE_MAX - NORMAL_SIZE_MIN)
        }

        model.animateIdleMillis(Util.getMillis(), rateScalar)

        ps.pushPose()
        // Y scales with size so the model's feet (pose-space Y=1.5) land at the block floor for any scale.
        ps.translate(0.5, 1.5 * sizeScalar.toDouble(), 0.5)
        ps.scale(-sizeScalar, -sizeScalar, sizeScalar)
        if (yawRad != 0f) {
            ps.mulPose(Quaternionf().rotationY(yawRad))
        }

        val consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE))
        model.renderToBuffer(ps, consumer, packedLight, packedOverlay, 1f, 1f, 1f, 1f)

        ps.popPose()
    }

    private fun hashPos(pos: BlockPos): Long {
        var h = pos.x.toLong() * 73856093L xor pos.y.toLong() * 19349663L xor pos.z.toLong() * 83492791L
        h = (h xor (h ushr 33)) * -49064778989728563L
        h = (h xor (h ushr 33)) * -4265267296991594537L
        return h xor (h ushr 33)
    }

    companion object {
        private val TEXTURE: ResourceLocation =
            EnderkinesisMod.id("textures/block/heart_of_the_wild.png")

        /** Mother heart fixed scale. */
        private const val MOTHER_SIZE_SCALE: Float = 2.0f

        private const val NORMAL_RATE_MIN: Float = 0.9f
        private const val NORMAL_RATE_MAX: Float = 1.2f
        private const val NORMAL_SIZE_MIN: Float = 0.98f
        private const val NORMAL_SIZE_MAX: Float = 1.05f
    }
}
