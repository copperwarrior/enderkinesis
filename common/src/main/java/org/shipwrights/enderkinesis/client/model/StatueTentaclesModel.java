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
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import org.shipwrights.enderkinesis.EnderkinesisMod;

/** Statue: Tentacles. Blockbench export. */
public class StatueTentaclesModel extends EntityModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION =
        new ModelLayerLocation(new ResourceLocation(EnderkinesisMod.MOD_ID, "statue_tentacles"), "main");
    private final ModelPart bone;
    private final ModelPart bone2;
    private final ModelPart bb_main;

    public StatueTentaclesModel(ModelPart root) {
        this.bone = root.getChild("bone");
        this.bone2 = root.getChild("bone2");
        this.bb_main = root.getChild("bb_main");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition bone = partdefinition.addOrReplaceChild("bone", CubeListBuilder.create(), PartPose.offsetAndRotation(-2.0842F, 13.5517F, 2.914F, -3.1416F, -0.4363F, 3.1416F));

        bone.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(32, 43).addBox(-0.25F, -1.0F, -1.0F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.8666F, -13.0873F, 0.6849F, 0.0F, 0.3491F, -0.8247F));

        bone.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(28, 43).addBox(1.0F, -3.0F, 0.0F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.5F)), PartPose.offsetAndRotation(1.0635F, -8.5584F, 0.0F, 0.0F, 0.2182F, -0.1702F));

        bone.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(40, 32).addBox(-1.25F, -1.0F, -1.0F, 3.0F, 5.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0635F, -8.5584F, 0.0F, 0.0309F, 0.2153F, 0.0136F));

        bone.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(0, 42).addBox(-1.0F, -1.5F, -1.0F, 3.0F, 4.0F, 3.0F, new CubeDeformation(0.5F)), PartPose.offsetAndRotation(0.9815F, -3.547F, 0.0F, 0.0205F, 0.1208F, 0.3973F));

        bone.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(24, 22).addBox(-3.0F, -2.25F, -2.0F, 5.0F, 5.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.6545F));

        PartDefinition bone2 = partdefinition.addOrReplaceChild("bone2", CubeListBuilder.create(), PartPose.offsetAndRotation(-2.2761F, 13.5517F, -0.6458F, 0.0F, 1.5272F, 0.0F));

        bone2.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(12, 42).addBox(1.7583F, -4.1559F, -0.1832F, 1.0F, 5.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.73F, -5.3093F, -1.6262F, -0.5694F, 0.0393F, 0.489F));

        bone2.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(24, 43).addBox(1.25F, -1.5F, -2.5F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.5F)), PartPose.offsetAndRotation(0.9815F, -3.547F, 0.0F, 0.0442F, -0.016F, 0.7045F));

        bone2.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(40, 40).addBox(-1.0F, -2.25F, -2.75F, 3.0F, 5.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.3054F, 0.0F, 0.6545F));

        PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 0).addBox(-6.0F, -10.0F, -6.0F, 12.0F, 10.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        bb_main.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(44, 29).addBox(-0.1002F, -2.3286F, 0.1079F, 3.0F, 1.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.125F, -19.4336F, -0.6067F, -0.3106F, 0.7131F, -0.1681F));

        bb_main.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(44, 22).addBox(-1.5F, -0.5F, 0.0F, 3.0F, 1.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(3.57F, -19.5F, 3.5059F, -3.1416F, -0.0873F, 3.1416F));

        bb_main.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(44, 28).addBox(-3.125F, 0.4336F, 0.6067F, 3.0F, 1.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.125F, -19.4336F, -0.6067F, 0.0F, 1.0472F, 0.0F));

        bb_main.addOrReplaceChild("cube_r12", CubeListBuilder.create().texOffs(44, 27).addBox(1.0181F, 1.4336F, -1.7807F, 3.0F, 1.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.125F, -19.4336F, -1.6067F, -3.1416F, 1.309F, 3.1416F));

        bb_main.addOrReplaceChild("cube_r13", CubeListBuilder.create().texOffs(44, 26).addBox(-2.0F, -3.0F, 0.0F, 3.0F, 1.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, -17.0F, -4.0F, 0.0F, 0.7854F, 0.0F));

        bb_main.addOrReplaceChild("cube_r14", CubeListBuilder.create().texOffs(44, 25).addBox(-2.0F, -3.0F, 0.0F, 3.0F, 1.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, -21.0F, 2.0F, 0.0F, 1.0472F, 0.0F));

        bb_main.addOrReplaceChild("cube_r15", CubeListBuilder.create().texOffs(44, 24).addBox(-2.0F, -3.0F, 0.0F, 3.0F, 1.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, -23.0F, 0.0F, -0.1572F, -0.3243F, -0.1339F));

        bb_main.addOrReplaceChild("cube_r16", CubeListBuilder.create().texOffs(44, 23).addBox(-2.0F, -3.0F, 0.0F, 3.0F, 1.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.0F, -18.0F, 0.0F, 0.0F, 0.48F, 0.0F));

        bb_main.addOrReplaceChild("cube_r17", CubeListBuilder.create().texOffs(16, 40).addBox(-2.0F, -2.5F, -1.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.1552F, -28.5041F, -1.25F, 0.0F, 0.0F, -0.8247F));

        bb_main.addOrReplaceChild("cube_r18", CubeListBuilder.create().texOffs(16, 33).addBox(1.25F, -7.0F, -1.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(0.5F)), PartPose.offsetAndRotation(3.5042F, -19.0067F, -1.25F, 0.0F, 0.0F, -0.1702F));

        bb_main.addOrReplaceChild("cube_r19", CubeListBuilder.create().texOffs(24, 32).addBox(-0.5F, -3.0F, -2.0F, 4.0F, 7.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(3.5042F, -19.0067F, -1.25F, 0.0F, 0.0F, 0.1789F));

        bb_main.addOrReplaceChild("cube_r20", CubeListBuilder.create().texOffs(0, 33).addBox(-1.0F, -2.5F, -2.0F, 4.0F, 5.0F, 4.0F, new CubeDeformation(0.5F)), PartPose.offsetAndRotation(2.4222F, -13.9953F, -1.25F, 0.0F, 0.0F, 0.5236F));

        bb_main.addOrReplaceChild("cube_r21", CubeListBuilder.create().texOffs(0, 22).addBox(-3.0F, -2.25F, -3.0F, 6.0F, 5.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.4407F, -10.4483F, -1.25F, 0.0F, 0.0F, 0.6545F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        bone.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone2.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bb_main.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
