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
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.EauhorranaEntity

/** Generated from `SwampPiranha.bbmodel`. */
class EauhorranaModel(private val bakedRoot: ModelPart) : CachedHierarchicalModel<EauhorranaEntity>() {

    override fun root(): ModelPart = bakedRoot

    override fun setupAnim(
        entity: EauhorranaEntity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        resetAllParts()
        val bite = entity.biteAnimationState
        if (bite.isStarted) {
            bite.updateTime(ageInTicks, 1f)
            if (bite.accumulatedTime < BITE_MILLIS) {
                KeyframeAnimations.animate(this, EauhorranaAnimations.bite, bite.accumulatedTime, 1f, ANIMATION_VECTOR_CACHE)
                return
            }
            bite.stop()
        }
        val clip: AnimationDefinition = when {
            entity.isLatched -> EauhorranaAnimations.clinged
            entity.isInWater -> EauhorranaAnimations.swim
            else -> EauhorranaAnimations.flop
        }
        KeyframeAnimations.animate(this, clip, (ageInTicks * MILLIS_PER_TICK).toLong(), 1f, ANIMATION_VECTOR_CACHE)
    }

    companion object {
        private val ANIMATION_VECTOR_CACHE = Vector3f()
        private const val MILLIS_PER_TICK = 50f
        private const val MILLIS_PER_SECOND = 1000f
        private val BITE_MILLIS = (EauhorranaAnimations.bite.lengthInSeconds() * MILLIS_PER_SECOND).toLong()

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id(EauhorranaEntity.ID_PATH), "main")

        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val rootBone = root.addOrReplaceChild("Root", CubeListBuilder.create(), PartPose.offset(0.0f, 20.0f, -3.0f))
            val head = rootBone.addOrReplaceChild("Head", CubeListBuilder.create(), PartPose.offset(0.0f, 2.0f, -2.0f))
            head.addOrReplaceChild("UpJaw", CubeListBuilder.create().texOffs(28, 28).addBox(-2.0f, -3.0f, -4.0f, 4.0f, 3.0f, 4.0f, CubeDeformation(0.0f)).texOffs(12, 26).addBox(0.0f, -6.0f, -7.0f, 0.0f, 3.0f, 6.0f, CubeDeformation(0.0f)).texOffs(28, 21).addBox(-2.0f, 0.0f, -4.0f, 4.0f, 1.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, -3.0f, 0.0f))
            head.addOrReplaceChild("LoJaw", CubeListBuilder.create().texOffs(26, 14).addBox(-2.0f, -1.0f, -5.0f, 4.0f, 2.0f, 5.0f, CubeDeformation(0.05f)).texOffs(24, 7).addBox(-2.0f, -3.1f, -5.0f, 4.0f, 2.0f, 5.0f, CubeDeformation(0.05f)), PartPose.offset(0.0f, 1.0f, 0.0f))
            val body1 = rootBone.addOrReplaceChild("Body1", CubeListBuilder.create().texOffs(0, 0).addBox(-3.0f, -6.0f, -2.0f, 6.0f, 9.0f, 6.0f, CubeDeformation(0.0f)).texOffs(0, 15).addBox(0.0f, -12.0f, -2.0f, 0.0f, 6.0f, 8.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 1.0f, 0.0f))
            body1.addOrReplaceChild("MouthHole", CubeListBuilder.create().texOffs(40, 35).addBox(-2.0f, -4.0f, 0.05f, 4.0f, 7.0f, 0.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 0.0f, -2.1f))
            body1.addOrReplaceChild("FinL", CubeListBuilder.create().texOffs(17, 0).addBox(0.0f, 0.0f, -1.0f, 4.0f, 0.0f, 7.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(3.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.6109f))
            val finr = body1.addOrReplaceChild("FinR", CubeListBuilder.create(), PartPose.offset(-3.0f, -1.0f, 0.0f))
            finr.addOrReplaceChild("FinR_r1", CubeListBuilder.create().texOffs(11, 15).addBox(-4.0f, 0.0f, -1.0f, 4.0f, 0.0f, 7.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.6109f))
            val body2 = body1.addOrReplaceChild("Body2", CubeListBuilder.create().texOffs(0, 23).addBox(0.0f, -8.0f, 0.0f, 0.0f, 4.0f, 6.0f, CubeDeformation(0.0f)).texOffs(16, 22).addBox(-2.0f, -4.0f, 0.0f, 4.0f, 6.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 0.0f, 4.0f))
            val body3 = body2.addOrReplaceChild("Body3", CubeListBuilder.create().texOffs(32, 0).addBox(-1.0f, -2.0f, 0.0f, 2.0f, 3.0f, 2.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 0.0f, 4.0f))
            body3.addOrReplaceChild("Tail", CubeListBuilder.create().texOffs(0, 6).addBox(0.0f, -5.0f, 0.0f, 0.0f, 8.0f, 9.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 0.0f, 2.0f))
            return LayerDefinition.create(mesh, 64, 64)
        }
    }
}
