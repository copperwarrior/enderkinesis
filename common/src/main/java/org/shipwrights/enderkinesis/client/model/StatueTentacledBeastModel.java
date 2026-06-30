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

/** Statue: Tentacled Beast. Blockbench export. 128x128 atlas. */
public class StatueTentacledBeastModel extends EntityModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION =
        new ModelLayerLocation(new ResourceLocation(EnderkinesisMod.MOD_ID, "statue_tentacled_beast"), "main");
    private final ModelPart bone;
    private final ModelPart bone2;
    private final ModelPart bone7;
    private final ModelPart bone8;
    private final ModelPart bone10;
    private final ModelPart bone9;
    private final ModelPart bone4;
    private final ModelPart bone3;
    private final ModelPart bb_main;

    public StatueTentacledBeastModel(ModelPart root) {
        this.bone = root.getChild("bone");
        this.bone2 = root.getChild("bone2");
        this.bone7 = root.getChild("bone7");
        this.bone8 = root.getChild("bone8");
        this.bone10 = root.getChild("bone10");
        this.bone9 = root.getChild("bone9");
        this.bone4 = root.getChild("bone4");
        this.bone3 = root.getChild("bone3");
        this.bb_main = root.getChild("bb_main");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition bone = partdefinition.addOrReplaceChild("bone", CubeListBuilder.create(), PartPose.offset(3.0981F, 5.7247F, -3.9953F));
        bone.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(46, 63).addBox(-0.5F, -0.25F, -0.5F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -3.0F, 0.0F, 0.2661F, -0.081F, -0.1337F));
        bone.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(62, 65).addBox(-0.175F, -0.25F, 0.375F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -0.1234F, -0.026F, -0.4617F));

        PartDefinition bone2 = partdefinition.addOrReplaceChild("bone2", CubeListBuilder.create(), PartPose.offset(2.3246F, 5.8388F, -4.3451F));
        bone2.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(42, 63).addBox(-0.5F, -0.75F, 0.0F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -3.0F, 0.0F, 0.0473F, -0.0891F, -0.0278F));
        bone2.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(58, 65).addBox(-0.2F, -0.75F, 0.0F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -0.3021F, -0.123F, -0.2958F));

        PartDefinition bone7 = partdefinition.addOrReplaceChild("bone7", CubeListBuilder.create(), PartPose.offset(1.3503F, 6.0989F, -4.4106F));
        bone7.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(38, 63).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -3.0F, 0.0F, 0.0035F, -0.0789F, 0.0183F));
        bone7.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(64, 36).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -0.4774F, -0.0616F, 0.0527F));

        PartDefinition bone8 = partdefinition.addOrReplaceChild("bone8", CubeListBuilder.create(), PartPose.offset(0.3503F, 5.8489F, -4.4106F));
        bone8.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(34, 63).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -3.0F, 0.0F, -0.2153F, -0.0731F, 0.035F));
        bone8.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(54, 63).addBox(-0.6F, 0.25F, -0.525F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -0.609F, -0.0876F, -0.0203F));

        PartDefinition bone10 = partdefinition.addOrReplaceChild("bone10", CubeListBuilder.create(), PartPose.offset(-1.6685F, 6.7547F, -4.426F));
        bone10.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(26, 63).addBox(-0.5F, -1.0F, 0.0F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -3.0F, 0.0F, 0.0918F, -0.0651F, 0.0972F));
        bone10.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(50, 63).addBox(-0.75F, -1.0F, 0.0F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -0.2557F, -0.028F, 0.1135F));

        PartDefinition bone9 = partdefinition.addOrReplaceChild("bone9", CubeListBuilder.create(), PartPose.offset(-0.6497F, 6.5989F, -4.4106F));
        bone9.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(30, 63).addBox(-0.5F, -0.5F, 0.0F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -3.0F, 0.0F, 0.0476F, -0.0702F, 0.0574F));
        bone9.addOrReplaceChild("cube_r12", CubeListBuilder.create().texOffs(22, 63).addBox(-0.65F, 0.55F, 0.075F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.3926F, -0.0041F, 0.1678F));

        PartDefinition bone4 = partdefinition.addOrReplaceChild("bone4", CubeListBuilder.create(), PartPose.offsetAndRotation(5.7148F, 3.2159F, 1.0039F, 0.0F, 0.0F, -0.5236F));
        bone4.addOrReplaceChild("cube_r13", CubeListBuilder.create().texOffs(48, 0).addBox(-1.5F, -2.0F, -5.0F, 3.0F, 3.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-0.2148F, 0.7841F, 0.7461F, 1.0111F, -0.1334F, -0.2874F));
        bone4.addOrReplaceChild("cube_r14", CubeListBuilder.create().texOffs(22, 44).addBox(-1.5F, -2.0F, -7.0F, 3.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.2852F, 4.0341F, -2.2539F, -0.4409F, -0.3093F, -0.4935F));

        PartDefinition bone5 = bone4.addOrReplaceChild("bone5", CubeListBuilder.create(), PartPose.offsetAndRotation(-0.4567F, 0.8003F, 0.3886F, 0.4007F, 0.0856F, 0.5548F));
        bone5.addOrReplaceChild("cube_r15", CubeListBuilder.create().texOffs(48, 18).addBox(-1.5F, -2.0F, -5.0F, 3.0F, 3.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-0.2148F, 0.7841F, 0.7461F, 1.0111F, -0.1334F, -0.2874F));
        bone5.addOrReplaceChild("cube_r16", CubeListBuilder.create().texOffs(0, 46).addBox(-1.5F, -2.0F, -7.0F, 3.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.2852F, 4.0341F, -2.2539F, 0.0212F, -0.2457F, -0.6634F));

        PartDefinition bone6 = bone4.addOrReplaceChild("bone6", CubeListBuilder.create(), PartPose.offsetAndRotation(-7.9706F, -1.804F, 0.0076F, 1.4674F, -0.5648F, 0.5791F));
        bone6.addOrReplaceChild("cube_r17", CubeListBuilder.create().texOffs(0, 35).addBox(-1.5F, -2.0F, -7.0F, 3.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.5F, 2.75F, -2.0F, -1.1018F, 0.4816F, -0.8874F));
        bone6.addOrReplaceChild("cube_r18", CubeListBuilder.create().texOffs(48, 27).addBox(-1.5F, -2.0F, -5.0F, 3.0F, 3.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 1.0111F, -0.1334F, 0.978F));

        PartDefinition bone3 = partdefinition.addOrReplaceChild("bone3", CubeListBuilder.create(), PartPose.offsetAndRotation(-3.75F, 4.0F, 0.75F, 0.0F, 0.0F, 1.0908F));
        bone3.addOrReplaceChild("cube_r19", CubeListBuilder.create().texOffs(44, 44).addBox(-1.5F, -2.0F, -7.0F, 3.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.5F, 2.75F, -2.0F, -1.1018F, 0.4816F, -0.8874F));
        bone3.addOrReplaceChild("cube_r20", CubeListBuilder.create().texOffs(48, 9).addBox(-1.5F, -2.0F, -5.0F, 3.0F, 3.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 1.0111F, -0.1334F, 0.978F));

        PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 0).addBox(-6.0F, -10.0F, -6.0F, 12.0F, 10.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        bb_main.addOrReplaceChild("cube_r21", CubeListBuilder.create().texOffs(0, 63).addBox(-2.0F, -1.5F, -1.0F, 4.0F, 3.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.7018F, -10.2049F, 3.4771F, 2.4629F, -0.2917F, 2.9935F));
        bb_main.addOrReplaceChild("cube_r22", CubeListBuilder.create().texOffs(60, 60).addBox(-2.0F, -1.5F, -1.0F, 4.0F, 3.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.0482F, -10.2049F, 3.4771F, 2.5113F, 0.142F, -3.0316F));
        bb_main.addOrReplaceChild("cube_r23", CubeListBuilder.create().texOffs(0, 57).addBox(-2.0F, -3.5F, -1.0F, 4.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.7785F, -8.8582F, -1.7159F, -0.5756F, -0.0522F, -0.2413F));
        bb_main.addOrReplaceChild("cube_r24", CubeListBuilder.create().texOffs(60, 55).addBox(-4.0F, -1.0F, -3.0F, 4.0F, 3.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -9.25F, 1.0F, -0.6068F, 0.0749F, 0.1074F));
        bb_main.addOrReplaceChild("cube_r25", CubeListBuilder.create().texOffs(38, 55).addBox(-3.0F, -4.5F, -0.5F, 5.0F, 7.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(6.7824F, -13.4526F, 7.6051F, 0.3707F, 0.0994F, 0.0852F));
        bb_main.addOrReplaceChild("cube_r26", CubeListBuilder.create().texOffs(50, 55).addBox(-1.0F, -4.5F, -0.5F, 4.0F, 7.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.8149F, -14.8956F, 8.4088F, 0.569F, -0.0333F, -0.0061F));
        bb_main.addOrReplaceChild("cube_r27", CubeListBuilder.create().texOffs(22, 55).addBox(-5.0F, -3.0F, -0.5F, 7.0F, 7.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(7.6288F, -20.8296F, 4.8913F, 0.3151F, 0.1699F, -0.1684F));
        bb_main.addOrReplaceChild("cube_r28", CubeListBuilder.create().texOffs(12, 57).addBox(-1.0F, -1.0F, -1.5F, 2.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.974F, -19.6644F, 3.5028F, 0.4766F, 0.5491F, 0.1558F));
        bb_main.addOrReplaceChild("cube_r29", CubeListBuilder.create().texOffs(48, 36).addBox(-5.0F, -3.0F, -0.5F, 7.0F, 7.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.8712F, -20.8296F, 4.8913F, 0.501F, -0.2175F, 0.3429F));
        bb_main.addOrReplaceChild("cube_r30", CubeListBuilder.create().texOffs(12, 62).addBox(-1.0F, -1.0F, -1.5F, 2.0F, 2.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.026F, -19.6644F, 3.5028F, 0.7469F, -0.3297F, -0.2912F));
        bb_main.addOrReplaceChild("cube_r31", CubeListBuilder.create().texOffs(24, 22).addBox(-4.0F, -6.0F, -5.0F, 6.0F, 6.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.5F, -22.25F, 1.0F, 0.436F, -0.0052F, -0.1768F));
        bb_main.addOrReplaceChild("cube_r32", CubeListBuilder.create().texOffs(24, 34).addBox(-4.0F, -6.0F, -2.0F, 8.0F, 6.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, -16.25F, 1.0F, 0.0433F, -0.0057F, -0.0001F));
        bb_main.addOrReplaceChild("cube_r33", CubeListBuilder.create().texOffs(0, 22).addBox(-4.0F, -9.0F, -2.0F, 8.0F, 9.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -9.25F, 1.0F, 0.0433F, -0.0057F, 0.1308F));

        return LayerDefinition.create(meshdefinition, 128, 128);
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        bone.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone2.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone7.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone8.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone10.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone9.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone4.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bone3.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bb_main.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
