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
import org.shipwrights.enderkinesis.entity.ShilleagkDeerEntity

/** Generated from `SwampDeer(1).bbmodel`. Fix against the source: LegR2 pivot z=10, to match LegL2. */
class ShilleagkDeerModel(private val bakedRoot: ModelPart) : CachedHierarchicalModel<ShilleagkDeerEntity>() {

    private val neck: ModelPart = bakedRoot.getChild("bone").getChild("HEAD")
    private val head: ModelPart = neck.getChild("JustHead")

    init {
        FaceUvCube.attach(bakedRoot.getChild("bone").getChild("HEAD").getChild("JustHead"), FaceUvCube(-3.0f, -1.0f, -2.0f, 4.0f, 1.0f, 5.0f, 0.0f, floatArrayOf(6.0f, 38.0f, 10.0f, 39.0f, 0.0f, 38.0f, 5.0f, 39.0f, 16.0f, 38.0f, 20.0f, 39.0f, 11.0f, 38.0f, 16.0f, 39.0f, 2.0f, 28.0f, 2.0f, 28.0f, 14.0f, 28.0f, 10.0f, 33.0f), TEXTURE_SIZE, TEXTURE_SIZE))
        FaceUvCube.attach(bakedRoot.getChild("bone").getChild("HEAD").getChild("JustHead").getChild("HiTeeth"), FaceUvCube(-3.0f, -2.1f, -1.0f, 4.0f, 2.0f, 4.0f, -0.1f, floatArrayOf(24.0f, 62.0f, 28.0f, 64.0f, 20.0f, 62.0f, 24.0f, 64.0f, 32.0f, 63.0f, 36.0f, 64.0f, 28.0f, 62.0f, 32.0f, 64.0f, 26.0f, 60.0f, 25.0f, 59.0f, 30.0f, 59.0f, 29.0f, 60.0f), TEXTURE_SIZE, TEXTURE_SIZE))
        FaceUvCube.attach(bakedRoot.getChild("bone").getChild("HEAD").getChild("JustHead").getChild("JAW"), FaceUvCube(-2.0f, 0.0f, -4.0f, 4.0f, 1.0f, 4.0f, 0.0f, floatArrayOf(24.0f, 58.0f, 28.0f, 59.0f, 20.0f, 58.0f, 24.0f, 59.0f, 32.0f, 58.0f, 36.0f, 59.0f, 28.0f, 58.0f, 32.0f, 59.0f, 29.0f, 58.0f, 25.0f, 54.0f, 33.0f, 54.0f, 29.0f, 58.0f), TEXTURE_SIZE, TEXTURE_SIZE))
        FaceUvCube.attach(bakedRoot.getChild("bone").getChild("HEAD").getChild("JustHead").getChild("JAW").getChild("LowTeeth"), FaceUvCube(-3.0f, -0.9f, -0.5f, 4.0f, 2.0f, 4.0f, -0.1f, floatArrayOf(24.0f, 61.0f, 28.0f, 63.0f, 20.0f, 61.0f, 24.0f, 63.0f, 32.0f, 63.0f, 36.0f, 64.0f, 28.0f, 61.0f, 32.0f, 63.0f, 26.0f, 60.0f, 25.0f, 59.0f, 30.0f, 59.0f, 29.0f, 60.0f), TEXTURE_SIZE, TEXTURE_SIZE))
    }

    override fun root(): ModelPart = bakedRoot

