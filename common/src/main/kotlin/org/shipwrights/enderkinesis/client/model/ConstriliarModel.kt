package org.shipwrights.enderkinesis.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.ConstriliarEntity

/**
 * Blockbench export re-rooted into a hip→thigh→calf chain per leg (the export had thigh
 * and calf as flat siblings, which can't articulate). Knee pivot/rotation are the export's
 * calf pose expressed in the thigh's frame, so the bind pose renders identically. Leg
 * rotations come from [ConstriliarEntity.gait]'s IK solve, not limb-swing math.
 */
// Translucent render type: the skin has transparent regions that reveal the block wrap
// the platform BER draws beneath (and stay see-through in the shipless fallback).
class ConstriliarModel(root: ModelPart) :
    EntityModel<ConstriliarEntity>(net.minecraft.client.renderer.RenderType::entityTranslucent) {

    private val legs = Array(4) { root.getChild(LEG_NAMES[it]) }
    private val thighs = Array(4) { legs[it].getChild("thigh") }
    private val calves = Array(4) { thighs[it].getChild("calf") }
    private val body: ModelPart = root.getChild("bb_main")

    /** Spawn-intro leg pop: visibility only — each leg appears at its own offset; the
     *  pick-up-and-set-down motion itself is the gait's intro foot path. */
    fun applySpawnPop(t: Float) {
        for (i in 0..3) {
            legs[i].visible = t >= ConstriliarEntity.ANIM_LEG_POP[i]
        }
    }

    fun resetSpawnPop() {
        for (leg in legs) leg.visible = true
    }

    override fun setupAnim(
        entity: ConstriliarEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        val angles = entity.gait.renderAngles
        for (i in 0..3) {
            legs[i].yRot = angles[i * 3]
            thighs[i].xRot = angles[i * 3 + 1]
            calves[i].xRot = angles[i * 3 + 2]
        }
    }

    override fun renderToBuffer(
        poseStack: PoseStack,
        vertexConsumer: VertexConsumer,
        packedLight: Int,
        packedOverlay: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        for (leg in legs) leg.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha)
        body.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha)
    }

    companion object {
        val LAYER_LOCATION = ModelLayerLocation(
            ResourceLocation(EnderkinesisMod.MOD_ID, "constriliar"),
            "main",
        )

        private val LEG_NAMES = arrayOf("leg_front_right", "leg_front_left", "leg_back_left", "leg_back_right")
        private val LEG_X = floatArrayOf(-7f, 7f, 7f, -7f)
        private val LEG_Z = floatArrayOf(-7f, -7f, 7f, 7f)
        private val LEG_YAW = floatArrayOf(0.7854f, -0.7854f, -2.3562f, 2.3562f)

        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root
            for (i in 0..3) {
                val leg = root.addOrReplaceChild(
                    LEG_NAMES[i],
                    CubeListBuilder.create(),
                    PartPose.offsetAndRotation(LEG_X[i], 18f, LEG_Z[i], 0f, LEG_YAW[i], 0f),
                )
                val thigh = leg.addOrReplaceChild(
                    "thigh",
                    CubeListBuilder.create().texOffs(0, 20)
                        .addBox(-1.4226f, -2.0358f, -15.6665f, 3f, 4f, 17f, CubeDeformation.NONE),
                    PartPose.offsetAndRotation(-0.0472f, 0.25f, -1.2517f, -0.6545f, 0f, 0f),
                )
                thigh.addOrReplaceChild(
                    "calf",
                    CubeListBuilder.create().texOffs(40, 20)
                        .addBox(-0.9226f, -0.9673f, -14.3053f, 2f, 2f, 15f, CubeDeformation.NONE),
                    PartPose.offsetAndRotation(0f, 0.0589f, -14.4502f, 2.0944f, 0f, 0f),
                )
            }
            root.addOrReplaceChild(
                "bb_main",
                CubeListBuilder.create().texOffs(0, 0)
                    .addBox(-8f, -8f, -8f, 16f, 4f, 16f, CubeDeformation.NONE),
                PartPose.offset(0f, 24f, 0f),
            )
            return LayerDefinition.create(mesh, 128, 128)
        }
    }
}
