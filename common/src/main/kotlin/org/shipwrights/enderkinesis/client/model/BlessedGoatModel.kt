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
import org.shipwrights.enderkinesis.entity.BlessedGoatEntity
import org.shipwrights.enderkinesis.util.MILLIS_PER_TICK

/** Generated from `SwampGoat.bbmodel`. */
class BlessedGoatModel(private val bakedRoot: ModelPart) : CachedHierarchicalModel<BlessedGoatEntity>() {

    private val head: ModelPart = bakedRoot.getChild("Head")
    private val hornLeft: ModelPart = head.getChild("HornL")
    private val hornRight: ModelPart = head.getChild("HornR")

    override fun root(): ModelPart = bakedRoot

    override fun setupAnim(
        entity: BlessedGoatEntity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        resetAllParts()
        hornLeft.visible = entity.hasLeftHorn()
        hornRight.visible = entity.hasRightHorn()
        // LivingEntityRenderer clamps limbSwingAmount to 1, which hides the charge speed.
        val swingSpeed = entity.walkAnimation.speed((ageInTicks - entity.tickCount).coerceIn(0f, 1f))
        if (entity.action == BlessedGoatEntity.Action.CHARGE) {
            val millis = (limbSwing * CHARGE_MILLIS_PER_SWING).toLong()
            KeyframeAnimations.animate(this, BlessedGoatAnimations.headButt, millis, 1f, ANIMATION_VECTOR_CACHE)
        } else {
            val move = (swingSpeed / WALK_SWING_SPEED).coerceAtMost(1f)
            if (move > 0f) {
                val millis = (limbSwing * WALK_MILLIS_PER_SWING).toLong()
                KeyframeAnimations.animate(this, BlessedGoatAnimations.walk, millis, move, ANIMATION_VECTOR_CACHE)
            }
        }

        play(entity.idleStampAnimationState, BlessedGoatAnimations.idleStamp, ageInTicks)
        play(entity.idleEarFlickAnimationState, BlessedGoatAnimations.idleEarFlick, ageInTicks)
        // Non-short-circuit `or`: every running clip must advance and draw.
        val headPosed = play(entity.idleHeadShakeAnimationState, BlessedGoatAnimations.idleHeadShake, ageInTicks) or
            play(entity.feedAnimationState, BlessedGoatAnimations.bite, ageInTicks)
        if (!headPosed && entity.action == BlessedGoatEntity.Action.NONE) {
            head.yRot += netHeadYaw * Mth.DEG_TO_RAD
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
        private const val WALK_SWING_SPEED = BlessedGoatEntity.WALK_GROUND_SPEED * BlessedGoatEntity.LIMB_SWING_PER_BLOCK
        private const val CHARGE_SWING_SPEED = BlessedGoatEntity.CHARGE_GROUND_SPEED * BlessedGoatEntity.LIMB_SWING_PER_BLOCK

        // Each clip plays at its authored rate when the goat moves at that gait's design speed.
        private const val WALK_MILLIS_PER_SWING = MILLIS_PER_TICK / WALK_SWING_SPEED
        private const val CHARGE_MILLIS_PER_SWING = MILLIS_PER_TICK / CHARGE_SWING_SPEED

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id(BlessedGoatEntity.ID_PATH), "main")

        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val head = root.addOrReplaceChild("Head", CubeListBuilder.create(), PartPose.offset(0.0f, 9.0f, -8.0f))
            head.addOrReplaceChild("HornL", CubeListBuilder.create().texOffs(58, 0).addBox(-0.5f, -10.4f, -1.0f, 3.0f, 9.0f, 3.0f, CubeDeformation(0.0f)).texOffs(44, 34).addBox(-0.5f, -10.4f, 2.0f, 9.0f, 3.0f, 3.0f, CubeDeformation(0.0f)).texOffs(46, 4).addBox(6.5f, -4.4f, -3.0f, 2.0f, 2.0f, 8.0f, CubeDeformation(0.0f)).texOffs(36, 60).addBox(6.5f, -7.4f, 2.0f, 2.0f, 3.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(1.0f, -2.0f, -5.7f, -0.4363f, 0.0f, 0.0f))
            head.addOrReplaceChild("HornR", CubeListBuilder.create().texOffs(12, 58).addBox(-5.0f, -10.4f, -1.0f, 3.0f, 9.0f, 3.0f, CubeDeformation(0.0f)).texOffs(0, 48).addBox(-11.0f, -10.4f, 2.0f, 9.0f, 3.0f, 3.0f, CubeDeformation(0.0f)).texOffs(46, 60).addBox(-11.0f, -7.4f, 2.0f, 2.0f, 3.0f, 3.0f, CubeDeformation(0.0f)).texOffs(16, 48).addBox(-11.0f, -4.4f, -3.0f, 2.0f, 2.0f, 8.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(1.5f, -2.0f, -5.7f, -0.4363f, 0.0f, 0.0f))
            val head2 = head.addOrReplaceChild("Head2", CubeListBuilder.create().texOffs(43, 17).addBox(-2.5f, -1.7648f, -7.0611f, 5.0f, 4.0f, 7.0f, CubeDeformation(0.0f)).texOffs(70, 20).addBox(-2.5f, 2.2352f, -7.0611f, 5.0f, 6.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.4363f, 0.0f, 0.0f))
            head2.addOrReplaceChild("UpTeeth", CubeListBuilder.create().texOffs(88, 22).addBox(-2.5f, 0.0f, -7.0f, 5.0f, 6.0f, 2.0f, CubeDeformation(-0.05f)), PartPose.offset(0.0f, 2.2352f, 3.9389f))
            val jaw = head2.addOrReplaceChild("Jaw", CubeListBuilder.create().texOffs(71, 30).addBox(-2.5f, -0.0143f, 0.0007f, 5.0f, 6.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 2.2496f, -3.0618f))
            jaw.addOrReplaceChild("DoTeeth", CubeListBuilder.create().texOffs(88, 30).addBox(-2.5f, 0.0f, -9.0f, 5.0f, 6.0f, 2.0f, CubeDeformation(-0.05f)), PartPose.offset(0.0f, -0.0143f, 7.0007f))
            val beard = jaw.addOrReplaceChild("Beard", CubeListBuilder.create(), PartPose.offset(0.0f, 3.9923f, 1.7262f))
            beard.addOrReplaceChild("Beard_r1", CubeListBuilder.create().texOffs(70, 0).addBox(-2.5f, 0.0f, -1.0f, 5.0f, 7.0f, 3.0f, CubeDeformation(-0.05f)), PartPose.offsetAndRotation(0.0f, -0.2418f, -0.6645f, 0.4363f, 0.0f, 0.0f))
            val earr = head.addOrReplaceChild("EarR", CubeListBuilder.create(), PartPose.offset(-2.5f, -4.0f, -3.0f))
            earr.addOrReplaceChild("EarR_r1", CubeListBuilder.create().texOffs(33, 24).addBox(-1.0f, 0.0f, -2.0f, 1.0f, 8.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.2182f))
            val earl = head.addOrReplaceChild("EarL", CubeListBuilder.create(), PartPose.offset(2.5f, -4.0f, -3.0f))
            earl.addOrReplaceChild("EarL_r1", CubeListBuilder.create().texOffs(0, 24).addBox(0.0f, 0.0f, -2.0f, 1.0f, 8.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.2182f))
            root.addOrReplaceChild("Body", CubeListBuilder.create().texOffs(36, 40).addBox(-5.5f, -8.0f, 1.0f, 11.0f, 12.0f, 8.0f, CubeDeformation(-0.1f)).texOffs(0, 24).addBox(-5.5f, -9.0f, -9.0f, 11.0f, 13.0f, 11.0f, CubeDeformation(0.0f)).texOffs(0, 0).addBox(-4.5f, -7.0f, -8.0f, 9.0f, 8.0f, 16.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 17.0f, -1.0f))
            root.addOrReplaceChild("Leg1R", CubeListBuilder.create().texOffs(0, 54).addBox(-1.5f, -3.0f, -1.5f, 3.0f, 9.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(-2.0f, 18.0f, -6.5f))
            root.addOrReplaceChild("Leg1L", CubeListBuilder.create().texOffs(0, 0).addBox(-1.5f, -3.0f, -1.5f, 3.0f, 9.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(2.0f, 18.0f, -6.5f))
            root.addOrReplaceChild("Leg2R", CubeListBuilder.create().texOffs(60, 14).addBox(-1.5f, 0.0f, -1.5f, 3.0f, 6.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(2.0f, 18.0f, 4.5f))
            root.addOrReplaceChild("Leg2L", CubeListBuilder.create().texOffs(24, 58).addBox(-1.5f, 0.0f, -1.5f, 3.0f, 6.0f, 3.0f, CubeDeformation(0.0f)), PartPose.offset(-2.0f, 18.0f, 4.5f))
            return LayerDefinition.create(mesh, 128, 128)
        }
    }
}
