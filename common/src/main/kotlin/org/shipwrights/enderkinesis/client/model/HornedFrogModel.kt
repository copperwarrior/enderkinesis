package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.AnimationDefinition
import net.minecraft.client.animation.KeyframeAnimations
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.util.Mth
import net.minecraft.world.entity.AnimationState
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.HornedFrogEntity
import org.shipwrights.enderkinesis.util.MILLIS_PER_TICK

/** Generated from `SwampFrog.bbmodel`. */
class HornedFrogModel(private val bakedRoot: ModelPart) : CachedHierarchicalModel<HornedFrogEntity>() {

    override fun root(): ModelPart = bakedRoot

    private val body = bakedRoot.getChild("Root").getChild("Body")
    private val tongue = body.getChild("Tongue")

    override fun setupAnim(
        entity: HornedFrogEntity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        resetAllParts()
        // A swimming frog keeps paddling under the strike; clips add, so the tongue layers on SwimIdle.
        val base = if (entity.isInWater) HornedFrogAnimations.swimIdle else null
        if (playOnce(entity.tongueAnimationState, HornedFrogAnimations.tongue, TONGUE_MILLIS, ageInTicks, base)) {
            aimTongue(entity, ageInTicks - entity.tickCount)
            return
        }
        if (play(entity.jumpAnimationState, HornedFrogAnimations.jump, ageInTicks) ||
            play(entity.fallAnimationState, HornedFrogAnimations.fall, ageInTicks)
        ) {
            return
        }
        val dx = entity.x - entity.xo
        val dz = entity.z - entity.zo
        val moving = dx * dx + dz * dz > MOVING_SPEED_SQR
        val clip: AnimationDefinition = when {
            entity.isInWater && moving -> HornedFrogAnimations.swimming
            entity.isInWater -> HornedFrogAnimations.swimIdle
            moving -> HornedFrogAnimations.walk
            else -> HornedFrogAnimations.idle
        }
        KeyframeAnimations.animate(this, clip, (ageInTicks * MILLIS_PER_TICK).toLong(), 1f, ANIMATION_VECTOR_CACHE)
    }

