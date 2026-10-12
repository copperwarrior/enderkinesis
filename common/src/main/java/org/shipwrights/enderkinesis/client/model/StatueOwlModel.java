package org.shipwrights.enderkinesis.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.EnderkinesisMod;

/** Statue: Owl. Blockbench export. */
public class StatueOwlModel extends EntityModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION =
        new ModelLayerLocation(new ResourceLocation(EnderkinesisMod.MOD_ID, "statue_owl"), "main");
    private final ModelPart bone2;
    private final ModelPart bone;
    private final ModelPart bb_main;

    public StatueOwlModel(ModelPart root) {
        this.bone2 = root.getChild("bone2");
        this.bone = root.getChild("bone");
        this.bb_main = root.getChild("bb_main");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        partdefinition.addOrReplaceChild("bone2", CubeListBuilder.create().texOffs(25, 61).addBox(-3.0F, -9.0F, -3.0F, 6.0F, 1.0F, 7.0F, new CubeDeformation(0.0F))
            .texOffs(37, 26).addBox(-1.0F, -4.0F, -6.5F, 2.0F, 2.0F, 3.0F, new CubeDeformation(0.0F))
            .texOffs(30, 24).addBox(-2.0F, -2.0F, -6.0F, 4.0F, 4.0F, 1.0F, new CubeDeformation(0.0F))
            .texOffs(0, 45).addBox(-5.5F, -2.0F, -5.0F, 11.0F, 5.0F, 11.0F, new CubeDeformation(0.0F))
            .texOffs(43, 24).addBox(5.0F, -8.0F, -4.0F, 2.0F, 2.0F, 0.0F, new CubeDeformation(0.0F))
            .texOffs(43, 22).addBox(-7.0F, -8.0F, -4.0F, 2.0F, 2.0F, 0.0F, new CubeDeformation(0.0F))
            .texOffs(38, 22).addBox(-5.0F, -8.0F, -4.0F, 10.0F, 6.0F, 9.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, -3.0F, 0.0F));

        partdefinition.addOrReplaceChild("bone", CubeListBuilder.create().texOffs(44, 59).addBox(-5.0F, -15.122F, -5.8609F, 10.0F, 6.0F, 2.0F, new CubeDeformation(0.0F))
            .texOffs(44, 37).addBox(-6.0F, -14.1391F, -3.122F, 1.0F, 13.0F, 9.0F, new CubeDeformation(0.0F))
            .texOffs(64, 37).addBox(5.0F, -14.1391F, -3.122F, 1.0F, 13.0F, 9.0F, new CubeDeformation(0.0F))
            .texOffs(0, 22).addBox(-5.0F, -15.122F, -3.8609F, 10.0F, 14.0F, 9.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 14.0F, 1.0F, 0.1309F, 0.0F, 0.0F));

        PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 0).addBox(-6.0F, -10.0F, -6.0F, 12.0F, 10.0F, 12.0F, new CubeDeformation(0.0F))
            .texOffs(0, 61).addBox(-3.5F, -11.0F, -4.0F, 3.0F, 1.0F, 5.0F, new CubeDeformation(0.0F))
            .texOffs(16, 61).addBox(0.5F, -11.0F, -4.0F, 3.0F, 1.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        bb_main.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(33, 48).addBox(-2.0F, -7.0F, -1.0F, 4.0F, 7.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -12.0F, 5.0F, -2.0508F, 0.0F, 0.0F));

        return LayerDefinition.create(meshdefinition, 128, 128);
    }

    public void setBone2Rotation(float xRot, float yRot) {
        bone2.xRot = xRot;
        bone2.yRot = yRot;
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        bone2.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bb_main.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
