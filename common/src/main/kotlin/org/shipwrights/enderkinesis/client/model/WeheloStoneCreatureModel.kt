package org.shipwrights.enderkinesis.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.ArmedModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.WeheloStoneCreature

/** Generated from `stone_creature.bbmodel`. */
class WeheloStoneCreatureModel(private val bakedRoot: ModelPart) :
    CachedHierarchicalModel<WeheloStoneCreature>(), ArmedModel {

    private val body: ModelPart = bakedRoot.getChild("body")
    val head: ModelPart = body.getChild("head")
    private val rightArm: ModelPart = body.getChild("right_arm")

    /** Set each frame from [WeheloStoneCreature.fade]. */
    var alpha = 1f
        private set

    override fun root(): ModelPart = bakedRoot

    override fun prepareMobModel(entity: WeheloStoneCreature, limbSwing: Float, limbSwingAmount: Float, partialTick: Float) {
        alpha = entity.fade(partialTick)
    }

    override fun renderToBuffer(
        poseStack: PoseStack, buffer: VertexConsumer, packedLight: Int, packedOverlay: Int,
        red: Float, green: Float, blue: Float, alpha: Float,
    ) {
        super.renderToBuffer(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha * this.alpha)
    }

    override fun setupAnim(
        entity: WeheloStoneCreature, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        resetAllParts()
        head.yRot = netHeadYaw * Mth.DEG_TO_RAD
        head.xRot = headPitch * Mth.DEG_TO_RAD
        animateWalk(WeheloStoneCreatureAnimations.run, limbSwing, limbSwingAmount, RUN_SPEED, RUN_WEIGHT_SCALE)
    }

    /** The head is a child of the body, so a pose on the face must go through the body first. */
    fun translateToHead(poseStack: PoseStack) {
        body.translateAndRotate(poseStack)
        head.translateAndRotate(poseStack)
    }

    // Only a right arm: a left-hand item stays at the body.
    override fun translateToHand(arm: HumanoidArm, poseStack: PoseStack) {
        body.translateAndRotate(poseStack)
        if (arm == HumanoidArm.RIGHT) rightArm.translateAndRotate(poseStack)
    }

    companion object {
        private const val RUN_SPEED = 1f
        private const val RUN_WEIGHT_SCALE = 2f

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id(WeheloStoneCreature.ID_PATH), "main")

        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val body = mesh.root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(16, 16).addBox(-4.0f, 0.0f, -2.0f, 8.0f, 12.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 0.0f, 0.0f))
            body.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0f, -8.0f, -4.0f, 8.0f, 8.0f, 8.0f, CubeDeformation(0.0f)), PartPose.offset(0.0f, 0.0f, 0.0f))
            body.addOrReplaceChild("right_arm", CubeListBuilder.create().texOffs(40, 16).addBox(-3.0f, -2.0f, -2.0f, 4.0f, 12.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offset(-5.0f, 2.0f, 0.0f))
            body.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(16, 48).addBox(-2.0f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offset(2.0f, 12.0f, 0.0f))
            body.addOrReplaceChild("right_leg", CubeListBuilder.create().texOffs(0, 16).addBox(-2.0f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, CubeDeformation(0.0f)), PartPose.offset(-2.0f, 12.0f, 0.0f))
            // UVs past 16 wrap: the vanilla stone texture repeats over each face.
            return LayerDefinition.create(mesh, 16, 16)
        }
    }
}