    /**
     * Layers an aim on the Tongue clip: the body takes part of the turn, the tongue the rest, and the tongue's
     * reach scales so that its tip, fully out, lands on the prey. Angles are clamped to stay natural.
     */
    private fun aimTongue(entity: HornedFrogEntity, partialTick: Float) {
        if (!entity.hasTongueAim) return
        val yaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot) * Mth.DEG_TO_RAD
        val dx = (entity.tongueAimX - Mth.lerp(partialTick.toDouble(), entity.xo, entity.x)).toFloat()
        val dy = (entity.tongueAimY - Mth.lerp(partialTick.toDouble(), entity.yo, entity.y)).toFloat()
        val dz = (entity.tongueAimZ - Mth.lerp(partialTick.toDouble(), entity.zo, entity.z)).toFloat()
        // World to model pixels: model -Z is forward, +Y is down, Y = 24 is the ground; babies render at half scale.
        val pixels = PIXELS_PER_BLOCK / entity.scale
        val sin = Mth.sin(yaw)
        val cos = Mth.cos(yaw)
        val vx = (dx * cos + dz * sin) * pixels - TONGUE_PIVOT_X
        val vy = MODEL_GROUND - dy * pixels - TONGUE_PIVOT_Y
        val vz = -(dz * cos - dx * sin) * pixels - TONGUE_PIVOT_Z
        val horizontal = Mth.sqrt(vx * vx + vz * vz)
        val length = Mth.sqrt(horizontal * horizontal + vy * vy)
        if (length < MIN_AIM_LENGTH) return
        // xRot then yRot turn the -Z tongue to (vx, vy, vz).
        val pitch = Mth.clamp(Mth.atan2(vy.toDouble(), horizontal.toDouble()).toFloat(), MIN_PITCH, MAX_PITCH)
        val turn = Mth.clamp(Mth.atan2(-vx.toDouble(), -vz.toDouble()).toFloat(), -MAX_TURN, MAX_TURN)
        body.xRot += pitch * BODY_SHARE
        body.yRot += turn * BODY_SHARE
        val tonguePitch = pitch * (1f - BODY_SHARE)
        val tongueTurn = turn * (1f - BODY_SHARE)
        tongue.xRot += tonguePitch
        tongue.yRot += tongueTurn
        val reach = Mth.clamp(length / FULL_TIP_PIXELS, MIN_REACH_SCALE, MAX_REACH_SCALE)
        // The clip pushes the tongue out along model -Z; turn that push along the aim and scale it with the reach.
        val push = (TONGUE_REST_Z - tongue.z) * reach
        val flat = Mth.cos(tonguePitch)
        tongue.x = TONGUE_REST_X - flat * Mth.sin(tongueTurn) * push
        tongue.y = TONGUE_REST_Y + Mth.sin(tonguePitch) * push
        tongue.z = TONGUE_REST_Z - flat * Mth.cos(tongueTurn) * push
        tongue.zScale *= reach
    }

    // Jump and Fall are Blockbench "hold" clips: past their end, vanilla keeps the last keyframe.
    private fun play(state: AnimationState, clip: AnimationDefinition, ageInTicks: Float): Boolean {
        if (!state.isStarted) return false
        state.updateTime(ageInTicks, 1f)
        KeyframeAnimations.animate(this, clip, state.accumulatedTime, 1f, ANIMATION_VECTOR_CACHE)
        return true
    }

    private fun playOnce(
        state: AnimationState, clip: AnimationDefinition, lengthMillis: Long, ageInTicks: Float, base: AnimationDefinition?,
    ): Boolean {
        if (!state.isStarted) return false
        state.updateTime(ageInTicks, 1f)
        if (state.accumulatedTime >= lengthMillis) {
            state.stop()
            return false
        }
        if (base != null) {
            KeyframeAnimations.animate(this, base, (ageInTicks * MILLIS_PER_TICK).toLong(), 1f, ANIMATION_VECTOR_CACHE)
        }
        KeyframeAnimations.animate(this, clip, state.accumulatedTime, 1f, ANIMATION_VECTOR_CACHE)
        return true
    }

    companion object {
        private val ANIMATION_VECTOR_CACHE = Vector3f()
        private const val MOVING_SPEED_SQR = 1.0e-4
        private const val MILLIS_PER_SECOND = 1000f
        private const val PIXELS_PER_BLOCK = 16f
        private const val MODEL_GROUND = 24f
        // Tongue pivot in model space: Root (0, 23, 2) + Body (0, 0, 0) + Tongue (0, -4, 3).
        private const val TONGUE_PIVOT_X = 0f
        private const val TONGUE_PIVOT_Y = 19f
        private const val TONGUE_PIVOT_Z = 5f
        // Tongue offset in Body.
        private const val TONGUE_REST_X = 0f
        private const val TONGUE_REST_Y = -4f
        private const val TONGUE_REST_Z = 3f
        // Clip at full reach: pushed 3 px, and the 10 px tongue scaled 4.2×.
        private const val FULL_TIP_PIXELS = 45f
        private const val MIN_REACH_SCALE = 0.2f
        private const val MAX_REACH_SCALE = 1.6f
        private const val MIN_AIM_LENGTH = 1f
        private const val BODY_SHARE = 0.35f
        private const val MIN_PITCH = -1.05f
        private const val MAX_PITCH = 0.5f
        private const val MAX_TURN = 1.05f
        private val TONGUE_MILLIS = (HornedFrogAnimations.tongue.lengthInSeconds() * MILLIS_PER_SECOND).toLong()

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id(HornedFrogEntity.ID_PATH), "main")

        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val rootBone = root.addOrReplaceChild("Root", CubeListBuilder.create(), PartPose.offset(0.0f, 23.0f, 2.0f))
            rootBone.addOrReplaceChild("LeftArm", CubeListBuilder.create().texOffs(0, 36).addBox(-1.0f, 0.0f, -2.0f, 3.0f, 3.0f, 3.0f, CubeDeformation(0.0f)).texOffs(24, 3).addBox(0.0f, 3.0f, -4.0f, 4.0f, 0.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(4.5f, -2.0f, -3.0f))
            rootBone.addOrReplaceChild("RightArm", CubeListBuilder.create().texOffs(24, 0).addBox(-4.0f, 3.0f, -4.0f, 4.0f, 0.0f, 3.0f, CubeDeformation(0.0f)).texOffs(12, 39).addBox(-2.0f, 0.0f, -2.0f, 3.0f, 3.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(-4.5f, -2.0f, -3.0f))
            rootBone.addOrReplaceChild("LeftLeg", CubeListBuilder.create().texOffs(31, 10).addBox(0.0f, 4.0f, -5.0f, 5.0f, 0.0f, 5.0f, CubeDeformation(0.0f)).texOffs(31, 34).addBox(-1.0f, 0.0f, -3.0f, 3.0f, 4.0f, 5.0f, CubeDeformation(0.0f)), PartPose.offset(4.5f, -3.0f, 3.0f))
            rootBone.addOrReplaceChild("RightLeg", CubeListBuilder.create().texOffs(20, 30).addBox(-2.0f, 0.0f, -3.0f, 3.0f, 4.0f, 5.0f, CubeDeformation(0.0f)).texOffs(21, 15).addBox(-5.0f, 4.0f, -5.0f, 5.0f, 0.0f, 5.0f, CubeDeformation(0.0f)), PartPose.offset(-4.5f, -3.0f, 3.0f))
            val body = rootBone.addOrReplaceChild("Body", CubeListBuilder.create().texOffs(0, 15).addBox(-4.5f, -7.0f, 0.0f, 9.0f, 8.0f, 4.0f, CubeDeformation(0.0f)).texOffs(31, 30).addBox(1.5f, -10.0f, 4.0f, 5.0f, 3.0f, 0.0f, CubeDeformation(0.0f)).texOffs(0, 27).addBox(-6.5f, -10.0f, 4.0f, 5.0f, 3.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 0.0f, 0.0f))
            val head = body.addOrReplaceChild("Head", CubeListBuilder.create().texOffs(19, 20).addBox(-3.5f, -2.0f, -6.0f, 7.0f, 3.0f, 7.0f, CubeDeformation(0.0f)).texOffs(0, 20).addBox(0.0f, -7.0f, -8.0f, 0.0f, 6.0f, 10.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, -6.0f, 1.0f))
            head.addOrReplaceChild("EyeL", CubeListBuilder.create().texOffs(4, 6).addBox(0.0f, -3.0f, -1.0f, 2.0f, 2.0f, 0.0f, CubeDeformation(0.0f)).texOffs(0, 3).addBox(0.0f, -1.0f, -1.0f, 2.0f, 1.0f, 2.0f, CubeDeformation(0.0f)), PartPose.offset(1.5f, -2.0f, -5.0f))
            head.addOrReplaceChild("EyeR", CubeListBuilder.create().texOffs(0, 0).addBox(-2.0f, -1.0f, -1.0f, 2.0f, 1.0f, 2.0f, CubeDeformation(0.0f)).texOffs(0, 6).addBox(-2.0f, -3.0f, -1.0f, 2.0f, 2.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(-1.5f, -2.0f, -5.0f))
            body.addOrReplaceChild("Tongue", CubeListBuilder.create().texOffs(26, 0).addBox(-1.5f, 0.0f, -10.0f, 3.0f, 0.0f, 10.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, -4.0f, 3.0f))
            body.addOrReplaceChild("Mouth", CubeListBuilder.create().texOffs(0, 0).addBox(-4.5f, -6.0f, -9.0f, 9.0f, 6.0f, 9.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 1.0f, 0.0f))
            return LayerDefinition.create(mesh, 64, 64)
        }
    }
}
