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

/** Statue: Yellow Tower. Blockbench export. 128x128 atlas. */
public class StatueYellowTowerModel extends EntityModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION =
        new ModelLayerLocation(new ResourceLocation(EnderkinesisMod.MOD_ID, "statue_yellow_tower"), "main");
    private final ModelPart bb_main;

    public StatueYellowTowerModel(ModelPart root) {
        this.bb_main = root.getChild("bb_main");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 0).addBox(-6.0F, -10.0F, -6.0F, 12.0F, 10.0F, 12.0F, new CubeDeformation(0.0F))
            .texOffs(0, 22).addBox(-5.0F, -12.0F, -5.0F, 10.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
            .texOffs(0, 34).addBox(-4.0F, -23.0F, -4.0F, 8.0F, 12.0F, 8.0F, new CubeDeformation(0.0F))
            .texOffs(32, 34).addBox(-5.0F, -24.0F, -5.0F, 10.0F, 2.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        bb_main.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(32, 46).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 3.0F, 3.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(4.1418F, -17.3683F, -2.8213F, 0.329F, 0.39F, -0.1329F));
        bb_main.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(56, 8).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.7335F, -14.1227F, -4.3583F, -1.5868F, -0.1904F, -2.5279F));
        bb_main.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(48, 54).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.7009F, -19.7825F, -2.0718F, 0.4224F, -0.2524F, 0.1743F));
        bb_main.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(24, 58).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0953F, -19.3727F, 4.2736F, 3.055F, -0.5051F, 2.8953F));
        bb_main.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(58, 22).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-0.9047F, -12.8727F, 4.2736F, -0.4013F, -0.6642F, 0.3784F));
        bb_main.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(58, 20).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.8453F, -18.3727F, 4.2736F, 2.9274F, 0.5881F, -3.063F));
        bb_main.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(56, 6).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.7335F, -21.3727F, -4.3583F, -0.8924F, 1.1458F, 2.0503F));
        bb_main.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(40, 54).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, -19.5F, 4.0F, -0.143F, 0.3456F, 0.5202F));
        bb_main.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(24, 54).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.0F, -20.25F, 4.0F, 0.2251F, -0.6623F, -0.4999F));
        bb_main.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(16, 54).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(4.3582F, -14.1183F, -3.3213F, 0.3319F, -0.7781F, -0.1799F));
        bb_main.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(20, 58).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.5953F, -21.1227F, 4.2736F, 3.055F, -0.5051F, -2.6461F));
        bb_main.addOrReplaceChild("cube_r12", CubeListBuilder.create().texOffs(16, 58).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.5953F, -15.3727F, 4.2736F, 3.055F, -0.5051F, -2.6461F));
        bb_main.addOrReplaceChild("cube_r13", CubeListBuilder.create().texOffs(12, 58).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.5953F, -21.1227F, 4.2736F, 3.055F, 0.5051F, 2.6461F));
        bb_main.addOrReplaceChild("cube_r14", CubeListBuilder.create().texOffs(8, 58).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5953F, -15.3727F, 5.2736F, 0.2026F, -0.704F, -0.25F));
        bb_main.addOrReplaceChild("cube_r15", CubeListBuilder.create().texOffs(4, 58).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.5953F, -15.3727F, 4.2736F, 3.055F, 0.5051F, 2.6461F));
        bb_main.addOrReplaceChild("cube_r16", CubeListBuilder.create().texOffs(0, 58).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.4047F, -15.3727F, 1.7736F, 0.2026F, -0.704F, -0.25F));
        bb_main.addOrReplaceChild("cube_r17", CubeListBuilder.create().texOffs(56, 56).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.9047F, -12.8727F, 4.2736F, -0.4013F, 0.6642F, -0.3784F));
        bb_main.addOrReplaceChild("cube_r18", CubeListBuilder.create().texOffs(56, 54).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.4047F, -19.3727F, -0.2264F, 0.2026F, -0.704F, -0.25F));
        bb_main.addOrReplaceChild("cube_r19", CubeListBuilder.create().texOffs(36, 56).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.4047F, -15.3727F, 1.7736F, 0.2026F, 0.704F, 0.25F));
        bb_main.addOrReplaceChild("cube_r20", CubeListBuilder.create().texOffs(32, 56).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.8453F, -18.3727F, 4.2736F, 2.9274F, -0.5881F, 3.063F));
        bb_main.addOrReplaceChild("cube_r21", CubeListBuilder.create().texOffs(56, 18).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.4047F, -19.3727F, -0.2264F, 0.2026F, 0.704F, 0.25F));
        bb_main.addOrReplaceChild("cube_r22", CubeListBuilder.create().texOffs(56, 4).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.7335F, -13.3727F, -4.3583F, -1.4865F, 0.047F, 2.5398F));
        bb_main.addOrReplaceChild("cube_r23", CubeListBuilder.create().texOffs(56, 2).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.7335F, -19.3727F, -4.3583F, -1.6388F, -0.2614F, -2.5162F));
        bb_main.addOrReplaceChild("cube_r24", CubeListBuilder.create().texOffs(56, 0).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.7335F, -21.3727F, -4.3583F, -0.8924F, -1.1458F, -2.0503F));
        bb_main.addOrReplaceChild("cube_r25", CubeListBuilder.create().texOffs(54, 32).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.7335F, -18.3727F, -4.3583F, -1.4865F, 0.047F, 2.5398F));
        bb_main.addOrReplaceChild("cube_r26", CubeListBuilder.create().texOffs(56, 16).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.7335F, -14.3727F, -2.3583F, -0.0752F, -0.5349F, -2.7024F));
        bb_main.addOrReplaceChild("cube_r27", CubeListBuilder.create().texOffs(50, 32).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-0.7335F, -13.8727F, -4.3583F, -0.0752F, 0.5349F, 2.7024F));
        bb_main.addOrReplaceChild("cube_r28", CubeListBuilder.create().texOffs(56, 14).addBox(-1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.5F, -13.75F, -1.0F, 0.0181F, -0.1074F, 0.1908F));
        bb_main.addOrReplaceChild("cube_r29", CubeListBuilder.create().texOffs(56, 12).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.7335F, -14.3727F, -2.3583F, -0.0752F, 0.5349F, 2.7024F));
        bb_main.addOrReplaceChild("cube_r30", CubeListBuilder.create().texOffs(56, 10).addBox(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.5F, -13.75F, -1.0F, 0.0181F, 0.1074F, -0.1908F));
        bb_main.addOrReplaceChild("cube_r31", CubeListBuilder.create().texOffs(8, 54).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.0F, -13.25F, 4.0F, -2.3149F, -1.0984F, 2.6849F));
        bb_main.addOrReplaceChild("cube_r32", CubeListBuilder.create().texOffs(0, 54).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.0F, -15.0F, 1.0F, 0.0181F, -0.1074F, -0.3328F));
        bb_main.addOrReplaceChild("cube_r33", CubeListBuilder.create().texOffs(32, 52).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.0F, -13.5F, 3.0F, -0.143F, -0.3456F, -0.5202F));
        bb_main.addOrReplaceChild("cube_r34", CubeListBuilder.create().texOffs(50, 50).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.7009F, -19.7825F, -2.0718F, 0.4224F, 0.2524F, -0.1743F));
        bb_main.addOrReplaceChild("cube_r35", CubeListBuilder.create().texOffs(48, 0).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(3.6082F, -20.1183F, 1.4287F, -0.2155F, -0.2123F, -0.0434F));
        bb_main.addOrReplaceChild("cube_r36", CubeListBuilder.create().texOffs(50, 46).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(3.6082F, -17.1183F, 3.4287F, 0.2785F, -0.5198F, -0.1474F));
        bb_main.addOrReplaceChild("cube_r37", CubeListBuilder.create().texOffs(42, 50).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.0F, -13.5F, 3.0F, -0.143F, 0.3456F, 0.5202F));
        bb_main.addOrReplaceChild("cube_r38", CubeListBuilder.create().texOffs(50, 28).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(-4.3582F, -14.1183F, -3.3213F, 0.3319F, 0.7781F, 0.1799F));
        bb_main.addOrReplaceChild("cube_r39", CubeListBuilder.create().texOffs(42, 46).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.6082F, -20.1183F, 1.4287F, -0.2155F, 0.2123F, 0.0434F));
        bb_main.addOrReplaceChild("cube_r40", CubeListBuilder.create().texOffs(50, 24).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.6082F, -17.1183F, 3.4287F, 0.2785F, 0.5198F, 0.1474F));
        bb_main.addOrReplaceChild("cube_r41", CubeListBuilder.create().texOffs(50, 20).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.0F, -15.0F, 1.0F, 0.0181F, 0.1074F, 0.3328F));
        bb_main.addOrReplaceChild("cube_r42", CubeListBuilder.create().texOffs(40, 28).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 3.0F, 3.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(0.1418F, -15.3683F, 4.1787F, 2.6805F, 1.4422F, -3.1386F));
        bb_main.addOrReplaceChild("cube_r43", CubeListBuilder.create().texOffs(40, 22).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 3.0F, 3.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(-3.8582F, -17.3683F, -0.8213F, 0.369F, 0.087F, -0.3852F));
        bb_main.addOrReplaceChild("cube_r44", CubeListBuilder.create().texOffs(48, 16).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.6082F, -17.1183F, -3.5713F, 0.2785F, 0.5198F, 0.1474F));
        bb_main.addOrReplaceChild("cube_r45", CubeListBuilder.create().texOffs(48, 12).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.6082F, -20.1183F, -4.5713F, -0.2155F, 0.2123F, 0.0434F));
        bb_main.addOrReplaceChild("cube_r46", CubeListBuilder.create().texOffs(48, 8).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.2991F, -18.7825F, -4.0718F, 0.4224F, -0.2524F, 0.1743F));
        bb_main.addOrReplaceChild("cube_r47", CubeListBuilder.create().texOffs(48, 4).addBox(-1.0F, -1.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -15.0F, -4.0F, 0.0181F, 0.1074F, 0.3328F));

        return LayerDefinition.create(meshdefinition, 128, 128);
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        bb_main.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
