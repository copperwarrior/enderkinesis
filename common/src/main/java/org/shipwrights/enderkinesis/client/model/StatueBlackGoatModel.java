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

/** Statue: Black Goat. Blockbench export. 128x128 atlas. */
public class StatueBlackGoatModel extends EntityModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION =
        new ModelLayerLocation(new ResourceLocation(EnderkinesisMod.MOD_ID, "statue_black_goat"), "main");
    private final ModelPart bb_main;

    public StatueBlackGoatModel(ModelPart root) {
        this.bb_main = root.getChild("bb_main");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 0).addBox(-6.0F, -10.0F, -6.0F, 12.0F, 10.0F, 12.0F, new CubeDeformation(0.0F))
            .texOffs(0, 40).addBox(-5.0F, -15.0F, -1.5F, 10.0F, 5.0F, 7.0F, new CubeDeformation(0.0F))
            .texOffs(34, 40).addBox(-3.5F, -24.0F, -1.0F, 7.0F, 9.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        bb_main.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(18, 67).mirror().addBox(-0.5F, -4.0F, -2.5F, 2.0F, 8.0F, 2.0F, new CubeDeformation(0.0F)).mirror(false)
            .texOffs(18, 67).addBox(2.5F, -4.0F, -2.5F, 2.0F, 8.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.0F, -33.763F, -1.1644F, 0.4363F, 0.0F, 0.0F));
        bb_main.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(66, 65).addBox(-1.0F, -2.0F, -1.0F, 4.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.0F, -27.3618F, 2.3033F, 0.2084F, -0.0651F, 0.2986F));
        bb_main.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(60, 46).addBox(-3.0F, -2.0F, -1.0F, 4.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(3.0F, -27.3618F, 2.3033F, 0.2084F, 0.0651F, -0.2986F));
        bb_main.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(60, 36).addBox(-1.0F, -5.0F, -3.0F, 4.0F, 4.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.0F, -24.0F, 1.0F, 0.48F, 0.0F, 0.0F));
        bb_main.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(48, 0).addBox(-2.0F, -7.0F, -2.0F, 6.0F, 8.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.0F, -24.0F, 1.0F, 0.2182F, 0.0F, 0.0F));
        bb_main.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(48, 55).addBox(-4.0F, -6.0F, -1.0F, 8.0F, 5.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -11.0F, -2.0F, -0.3491F, 0.0F, 0.0F));
        bb_main.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(72, 0).addBox(-0.0341F, -0.2621F, -0.5207F, 3.0F, 1.0F, 2.0F, new CubeDeformation(0.15F)), PartPose.offsetAndRotation(-6.014F, -27.6067F, -3.8278F, -1.4263F, 1.1609F, -0.2895F));
        bb_main.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(60, 52).addBox(-2.7909F, -0.2621F, -0.5207F, 3.0F, 1.0F, 2.0F, new CubeDeformation(0.15F)), PartPose.offsetAndRotation(7.5404F, -17.57F, -5.4678F, 0.1945F, -0.3748F, -0.5724F));
        bb_main.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(24, 52).addBox(-0.2091F, -0.2621F, -0.5207F, 3.0F, 1.0F, 2.0F, new CubeDeformation(0.15F)), PartPose.offsetAndRotation(-7.163F, -26.9869F, -4.5664F, -1.9175F, 1.1381F, -0.8256F));
        bb_main.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(56, 25).addBox(-1.5F, -1.0F, -7.0F, 3.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-5.0F, -21.6345F, -0.9359F, -0.6028F, 0.8015F, 0.6648F));
        bb_main.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(70, 52).addBox(-2.9659F, -0.2621F, -0.5207F, 3.0F, 1.0F, 2.0F, new CubeDeformation(0.15F)), PartPose.offsetAndRotation(6.9713F, -18.9439F, -5.6636F, 0.2322F, -0.6735F, -0.6468F));
        bb_main.addOrReplaceChild("cube_r12", CubeListBuilder.create().texOffs(48, 65).addBox(-1.5F, -2.0F, -5.0F, 3.0F, 4.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.5F, -22.0F, 3.0F, 0.1283F, 0.1719F, -0.0306F));
        bb_main.addOrReplaceChild("cube_r13", CubeListBuilder.create().texOffs(56, 14).addBox(-1.5F, -1.0F, -7.0F, 3.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, -18.75F, 0.75F, 0.1822F, -0.1176F, -0.522F));
        bb_main.addOrReplaceChild("cube_r14", CubeListBuilder.create().texOffs(0, 64).addBox(-1.5F, -2.0F, -5.0F, 3.0F, 4.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.5F, -22.0F, 3.0F, 1.0111F, -0.1334F, -0.1129F));
        bb_main.addOrReplaceChild("cube_r15", CubeListBuilder.create().texOffs(24, 55).addBox(-4.0F, -8.0F, -1.0F, 7.0F, 7.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, -13.0F, 0.5F, 0.0F, 0.0F, -0.1745F));
        bb_main.addOrReplaceChild("cube_r16", CubeListBuilder.create().texOffs(0, 52).addBox(-3.0F, -8.0F, -1.0F, 7.0F, 7.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.0F, -13.0F, 0.5F, 0.0F, 0.0F, 0.1745F));
        bb_main.addOrReplaceChild("cube_r17", CubeListBuilder.create().texOffs(28, 22).addBox(-4.0F, -8.0F, 0.0F, 4.0F, 8.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.25F, -4.0F, -7.0F, 0.176F, 0.1289F, -0.0229F));
        bb_main.addOrReplaceChild("cube_r18", CubeListBuilder.create().texOffs(0, 22).addBox(-4.0F, -8.0F, 0.0F, 4.0F, 8.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.5F, -4.0F, -8.0F, 0.0F, -0.1309F, 0.1745F));

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
