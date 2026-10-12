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
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.ShulkonautEntity

/** Blockbench export (shulkonaut.java) ported verbatim. The `sticky` group is the held
 *  book — visible only while a selector book is loaded. */
class ShulkonautModel(root: ModelPart) :
    EntityModel<ShulkonautEntity>(net.minecraft.client.renderer.RenderType::entityTranslucent) {

    private val base = root.getChild("base")
    private val head = base.getChild("head")
    private val sticky = root.getChild("sticky")
    private val bbMain = root.getChild("bb_main")

    override fun setupAnim(
        entity: ShulkonautEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        sticky.visible = entity.hasBook()
        head.yRot = netHeadYaw * Mth.DEG_TO_RAD
        head.xRot = headPitch * Mth.DEG_TO_RAD
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
        base.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha)
        sticky.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha)
        bbMain.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha)
    }

    companion object {
        val LAYER_LOCATION = ModelLayerLocation(
            ResourceLocation(EnderkinesisMod.MOD_ID, "shulkonaut"), "main",
        )

        @Suppress("LongMethod") // verbatim Blockbench geometry
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root

            val base = root.addOrReplaceChild(
                "base",
                CubeListBuilder.create().texOffs(0, 0)
                    .addBox(-8.0f, -8.0f, -8.0f, 16.0f, 8.0f, 16.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, 24.0f, 0.0f),
            )
            base.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                    .texOffs(0, 24).addBox(-3.0f, -3.0f, -3.0f, 6.0f, 6.0f, 6.0f, CubeDeformation(0.0f))
                    .texOffs(24, 24).addBox(-3.0f, -3.0f, -3.0f, 6.0f, 6.0f, 6.0f, CubeDeformation(0.2f)),
                PartPose.offset(0.0f, -9.0f, 0.0f),
            )

            val sticky = root.addOrReplaceChild(
                "sticky",
                CubeListBuilder.create().texOffs(0, 2)
                    .addBox(-2.5f, 0.1335f, -1.2114f, 3.0f, 0.0f, 1.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(-1.5f, 16.1241f, -5.7066f, -0.7931f, -0.1231f, 0.124f),
            )
            val sticky2 = sticky.addOrReplaceChild(
                "sticky2", CubeListBuilder.create(), PartPose.offset(0.0f, 0.0f, 0.0f),
            )
            sticky2.addOrReplaceChild(
                "sticky_r1",
                CubeListBuilder.create().texOffs(-1, 0)
                    .addBox(-1.5f, 0.0f, -2.0f, 3.0f, 0.0f, 2.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(-1.0f, 0.1335f, -1.2114f, 0.3927f, 0.0f, 0.0f),
            )

            val bbMain = root.addOrReplaceChild(
                "bb_main",
                CubeListBuilder.create()
                    .texOffs(40, 36).addBox(-4.0f, -8.0f, -8.0f, 8.0f, 1.0f, 1.0f, CubeDeformation(0.0f))
                    .texOffs(28, 36).addBox(-8.0f, -9.0f, -8.0f, 3.0f, 9.0f, 3.0f, CubeDeformation(-0.1f))
                    .texOffs(28, 36).mirror()
                    .addBox(5.0f, -9.0f, 5.0f, 3.0f, 9.0f, 3.0f, CubeDeformation(-0.1f)).mirror(false)
                    .texOffs(28, 36).addBox(-8.0f, -9.0f, 5.0f, 3.0f, 9.0f, 3.0f, CubeDeformation(-0.1f))
                    .texOffs(28, 36).mirror()
                    .addBox(5.0f, -9.0f, -8.0f, 3.0f, 9.0f, 3.0f, CubeDeformation(-0.1f)).mirror(false),
                PartPose.offset(0.0f, 24.0f, 0.0f),
            )
            bbMain.addOrReplaceChild(
                "cube_r1",
                CubeListBuilder.create().texOffs(0, 36).mirror()
                    .addBox(-6.0f, -1.0f, -1.0f, 12.0f, 4.0f, 2.0f, CubeDeformation(0.0f)).mirror(false),
                PartPose.offsetAndRotation(0.0f, -7.0f, 6.45f, -2.3562f, 0.0f, 3.1416f),
            )
            bbMain.addOrReplaceChild(
                "cube_r2",
                CubeListBuilder.create().texOffs(40, 36).mirror()
                    .addBox(-4.0f, -2.0f, -1.0f, 8.0f, 1.0f, 1.0f, CubeDeformation(0.0f)).mirror(false),
                PartPose.offsetAndRotation(0.0f, -6.0f, 7.0f, -3.1416f, 0.0f, -3.1416f),
            )
            bbMain.addOrReplaceChild(
                "cube_r3",
                CubeListBuilder.create().texOffs(0, 36)
                    .addBox(-6.0f, -1.0f, -1.0f, 12.0f, 4.0f, 2.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, -7.0f, 6.45f, -2.3562f, 0.0f, -3.1416f),
            )
            bbMain.addOrReplaceChild(
                "cube_r4",
                CubeListBuilder.create().texOffs(40, 36)
                    .addBox(-4.0f, -2.0f, -1.0f, 8.0f, 1.0f, 1.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, -6.0f, 7.0f, -3.1416f, 0.0f, 3.1416f),
            )
            bbMain.addOrReplaceChild(
                "cube_r5",
                CubeListBuilder.create().texOffs(0, 36).mirror()
                    .addBox(-6.0f, -1.0f, -1.0f, 12.0f, 4.0f, 2.0f, CubeDeformation(0.0f)).mirror(false),
                PartPose.offsetAndRotation(-6.475f, -7.0f, -0.05f, 0.0f, 1.5708f, -0.7854f),
            )
            bbMain.addOrReplaceChild(
                "cube_r6",
                CubeListBuilder.create().texOffs(40, 36).mirror()
                    .addBox(-4.0f, -2.0f, -1.0f, 8.0f, 1.0f, 1.0f, CubeDeformation(0.0f)).mirror(false),
                PartPose.offsetAndRotation(-7.0f, -6.0f, 0.0f, 0.0f, 1.5708f, 0.0f),
            )
            bbMain.addOrReplaceChild(
                "cube_r7",
                CubeListBuilder.create().texOffs(0, 36)
                    .addBox(-6.0f, -1.0f, -1.0f, 12.0f, 4.0f, 2.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(6.475f, -7.0f, -0.05f, 0.0f, -1.5708f, 0.7854f),
            )
            bbMain.addOrReplaceChild(
                "cube_r8",
                CubeListBuilder.create().texOffs(40, 36)
                    .addBox(-4.0f, -2.0f, -1.0f, 8.0f, 1.0f, 1.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(7.0f, -6.0f, 0.0f, 0.0f, -1.5708f, 0.0f),
            )
            bbMain.addOrReplaceChild(
                "cube_r9",
                CubeListBuilder.create().texOffs(0, 36)
                    .addBox(-6.0f, -1.0f, -1.0f, 12.0f, 4.0f, 2.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, -7.0f, -6.45f, 0.7854f, 0.0f, 0.0f),
            )

            return LayerDefinition.create(mesh, 64, 64)
        }
    }
}
