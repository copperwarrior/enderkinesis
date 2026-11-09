package org.shipwrights.enderkinesis.client.model

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
import org.shipwrights.enderkinesis.entity.KekhekhEntity
import org.shipwrights.enderkinesis.util.MILLIS_PER_TICK

/** Generated from `SwampPreyFish.bbmodel`. */
class KekhekhModel(private val bakedRoot: ModelPart) : CachedHierarchicalModel<KekhekhEntity>() {

    override fun root(): ModelPart = bakedRoot

    override fun setupAnim(
        entity: KekhekhEntity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        resetAllParts()
        val clip = if (entity.isInWater) KekhekhAnimations.swim else KekhekhAnimations.flop
        KeyframeAnimations.animate(this, clip, (ageInTicks * MILLIS_PER_TICK).toLong(), 1f, ANIMATION_VECTOR_CACHE)
    }

    companion object {
        private val ANIMATION_VECTOR_CACHE = Vector3f()

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id(KekhekhEntity.ID_PATH), "main")

        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            val bone = root.addOrReplaceChild("bone", CubeListBuilder.create(), PartPose.offset(0.0f, 23.0f, 0.0f))
            val upper = bone.addOrReplaceChild("Upper", CubeListBuilder.create().texOffs(12, 6).addBox(-1.0f, -1.0f, -3.0f, 2.0f, 2.0f, 2.0f, CubeDeformation(0.0f)).texOffs(0, 5).addBox(-1.0f, -1.0f, -1.0f, 2.0f, 3.0f, 4.0f, CubeDeformation(0.0f)).texOffs(8, 1).addBox(0.0f, -2.0f, 0.0f, 0.0f, 1.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, -1.0f, -2.0f))
            upper.addOrReplaceChild("Upper_r1", CubeListBuilder.create().texOffs(4, 0).addBox(0.0f, 0.0f, -1.0f, 2.0f, 0.0f, 5.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5236f))
            upper.addOrReplaceChild("Upper_r2", CubeListBuilder.create().texOffs(0, 0).addBox(-2.0f, 0.0f, -1.0f, 2.0f, 0.0f, 5.0f, CubeDeformation(0.0f)), PartPose.offsetAndRotation(-1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -0.5236f))
            val tail = upper.addOrReplaceChild("Tail", CubeListBuilder.create().texOffs(10, 10).addBox(-1.0f, -1.0f, 0.0f, 2.0f, 2.0f, 2.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 1.0f, 3.0f))
            tail.addOrReplaceChild("bone2", CubeListBuilder.create().texOffs(0, 7).addBox(0.0f, -2.0f, 0.0f, 0.0f, 3.0f, 5.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 0.0f, 2.0f))
            return LayerDefinition.create(mesh, 32, 32)
        }
    }
}
