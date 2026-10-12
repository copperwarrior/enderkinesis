package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.KeyframeAnimations
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.RenderType
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.SselithMothEntity

/**
 * Blockbench-authored moth model — split upper/lower wings with outer tips,
 * body, head with antennae, tail, six legs. Translated verbatim from the
 * `sselith_moth.java` export (256×128 texture).
 *
 * Animation: cross-fades [SselithMothAnimations.FLY] and
 * [SselithMothAnimations.IDLE] by the entity's smoothed landed blend —
 * `KeyframeAnimations.animate` ADDS scaled offsets onto the reset pose, so
 * calling both with complementary scales is a proper linear pose morph.
 */
class SselithMothModel(private val bakedRoot: ModelPart) :
    // Translucent layer, not the default cutout-no-cull: the moth texture
    // has genuinely translucent wing membrane, and cutout alpha-TESTS
    // (keep or discard) instead of blending. Model.renderType is final in
    // 1.20.1, so the layer is supplied through the constructor.
    CachedHierarchicalModel<SselithMothEntity>({ RenderType.entityTranslucent(it) }) {

    override fun root(): ModelPart = bakedRoot

    override fun setupAnim(
        entity: SselithMothEntity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        resetAllParts()
        val millis = (ageInTicks.toDouble() * (1000.0 / 20.0)).toLong()
        // ageInTicks = tickCount + partialTick; the fraction recovers the
        // partial for a smooth blend.
        val blend = entity.landedBlend(ageInTicks - entity.tickCount)
        if (blend < 1f) {
            KeyframeAnimations.animate(
                this, SselithMothAnimations.FLY,
                millis, 1f - blend, ANIMATION_VECTOR_CACHE,
            )
        }
        if (blend > 0f) {
            KeyframeAnimations.animate(
                this, SselithMothAnimations.IDLE,
                millis, blend, ANIMATION_VECTOR_CACHE,
            )
        }
    }

    companion object {
        private val ANIMATION_VECTOR_CACHE = Vector3f()

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id("sselith_moth"), "main")

        @JvmStatic
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root

            val body = root.addOrReplaceChild(
                "body",
                CubeListBuilder.create()
                    .texOffs(96, 47).addBox(-5.0f, -2.0f, -9.0f, 10.0f, 2.0f, 12.0f, CubeDeformation(0.0f))
                    .texOffs(96, 61).addBox(-4.0f, -3.0f, -10.0f, 8.0f, 4.0f, 14.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, 23.0f, 4.0f),
            )

            val legBR = body.addOrReplaceChild("leg_b_r", CubeListBuilder.create(),
                PartPose.offset(-2.75f, 0.5847f, -5.5346f))
            legBR.addOrReplaceChild("leg2_b_r_r1",
                CubeListBuilder.create().texOffs(117, 79).addBox(-0.5f, -0.25f, -0.5f, 1.0f, 2.0f, 1.0f, CubeDeformation(-0.2f)),
                PartPose.offsetAndRotation(-0.8763f, 1.4731f, -0.4323f, 0.5325f, 0.2129f, 0.4821f))
            legBR.addOrReplaceChild("leg1_b_r_r1",
                CubeListBuilder.create().texOffs(101, 79).addBox(-0.5f, -1.0f, -0.5f, 1.0f, 3.0f, 1.0f, CubeDeformation(-0.1f)),
                PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.3838f, 0.2129f, 0.4821f))

            val legBL = body.addOrReplaceChild("leg_b_l", CubeListBuilder.create(),
                PartPose.offset(2.75f, 0.5847f, -5.5346f))
            legBL.addOrReplaceChild("leg1_b_l_r1",
                CubeListBuilder.create().texOffs(105, 79).addBox(-0.5f, -1.0f, -0.5f, 1.0f, 3.0f, 1.0f, CubeDeformation(-0.1f)),
                PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.3838f, -0.2129f, -0.4821f))
            legBL.addOrReplaceChild("leg2_b_l_r1",
                CubeListBuilder.create().texOffs(121, 79).addBox(-0.5f, -0.25f, -0.5f, 1.0f, 2.0f, 1.0f, CubeDeformation(-0.2f)),
                PartPose.offsetAndRotation(0.8763f, 1.4731f, -0.4323f, 0.5325f, -0.2129f, -0.4821f))

            val legFL = body.addOrReplaceChild("leg_f_l", CubeListBuilder.create(),
                PartPose.offset(3.5f, 0.5847f, -9.5346f))
            legFL.addOrReplaceChild("leg1_f_l_r1",
                CubeListBuilder.create().texOffs(96, 79).addBox(-0.5f, -1.0f, -0.5f, 1.0f, 3.0f, 1.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.3838f, -0.2129f, -0.4821f))
            legFL.addOrReplaceChild("leg2_f_l_r1",
                CubeListBuilder.create().texOffs(109, 79).addBox(-0.5f, 0.0f, -0.5f, 1.0f, 2.0f, 1.0f, CubeDeformation(-0.1f)),
                PartPose.offsetAndRotation(0.8002f, 1.2698f, -0.5564f, 0.5325f, -0.2129f, -0.4821f))

            val legFR = body.addOrReplaceChild("leg_f_r", CubeListBuilder.create(),
                PartPose.offset(-3.5f, 0.5847f, -9.5346f))
            legFR.addOrReplaceChild("leg1_f_r_r1",
                CubeListBuilder.create().texOffs(96, 83).addBox(-0.5f, -1.0f, -0.5f, 1.0f, 3.0f, 1.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.3838f, 0.2129f, 0.4821f))
            legFR.addOrReplaceChild("leg2_f_r_r1",
                CubeListBuilder.create().texOffs(113, 79).addBox(-0.5f, 0.0f, -0.5f, 1.0f, 2.0f, 1.0f, CubeDeformation(-0.1f)),
                PartPose.offsetAndRotation(-0.8002f, 1.2698f, -0.5564f, 0.5325f, 0.2129f, 0.4821f))

            body.addOrReplaceChild("right_wing_upper",
                CubeListBuilder.create().texOffs(-35, 93).addBox(-54.0f, -2.0f, -26.0f, 55.0f, 0.0f, 35.0f, CubeDeformation(0.0f)),
                PartPose.offset(-5.0f, -1.0f, -3.0f))
            val rightWingLower = body.addOrReplaceChild("right_wing_lower",
                CubeListBuilder.create().texOffs(-43, 50).addBox(-36.0f, -1.01f, -5.0f, 40.0f, 0.0f, 43.0f, CubeDeformation(0.0f)),
                PartPose.offset(-5.0f, -1.0f, -3.0f))
            rightWingLower.addOrReplaceChild("right_wing_5",
                CubeListBuilder.create().texOffs(-18, 10).addBox(-14.0f, 0.0f, 0.0f, 14.0f, 0.0f, 40.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -1.01f, 38.0f))

            body.addOrReplaceChild("left_wing_upper",
                CubeListBuilder.create().texOffs(110, 93).addBox(-1.0f, -2.0f, -26.0f, 55.0f, 0.0f, 35.0f, CubeDeformation(0.0f)),
                PartPose.offset(5.0f, -1.0f, -3.0f))
            val leftWingLower = body.addOrReplaceChild("left_wing_lower",
                CubeListBuilder.create().texOffs(132, 49).addBox(-4.0f, -1.01f, -5.0f, 40.0f, 0.0f, 43.0f, CubeDeformation(0.0f)),
                PartPose.offset(5.0f, -1.0f, -3.0f))
            leftWingLower.addOrReplaceChild("left_wing_3",
                CubeListBuilder.create().texOffs(139, 9).addBox(0.0f, 0.0f, 0.0f, 14.0f, 0.0f, 40.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -1.01f, 38.0f))

            val head = body.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(140, 55).addBox(-3.0f, -1.5f, -2.0f, 6.0f, 3.0f, 3.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -1.0f, -10.0f))
            val antennae = head.addOrReplaceChild("antennae", CubeListBuilder.create(),
                PartPose.offset(-2.0f, -2.0f, -3.5f))
            val antennaR = antennae.addOrReplaceChild("antenna_r", CubeListBuilder.create(),
                PartPose.offset(0.8164f, 0.5176f, 1.7508f))
            antennaR.addOrReplaceChild("antenna_r_r1",
                CubeListBuilder.create().texOffs(153, 48).addBox(-2.5f, 0.0f, -11.0f, 5.0f, 0.0f, 11.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.2618f, 0.4363f, 0.0f))
            val antennaL = antennae.addOrReplaceChild("antenna_l", CubeListBuilder.create(),
                PartPose.offset(3.1836f, 0.5176f, 1.7508f))
            antennaL.addOrReplaceChild("antenna_l_r1",
                CubeListBuilder.create().texOffs(153, 59).addBox(-2.5f, 0.0f, -11.0f, 5.0f, 0.0f, 11.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, -0.2618f, -0.4363f, 0.0f))

            body.addOrReplaceChild("tail",
                CubeListBuilder.create()
                    .texOffs(140, 47).addBox(-3.0f, -1.0f, -1.0f, 6.0f, 2.0f, 6.0f, CubeDeformation(0.0f))
                    .texOffs(55, 102).addBox(-2.0f, -1.0f, 5.0f, 4.0f, 2.0f, 2.0f, CubeDeformation(-0.1f)),
                PartPose.offset(0.0f, -1.0f, 4.0f))

            return LayerDefinition.create(mesh, 256, 128)
        }
    }
}
