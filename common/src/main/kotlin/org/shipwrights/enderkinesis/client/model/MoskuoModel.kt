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
import net.minecraft.world.entity.AnimationState
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.MoskuoEntity

/** Generated from `SwampMosquito.bbmodel`. The clips scale BODYSkinny or BODYFat to zero to show one body. */
class MoskuoModel(private val bakedRoot: ModelPart) : CachedHierarchicalModel<MoskuoEntity>() {

    override fun root(): ModelPart = bakedRoot

    override fun setupAnim(
        entity: MoskuoEntity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        resetAllParts()
        if (play(entity.stingAnimationState, MoskuoAnimations.sting, ageInTicks) ||
            play(entity.digestAnimationState, MoskuoAnimations.digest, ageInTicks)
        ) {
            return
        }
        val dx = entity.x - entity.xo
        val dy = entity.y - entity.yo
        val dz = entity.z - entity.zo
        val moving = dx * dx + dy * dy + dz * dz > MOVING_SPEED_SQR
        val clip: AnimationDefinition = when {
            entity.isFed && entity.isPerched -> MoskuoAnimations.fatPerch
            entity.isFed && moving -> MoskuoAnimations.fatMove
            entity.isFed -> MoskuoAnimations.fatIdle
            entity.isPerched -> MoskuoAnimations.skinnyPerch
            moving -> MoskuoAnimations.skinnyMove
            else -> MoskuoAnimations.skinnyIdle
        }
        KeyframeAnimations.animate(this, clip, (ageInTicks * MILLIS_PER_TICK).toLong(), 1f, ANIMATION_VECTOR_CACHE)
    }

    private fun play(state: AnimationState, clip: AnimationDefinition, ageInTicks: Float): Boolean {
        if (!state.isStarted) return false
        state.updateTime(ageInTicks, 1f)
        KeyframeAnimations.animate(this, clip, state.accumulatedTime, 1f, ANIMATION_VECTOR_CACHE)
        return true
    }

    companion object {
        private val ANIMATION_VECTOR_CACHE = Vector3f()
        private const val MILLIS_PER_TICK = 50f
        private const val MOVING_SPEED_SQR = 1.0e-4

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id(MoskuoEntity.ID_PATH), "main")

        @JvmStatic
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val bone3 = root.addOrReplaceChild("bone3", CubeListBuilder.create(), PartPose.offset(-0.5f, 22.0f, -1.0f))
            bone3.addOrReplaceChild("HEAD", CubeListBuilder.create().texOffs(11, 0).addBox(0.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f, CubeDeformation(0.0f)).texOffs(9, 0).addBox(0.0f, 0.0f, -7.0f, 1.0f, 0.0f, 7.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.4363f, 0.0f, 0.0f))
            val bodyskinny = bone3.addOrReplaceChild("BODYSkinny", CubeListBuilder.create().texOffs(11, 8).addBox(0.0f, -1.0f, 0.0f, 1.0f, 1.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.5236f, 0.0f, 0.0f))
            bodyskinny.addOrReplaceChild("LEG1L", CubeListBuilder.create().texOffs(16, 9).addBox(0.0f, 0.0f, -1.0f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(1.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.6981f))
            bodyskinny.addOrReplaceChild("LEG1R", CubeListBuilder.create().texOffs(16, 8).addBox(-3.0f, 0.0f, 0.0f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.6981f))
            bodyskinny.addOrReplaceChild("LEG2L", CubeListBuilder.create().texOffs(6, 16).addBox(0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(1.0f, 0.0f, 2.0f, 0.0f, 0.0f, 0.6981f))
            bodyskinny.addOrReplaceChild("LEG2R", CubeListBuilder.create().texOffs(0, 16).addBox(-3.0f, 0.0f, 0.0f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 2.0f, 0.0f, 0.0f, -0.6981f))
            bodyskinny.addOrReplaceChild("WINGL", CubeListBuilder.create().texOffs(0, 13).addBox(1.0f, 0.0f, 0.0f, 2.0f, -5.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, -1.0f, 0.0f))
            bodyskinny.addOrReplaceChild("WINGR", CubeListBuilder.create().texOffs(0, 0).addBox(-1.0f, -5.0f, 0.0f, 2.0f, 5.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(-1.0f, -1.0f, 0.0f))
            val bodyfat = bone3.addOrReplaceChild("BODYFat", CubeListBuilder.create().texOffs(0, 8).addBox(-1.5f, -3.0f, 0.0f, 3.0f, 3.0f, 5.0f, CubeDeformation(0.0f)).texOffs(0, 0).addBox(-1.5f, -3.0f, 0.0f, 3.0f, 3.0f, 5.0f, CubeDeformation(-0.1f)), PartPose.offsetAndRotation(0.5f, 0.0f, 0.0f, -0.7854f, 0.0f, 0.0f))
            bodyfat.addOrReplaceChild("LEG1L2", CubeListBuilder.create().texOffs(15, 15).addBox(0.0f, 0.0f, -1.0f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(1.5f, 0.0f, 1.0f, 0.0f, 0.0f, 0.6981f))
            bodyfat.addOrReplaceChild("LEG1R2", CubeListBuilder.create().texOffs(15, 14).addBox(-3.0f, 0.0f, -1.0f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(-1.5f, 0.0f, 1.0f, 0.0f, 0.0f, -0.6981f))
            bodyfat.addOrReplaceChild("LEG2L2", CubeListBuilder.create().texOffs(15, 13).addBox(0.0f, 0.0f, 0.0f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(1.5f, 0.0f, 2.0f, 0.0f, 0.0f, 0.6981f))
            bodyfat.addOrReplaceChild("LEG2R2", CubeListBuilder.create().texOffs(15, 7).addBox(-3.0f, 0.0f, 0.0f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(-1.5f, 0.0f, 2.0f, 0.0f, 0.0f, -0.6981f))
            bodyfat.addOrReplaceChild("WINGL2", CubeListBuilder.create().texOffs(0, 13).addBox(0.0f, 0.0f, 0.0f, 2.0f, -5.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(1.5f, -3.0f, 0.0f))
            bodyfat.addOrReplaceChild("WINGR2", CubeListBuilder.create().texOffs(0, 0).addBox(-2.0f, -5.0f, 0.0f, 2.0f, 5.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(-1.5f, -3.0f, 0.0f))
            return LayerDefinition.create(mesh, 32, 32)
        }
    }
}
