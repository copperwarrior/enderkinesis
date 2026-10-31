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
import org.shipwrights.enderkinesis.client.model.HeartOfTheWildAnimations
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
            val u3 = ((h ushr 40) and 0xFFFFFL).toFloat() / 0x100000L
            yawRad = u1 * (2f * Mth.PI)
            rateScalar = pulseRate(h)
            sizeScalar = NORMAL_SIZE_MIN + u3 * (NORMAL_SIZE_MAX - NORMAL_SIZE_MIN)
        }

        val now = Util.getMillis()
        if (isGrowing(be, now)) {
            model.animateGrowthMillis(now - be.growStartMillis)
        } else {
            model.animateBeatMillis(beatClockMillis(be, now), rateScalar)
        }
        HeartOfTheWildTendrils.captureTips(be, model, isMother, sizeScalar, yawRad)

        ps.pushPose()
        poseHeart(ps, isMother, sizeScalar, yawRad)

        val consumer = bufferSource.getBuffer(RenderType.entityTranslucent(TEXTURE))
        model.renderToBuffer(ps, consumer, packedLight, packedOverlay, 1f, 1f, 1f, 1f)

        ps.popPose()
    }

    companion object {
        /** Places the model in block space. A normal heart is scaled and centred to fit its block at any yaw. */
        internal fun poseHeart(ps: PoseStack, mother: Boolean, size: Float, yawRad: Float) {
            val scale = modelScale(mother, size)
            val floorPx = if (mother) MOTHER_FLOOR_PX else FIT_FLOOR_PX
            // Y scales with size so the lowest point of the model lands at the block floor for any scale.
            ps.translate(0.5, floorPx / 16.0 * scale, 0.5)
            ps.scale(-scale, -scale, scale)
            if (mother) return
            if (yawRad != 0f) ps.mulPose(Quaternionf().rotationY(yawRad))
            ps.translate(-FIT_CENTRE_X_PX / 16.0, 0.0, -FIT_CENTRE_Z_PX / 16.0)
        }

        internal fun modelScale(mother: Boolean, size: Float): Float = if (mother) size else size * FIT_SCALE

        internal fun hashPos(pos: BlockPos): Long {
            var h = pos.x.toLong() * 73856093L xor pos.y.toLong() * 19349663L xor pos.z.toLong() * 83492791L
            h = (h xor (h ushr 33)) * -49064778989728563L
            h = (h xor (h ushr 33)) * -4265267296991594537L
            return h xor (h ushr 33)
        }

        /** The pulse rate of a normal heart. [HeartOfTheWildSounds] times the heartbeat to it. */
        internal fun pulseRate(hash: Long): Float {
            val u2 = ((hash ushr 20) and 0xFFFFFL).toFloat() / 0x100000L
            return NORMAL_RATE_MIN + u2 * (NORMAL_RATE_MAX - NORMAL_RATE_MIN)
        }

        internal fun isGrowing(be: HeartOfTheWildBlockEntity, now: Long): Boolean =
            be.growStartMillis != HeartOfTheWildBlockEntity.NOT_GROWN &&
                now - be.growStartMillis < HeartOfTheWildAnimations.SUMMON_MILLIS

        /** The beat loop starts at its rest pose when the growth ends, so the two join without a jump. */
        internal fun beatClockMillis(be: HeartOfTheWildBlockEntity, now: Long): Long =
            if (be.growStartMillis == HeartOfTheWildBlockEntity.NOT_GROWN) now
            else now - be.growStartMillis - HeartOfTheWildAnimations.SUMMON_MILLIS

        internal val TEXTURE: ResourceLocation =
            EnderkinesisMod.id("textures/block/heart_of_the_wild.png")

        /** Mother heart fixed scale. */
        private const val MOTHER_SIZE_SCALE: Float = 2.0f

        private const val MOTHER_FLOOR_PX: Double = 24.0

        // Measured over the Beating loop in model pixels: the model turns inside a circle of radius
        // 13.06 px about (FIT_CENTRE_X_PX, FIT_CENTRE_Z_PX). FIT_SCALE fits that circle in the block at NORMAL_SIZE_MAX.
        private const val FIT_SCALE: Float = 0.584f
        private const val FIT_CENTRE_X_PX: Double = -0.7
        private const val FIT_CENTRE_Z_PX: Double = 0.4
        private const val FIT_FLOOR_PX: Double = 25.0

        private const val NORMAL_RATE_MIN: Float = 0.9f
        private const val NORMAL_RATE_MAX: Float = 1.2f
        private const val NORMAL_SIZE_MIN: Float = 0.98f
        private const val NORMAL_SIZE_MAX: Float = 1.05f
    }
}
