package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.client.model.ConstriliarModel
import org.shipwrights.enderkinesis.entity.ConstriliarEntity

/**
 * Entity-anchored rendering for the SPAWN INTRO only: entities reach clients instantly,
 * so the block-squash choreography plays without waiting on the ship; the platform BER
 * takes over the moment the intro ends, by which time the ship has had the whole intro
 * to load in. Outside the intro this renders nothing — the BER owns the body.
 */
class ConstriliarRenderer(ctx: EntityRendererProvider.Context) :
    MobRenderer<ConstriliarEntity, ConstriliarModel>(
        ctx,
        ConstriliarModel(ctx.bakeLayer(ConstriliarModel.LAYER_LOCATION)),
        0f,
    ) {

    override fun getTextureLocation(entity: ConstriliarEntity): ResourceLocation =
        ConstriliarBodySkin.TEXTURE

    companion object {
        private const val ROCK_TICKS = 7f
        private const val ROCK_ANGLE = 0.09f
        private const val ROCK_DIP = 0.025f
        private const val PLATE_PIVOT = 0.375
        private val LEG_CORNER_X = floatArrayOf(1f, -1f, -1f, 1f)
        private val LEG_CORNER_Z = floatArrayOf(-1f, -1f, 1f, 1f)
    }

    override fun render(
        entity: ConstriliarEntity,
        entityYaw: Float,
        partialTicks: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ) {
        val animT = entity.spawnAnimTime(partialTicks)
        if (animT < 0f) return

        // Same model-space chain the BER uses, anchored to the entity pose: lift along
        // the surface normal, tilt, vanilla 180°-yaw, mirror, ground-plane shift. The
        // lift comes from the continuous curve at fractional time — the synced-int
        // version quantizes per tick and stutters.
        val up = entity.upVector(partialTicks)
        val lift = entity.spawnAnimLift(animT.toDouble())
        val yaw = Mth.rotLerp(partialTicks, entity.yBodyRotO, entity.yBodyRot)
        entity.gait.solveRenderAngles(entity, partialTicks)
        model.setupAnim(entity, 0f, 0f, 0f, 0f, 0f)

        poseStack.pushPose()
        poseStack.translate(up.x * lift, up.y * lift, up.z * lift)
        poseStack.mulPose(Quaternionf().rotationTo(0f, 1f, 0f, up.x, up.y, up.z))
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - yaw))
        applyLegPopRock(poseStack, animT)
        poseStack.scale(-1f, -1f, 1f)
        poseStack.translate(0.0, -1.501, 0.0)
        ConstriliarBodySkin.renderBody(poseStack, buffer, model, entity, animT, partialTicks, packedLight)
        poseStack.popPose()
    }

    /** Weight-shift as each leg deploys: the body rocks toward the popping leg's corner
     *  with a damped wobble and a small settle dip, pivoting around the plate centre.
     *  Per-leg timing offsets + differing wobble phases keep it organic. */
    private fun applyLegPopRock(poseStack: PoseStack, animT: Float) {
        var pitch = 0f
        var roll = 0f
        var dip = 0f
        for (i in 0..3) {
            val p = (animT - ConstriliarEntity.ANIM_LEG_POP[i]) / ROCK_TICKS
            if (p <= 0f || p >= 1f) continue
            val envelope = Mth.sin(Mth.PI * p)
            val wobble = Mth.sin(p * 9f + i * 1.7f) * envelope * 0.4f
            val angle = ROCK_ANGLE * envelope + wobble * 0.05f
            pitch += angle * LEG_CORNER_Z[i]
            roll += angle * LEG_CORNER_X[i]
            dip += ROCK_DIP * envelope
        }
        if (pitch == 0f && roll == 0f && dip == 0f) return
        poseStack.translate(0.0, PLATE_PIVOT, 0.0)
        poseStack.mulPose(Quaternionf().rotationZYX(roll, 0f, pitch))
        poseStack.translate(0.0, -PLATE_PIVOT - dip, 0.0)
    }
}