    override fun setupAnim(
        entity: ShilleagkDeerEntity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        resetAllParts()
        // LivingEntityRenderer clamps limbSwingAmount to 1, which hides the run speed.
        val swingSpeed = entity.walkAnimation.speed((ageInTicks - entity.tickCount).coerceIn(0f, 1f))
        val move = (swingSpeed / WALK_SWING_SPEED).coerceAtMost(1f)
        val run = smootherstep(((swingSpeed - WALK_SWING_SPEED) / (RUN_SWING_SPEED - WALK_SWING_SPEED)).coerceIn(0f, 1f))
        val walkWeight = move * (1f - run)
        val runWeight = move * run
        if (walkWeight > 0f) {
            val millis = (limbSwing * WALK_MILLIS_PER_SWING).toLong()
            KeyframeAnimations.animate(this, ShilleagkDeerAnimations.walk, millis, walkWeight, ANIMATION_VECTOR_CACHE)
        }
        if (runWeight > 0f) {
            val millis = (limbSwing * RUN_MILLIS_PER_SWING).toLong()
            KeyframeAnimations.animate(this, ShilleagkDeerAnimations.prance, millis, runWeight, ANIMATION_VECTOR_CACHE)
        }

        // Non-short-circuit `or`: every running clip must advance and draw.
        play(entity.idleLookBackAnimationState, ShilleagkDeerAnimations.idleLookBack, ageInTicks)
        play(entity.idleTailFlickAnimationState, ShilleagkDeerAnimations.idleTailFlick, ageInTicks)
        play(entity.idleStampAnimationState, ShilleagkDeerAnimations.idleStamp, ageInTicks)
        val posed = play(entity.grazeAnimationState, ShilleagkDeerAnimations.graze, ageInTicks) or
            play(entity.grazeBiteAnimationState, ShilleagkDeerAnimations.grazeBite, ageInTicks) or
            play(entity.attackAntlerAnimationState, ShilleagkDeerAnimations.attackAntler, ageInTicks) or
            play(entity.attackBiteAnimationState, ShilleagkDeerAnimations.attackBite, ageInTicks) or
            play(entity.freakyStartAnimationState, ShilleagkDeerAnimations.freakyStart, ageInTicks) or
            play(entity.freakyLoopAnimationState, ShilleagkDeerAnimations.freakyLoop, ageInTicks) or
            play(entity.freakyEndAnimationState, ShilleagkDeerAnimations.freakyEnd, ageInTicks)
        if (!posed) {
            neck.yRot += netHeadYaw * Mth.DEG_TO_RAD
            head.xRot += headPitch * Mth.DEG_TO_RAD
        }
    }

    private fun play(state: AnimationState, clip: AnimationDefinition, ageInTicks: Float): Boolean {
        if (!state.isStarted) return false
        state.updateTime(ageInTicks, 1f)
        KeyframeAnimations.animate(this, clip, state.accumulatedTime, 1f, ANIMATION_VECTOR_CACHE)
        return true
    }

    companion object {
        private val ANIMATION_VECTOR_CACHE = Vector3f()
        private const val TEXTURE_SIZE = 64f
        private const val MILLIS_PER_TICK = 50f
        private const val WALK_SWING_SPEED = ShilleagkDeerEntity.WALK_GROUND_SPEED * ShilleagkDeerEntity.LIMB_SWING_PER_BLOCK
        private const val RUN_SWING_SPEED = ShilleagkDeerEntity.RUN_GROUND_SPEED * ShilleagkDeerEntity.LIMB_SWING_PER_BLOCK

        // Each clip plays at its authored rate when the deer moves at that gait's design speed.
        private const val WALK_MILLIS_PER_SWING = MILLIS_PER_TICK / WALK_SWING_SPEED
        private const val RUN_MILLIS_PER_SWING = MILLIS_PER_TICK / RUN_SWING_SPEED

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id(ShilleagkDeerEntity.ID_PATH), "main")

        private fun smootherstep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

