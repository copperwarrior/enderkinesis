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

/** Statue: Counting. Blockbench export. */
public class StatueCountingModel extends EntityModel<Entity> {
    public static final ModelLayerLocation LAYER_LOCATION =
        new ModelLayerLocation(new ResourceLocation(EnderkinesisMod.MOD_ID, "statue_counting"), "main");
    private final ModelPart bone;
    private final ModelPart bb_main;

    public StatueCountingModel(ModelPart root) {
        this.bone = root.getChild("bone");
        this.bb_main = root.getChild("bb_main");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        PartDefinition bone = partdefinition.addOrReplaceChild("bone", CubeListBuilder.create(), PartPose.offsetAndRotation(-3.1822F, 1.1274F, 1.1069F, 0.0F, 0.0F, -0.3491F));
        bone.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(40, 28).addBox(-1.0F, -1.75F, -2.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(-0.01F)), PartPose.offsetAndRotation(2.1381F, -0.1996F, 0.5597F, -2.2746F, -1.1367F, -1.9893F));
        bone.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(32, 28).addBox(-1.0F, -1.5F, -1.75F, 2.0F, 4.0F, 2.0F, new CubeDeformation(-0.01F)), PartPose.offsetAndRotation(4.9516F, 2.0302F, 2.0343F, -1.3528F, -1.2431F, -2.6492F));
        bone.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(8, 43).addBox(-2.25F, -1.75F, -0.25F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(6.9632F, 4.7994F, 3.082F, 1.1943F, -0.9825F, 0.8699F));
        bone.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(12, 43).addBox(0.5F, -1.5F, -1.5F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(9.0F, 5.0F, 0.0F, -0.0701F, -1.4017F, 2.7647F));

        PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 0).addBox(-6.0F, -10.0F, -6.0F, 12.0F, 10.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

        bb_main.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(28, 42).addBox(-0.25F, -0.5F, -0.5F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.5724F, -15.9336F, -3.8242F, -0.159F, 0.4537F, -0.3967F));
        bb_main.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(4, 44).addBox(-0.5F, -0.5F, -0.5F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.2811F, -18.9092F, -4.0712F, 0.0912F, 0.4429F, 0.1368F));
        bb_main.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(16, 42).addBox(-1.0F, 0.0F, 0.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(-0.3F)), PartPose.offsetAndRotation(-1.1022F, -20.043F, -1.9353F, -1.6105F, 0.4429F, 0.1368F));
        bb_main.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(40, 39).addBox(-1.0F, -2.5F, 0.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.1022F, -20.043F, -1.9353F, -1.7209F, 0.4874F, 0.3759F));
        bb_main.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(44, 44).addBox(-1.0F, -1.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.4766F, -11.3131F, 0.8723F, -2.8446F, 0.2749F, -2.2434F));
        bb_main.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(24, 42).addBox(0.0F, -2.0F, -0.5F, 1.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.5131F, -10.0159F, 4.2991F, 2.9762F, -0.0073F, -1.5008F));
        bb_main.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(32, 34).addBox(-1.0F, -3.75F, -1.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(-4.2147F, -11.4262F, 5.1106F, 2.9785F, 0.0285F, -1.2856F));
        bb_main.addOrReplaceChild("cube_r12", CubeListBuilder.create().texOffs(24, 36).addBox(-1.25F, -2.5F, 0.5F, 2.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.5851F, -13.8884F, 5.4468F, -2.5882F, -0.8553F, -0.147F));
        bb_main.addOrReplaceChild("cube_r13", CubeListBuilder.create().texOffs(40, 34).addBox(-1.0F, -3.5F, 2.5F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.7247F, -18.5977F, 2.0393F, -1.6007F, -0.8005F, -0.3901F));
        bb_main.addOrReplaceChild("cube_r14", CubeListBuilder.create().texOffs(0, 44).addBox(0.0F, -1.5F, -1.0F, 1.0F, 3.0F, 1.0F, new CubeDeformation(0.2F)), PartPose.offsetAndRotation(-5.0017F, -13.769F, -0.3954F, 0.2468F, 0.1375F, 1.0792F));
        bb_main.addOrReplaceChild("cube_r15", CubeListBuilder.create().texOffs(0, 31).addBox(-4.25F, -2.0F, -2.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, -14.0F, -1.0F, -0.5864F, 0.4874F, 0.3759F));
        bb_main.addOrReplaceChild("cube_r16", CubeListBuilder.create().texOffs(40, 44).addBox(0.0F, -2.0F, -0.5F, 1.0F, 2.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.4869F, -11.0159F, 1.2991F, -2.7503F, 0.0943F, -1.7424F));
        bb_main.addOrReplaceChild("cube_r17", CubeListBuilder.create().texOffs(32, 22).addBox(-1.0F, -3.75F, -1.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(2.7853F, -13.6762F, 3.8606F, 2.0338F, 0.9354F, 0.0284F));
        bb_main.addOrReplaceChild("cube_r18", CubeListBuilder.create().texOffs(16, 36).addBox(-1.25F, -2.5F, 0.5F, 2.0F, 4.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.4149F, -15.6384F, 5.4468F, -2.9745F, -0.3122F, -0.2067F));
        bb_main.addOrReplaceChild("cube_r19", CubeListBuilder.create().texOffs(32, 40).addBox(-1.0F, -1.5F, -1.0F, 2.0F, 3.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.6211F, -15.7898F, 3.3585F, -1.6232F, 1.1623F, -0.4597F));
        bb_main.addOrReplaceChild("cube_r20", CubeListBuilder.create().texOffs(40, 22).addBox(-1.0F, -2.5F, -1.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(-0.3F)), PartPose.offsetAndRotation(1.7041F, -18.857F, -2.3548F, 2.9317F, -0.6702F, -0.291F));
        bb_main.addOrReplaceChild("cube_r21", CubeListBuilder.create().texOffs(0, 38).addBox(-1.0F, -1.75F, -2.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(-0.1F)), PartPose.offsetAndRotation(0.7221F, -21.4249F, -2.0973F, 2.5881F, -0.8005F, -0.3901F));
        bb_main.addOrReplaceChild("cube_r22", CubeListBuilder.create().texOffs(8, 37).addBox(-1.1F, -2.25F, 3.35F, 2.0F, 4.0F, 2.0F, new CubeDeformation(-0.01F)), PartPose.offsetAndRotation(-2.7247F, -18.5977F, 2.0393F, 2.1518F, -0.8005F, -0.3901F));
        bb_main.addOrReplaceChild("cube_r23", CubeListBuilder.create().texOffs(8, 31).addBox(-1.0F, -5.25F, 0.35F, 2.0F, 4.0F, 2.0F, new CubeDeformation(-0.01F)), PartPose.offsetAndRotation(-2.7247F, -18.5977F, 2.0393F, 0.6246F, -0.8005F, -0.3901F));
        bb_main.addOrReplaceChild("cube_r24", CubeListBuilder.create().texOffs(24, 29).addBox(-1.0F, -2.5F, -1.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.7247F, -18.5977F, 2.0393F, -0.0735F, -0.8005F, -0.3901F));
        bb_main.addOrReplaceChild("cube_r25", CubeListBuilder.create().texOffs(16, 29).addBox(-0.75F, 1.0F, 0.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(-0.4F)), PartPose.offsetAndRotation(2.0F, -12.25F, -2.25F, -0.7067F, -0.161F, 1.3824F));
        bb_main.addOrReplaceChild("cube_r26", CubeListBuilder.create().texOffs(24, 22).addBox(0.0F, -2.0F, 0.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(0.2F)), PartPose.offsetAndRotation(2.0F, -12.25F, -2.25F, -0.7217F, 0.0111F, 1.5805F));
        bb_main.addOrReplaceChild("cube_r27", CubeListBuilder.create().texOffs(16, 22).addBox(0.0F, -2.0F, 0.0F, 2.0F, 5.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, -14.0F, -1.0F, -0.379F, -0.8005F, -0.3901F));
        bb_main.addOrReplaceChild("cube_r28", CubeListBuilder.create().texOffs(0, 22).addBox(-1.0F, -2.0F, -1.0F, 4.0F, 5.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.0F, -17.0F, 0.0F, 0.0F, -0.4363F, 0.0F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }

    @Override
    public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        bone.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        bb_main.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
