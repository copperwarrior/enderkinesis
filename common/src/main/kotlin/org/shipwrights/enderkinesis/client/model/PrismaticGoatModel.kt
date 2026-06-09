package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.model.HierarchicalModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.PrismaticGoat

/**
 * Restructured to mirror vanilla `GoatModel`'s bone hierarchy. Leg names match vanilla so
 * keyframes that target parts by name resolve correctly. `setupAnim` is vanilla `GoatModel`
 * verbatim — no `AnimationDefinition` (procedural in 1.20.1).
 */
class PrismaticGoatModel(private val root: ModelPart) : HierarchicalModel<PrismaticGoat>() {

    private val head: ModelPart = root.getChild("head")
    private val leftHorn: ModelPart = head.getChild("left_horn")
    private val rightHorn: ModelPart = head.getChild("right_horn")

    private val rightHindLeg: ModelPart = root.getChild("right_hind_leg")
    private val leftHindLeg: ModelPart = root.getChild("left_hind_leg")
    private val rightFrontLeg: ModelPart = root.getChild("right_front_leg")
    private val leftFrontLeg: ModelPart = root.getChild("left_front_leg")

    override fun root(): ModelPart = root

    override fun setupAnim(
        entity: PrismaticGoat,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        // Horns before super so a freshly-dropped horn isn't drawn for a single frame.
        leftHorn.visible = entity.hasLeftHorn()
        rightHorn.visible = entity.hasRightHorn()

        head.xRot = headPitch * Mth.DEG_TO_RAD
        head.yRot = netHeadYaw * Mth.DEG_TO_RAD
        rightHindLeg.xRot = Mth.cos(limbSwing * 0.6662f) * 1.4f * limbSwingAmount
        leftHindLeg.xRot = Mth.cos(limbSwing * 0.6662f + Mth.PI) * 1.4f * limbSwingAmount
        rightFrontLeg.xRot = Mth.cos(limbSwing * 0.6662f + Mth.PI) * 1.4f * limbSwingAmount
        leftFrontLeg.xRot = Mth.cos(limbSwing * 0.6662f) * 1.4f * limbSwingAmount

        // Vanilla overrides (not adds) head.xRot during ram — match that.
        val rammingHeadDip = entity.rammingXHeadRot
        if (rammingHeadDip != 0f) {
            head.xRot = rammingHeadDip
        }
    }

    companion object {
        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id("prismatic_goat"), "main")

        @JvmStatic
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val partdefinition = mesh.root

            // `head` carries its two boxes plus the four face/horn
            // children. Pivot `(0.5, 14, 0)` is the Blockbench-
            // authored head origin; child offsets are computed
            // relative to it so each child keeps its absolute pivot
            // from the raw export.
            val head = partdefinition.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                    .texOffs(23, 52).addBox(-0.5f, -3.0f, -14.0f, 0.0f, 7.0f, 5.0f, CubeDeformation(0.0f))
                    .texOffs(2, 61).addBox(-6.0f, -11.0f, -10.0f, 3.0f, 2.0f, 1.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.5f, 14.0f, 0.0f),
            )

            head.addOrReplaceChild(
                "mirror",
                CubeListBuilder.create()
                    .texOffs(2, 61).mirror()
                    .addBox(2.5f, -21.0f, -10.0f, 3.0f, 2.0f, 1.0f, CubeDeformation(0.0f))
                    .mirror(false),
                PartPose.offset(-0.5f, 10.0f, 0.0f),
            )

            // Nose reparented under head — absolute pivot
            // `(0.5, 6, -8)` becomes local `(0, -8, -8)` after
            // subtracting the head pivot `(0.5, 14, 0)`. Authored
            // X-rotation (0.9599 rad ≈ 55°) stays on the nose itself
            // and now composes additively with any head pitch.
            head.addOrReplaceChild(
                "nose",
                CubeListBuilder.create()
                    .texOffs(34, 46)
                    .addBox(-3.0f, -4.0f, -8.0f, 5.0f, 7.0f, 10.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, -8.0f, -8.0f, 0.9599f, 0.0f, 0.0f),
            )

            // Horn part names match vanilla so [GoatAnimation]
            // keyframes that move them resolve.
            head.addOrReplaceChild(
                "left_horn",
                CubeListBuilder.create()
                    .texOffs(12, 55)
                    .addBox(-0.01f, -16.0f, -10.0f, 2.0f, 7.0f, 2.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, 0.0f, 0.0f),
            )

            head.addOrReplaceChild(
                "right_horn",
                CubeListBuilder.create()
                    .texOffs(12, 55)
                    .addBox(-2.99f, -16.0f, -10.0f, 2.0f, 7.0f, 2.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, 0.0f, 0.0f),
            )

            partdefinition.addOrReplaceChild(
                "body",
                CubeListBuilder.create()
                    .texOffs(1, 1).addBox(-4.0f, -17.0f, -7.0f, 9.0f, 11.0f, 16.0f, CubeDeformation(0.0f))
                    .texOffs(0, 28).addBox(-5.0f, -18.0f, -8.0f, 11.0f, 14.0f, 11.0f, CubeDeformation(0.0f)),
                PartPose.offset(-0.5f, 24.0f, 0.0f),
            )

            // Leg part names match vanilla goat. texOffs / addBox /
            // pivot are the prismatic-goat artist's authoring;
            // [GoatAnimation] targets these names to set xRot only,
            // so the different rest positions don't break the
            // animation, they just give the prismatic legs a
            // slightly different silhouette than vanilla.
            partdefinition.addOrReplaceChild(
                "right_hind_leg",
                CubeListBuilder.create()
                    .texOffs(49, 29).addBox(0.0f, 4.0f, 0.0f, 3.0f, 6.0f, 3.0f, CubeDeformation(0.0f)),
                PartPose.offset(-3.5f, 14.0f, 4.0f),
            )

            partdefinition.addOrReplaceChild(
                "left_hind_leg",
                CubeListBuilder.create()
                    .texOffs(36, 29).addBox(0.0f, 4.0f, 0.0f, 3.0f, 6.0f, 3.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.5f, 14.0f, 4.0f),
            )

            partdefinition.addOrReplaceChild(
                "right_front_leg",
                CubeListBuilder.create()
                    .texOffs(49, 2).addBox(0.0f, 0.0f, 0.0f, 3.0f, 10.0f, 3.0f, CubeDeformation(0.0f)),
                PartPose.offset(-3.5f, 14.0f, -6.0f),
            )

            partdefinition.addOrReplaceChild(
                "left_front_leg",
                CubeListBuilder.create()
                    .texOffs(35, 2).addBox(0.0f, 0.0f, 0.0f, 3.0f, 10.0f, 3.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.5f, 14.0f, -6.0f),
            )

            return LayerDefinition.create(mesh, 64, 64)
        }
    }
}