        @JvmStatic
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val bone = root.addOrReplaceChild("bone", CubeListBuilder.create(), PartPose.offset(0.0f, 24.0f, 0.0f))
            bone.addOrReplaceChild("Tail", CubeListBuilder.create().texOffs(36, 43).addBox(-2.0f, -0.0038f, -0.0872f, 4.0f, 6.0f, 4.0f, CubeDeformation(0.0f)).texOffs(52, 46).addBox(-2.0f, 5.9962f, 3.9128f, 4.0f, 4.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, -21.0f, 12.0f, 0.0873f, 0.0f, 0.0f))
            bone.addOrReplaceChild("BODY", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0f, -5.0f, -9.0f, 8.0f, 10.0f, 18.0f, CubeDeformation(0.0f)).texOffs(48, 28).addBox(-9.0f, -10.0f, 0.0f, 8.0f, 6.0f, 0.0f, CubeDeformation(0.0f)).texOffs(48, 11).addBox(1.0f, -10.0f, 0.0f, 8.0f, 6.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, -17.0f, 3.0f))
            bone.addOrReplaceChild("LegL1", CubeListBuilder.create().texOffs(24, 39).addBox(-1.0f, 0.0f, -1.0f, 3.0f, 12.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(2.0f, -12.0f, -5.0f))
            bone.addOrReplaceChild("LegR1", CubeListBuilder.create().texOffs(12, 39).addBox(-2.0f, 0.0f, -1.0f, 3.0f, 12.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(-2.0f, -12.0f, -5.0f))
            bone.addOrReplaceChild("LegL2", CubeListBuilder.create().texOffs(0, 39).addBox(-1.0f, 0.0f, -1.0f, 3.0f, 12.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(2.0f, -12.0f, 10.0f))
            bone.addOrReplaceChild("LegR2", CubeListBuilder.create().texOffs(36, 28).addBox(-2.0f, 0.0f, -1.0f, 3.0f, 12.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(-2.0f, -12.0f, 10.0f))
            val head = bone.addOrReplaceChild("HEAD", CubeListBuilder.create().texOffs(0, 0).addBox(-2.0f, -8.0f, -3.0f, 4.0f, 8.0f, 5.0f, CubeDeformation(0.0f)).texOffs(52, 17).addBox(-5.0f, -7.0f, -1.0f, 3.0f, 8.0f, 0.0f, CubeDeformation(0.0f)).texOffs(50, 0).addBox(2.0f, -7.0f, -1.0f, 3.0f, 8.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, -18.0f, -6.0f))
            val justhead = head.addOrReplaceChild("JustHead", CubeListBuilder.create().texOffs(20, 28).addBox(-10.0f, -15.0f, 1.0f, 8.0f, 11.0f, 0.0f, CubeDeformation(0.0f)).texOffs(34, 0).addBox(0.0f, -15.0f, 1.0f, 8.0f, 11.0f, 0.0f, CubeDeformation(0.0f)).texOffs(0, 28).addBox(-3.0f, -5.0f, -3.0f, 4.0f, 4.0f, 6.0f, CubeDeformation(0.0f)).texOffs(34, 11).addBox(-3.0f, -2.0f, -6.0f, 4.0f, 1.0f, 3.0f, CubeDeformation(0.0f)).texOffs(8, 13).addBox(1.0f, -3.0f, 2.0f, 4.0f, 5.0f, 0.0f, CubeDeformation(0.0f)).texOffs(0, 13).addBox(-7.0f, -3.0f, 2.0f, 4.0f, 5.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(1.0f, -8.0f, -1.0f))
            justhead.addOrReplaceChild("HiTeeth", CubeListBuilder.create(), PartPose.offset(0.0f, 0.0f, -5.0f))
            val jaw = justhead.addOrReplaceChild("JAW", CubeListBuilder.create(), PartPose.offset(-1.0f, -1.0f, -2.0f))
            jaw.addOrReplaceChild("LowTeeth", CubeListBuilder.create(), PartPose.offset(1.0f, 0.0f, -3.5f))
            val earr = justhead.addOrReplaceChild("EarR", CubeListBuilder.create(), PartPose.offset(-3.0f, -4.0f, 3.0f))
            earr.addOrReplaceChild("EarR_r1", CubeListBuilder.create().texOffs(48, 40).addBox(-3.0f, -5.0f, -1.0f, 3.0f, 5.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.7854f))
            val earl = justhead.addOrReplaceChild("EarL", CubeListBuilder.create(), PartPose.offset(1.0f, -4.0f, 3.0f))
            earl.addOrReplaceChild("EarL_r1", CubeListBuilder.create().texOffs(48, 34).addBox(0.0f, -5.0f, -1.0f, 3.0f, 5.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.7854f))
            return LayerDefinition.create(mesh, 64, 64)
        }
    }
}
