package org.shipwrights.enderkinesis.client.model;

import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.shipwrights.enderkinesis.EnderkinesisMod;
import org.shipwrights.enderkinesis.entity.WohlemEntity;

/**
 * Wohlem model — hulking many-eyed brute. Part hierarchy is the artist's
 * Blockbench export verbatim (the {@link WohlemAnimations} keyframes were
 * authored against these exact pivots; bones are resolved by NAME through
 * {@code KeyframeAnimations.animate}).
 *
 * Animation layering mirrors {@code CatalogerModel}: idle and walk states run
 * continuously (phase preserved across stops) and cross-fade by the entity's
 * walk blend; the one-shot scratch flavour REPLACES idle at the idle weight
 * while its state runs; attack layers on top at full weight.
 */
public class WohlemModel extends HierarchicalModel<WohlemEntity> {

	public static final ModelLayerLocation LAYER_LOCATION =
		new ModelLayerLocation(EnderkinesisMod.INSTANCE.id("wohlem"), "main");

	/** Shared scratch vector for KeyframeAnimations.animate — matches what
	 *  HierarchicalModel does internally; rendering is single-threaded. */
	private static final org.joml.Vector3f ANIMATION_VECTOR_CACHE = new org.joml.Vector3f();

	private final ModelPart root;
	private final ModelPart head;

	public WohlemModel(ModelPart root) {
		this.root = root;
		this.head = root.getChild("pelvis").getChild("body").getChild("head");
	}

	@Override
	public ModelPart root() {
		return this.root;
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition pelvis = partdefinition.addOrReplaceChild("pelvis", CubeListBuilder.create(), PartPose.offset(0.0F, 14.0F, 10.0F));

		PartDefinition hip_r1 = pelvis.addOrReplaceChild("hip_r1", CubeListBuilder.create().texOffs(0, 21).addBox(-9.0F, -16.0F, -7.0F, 18.0F, 16.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, 1.0F, 0.3927F, 0.0F, 0.0F));

		PartDefinition right_leg = pelvis.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-9.0F, -1.0F, 1.0F));

		PartDefinition right_thigh_r1 = right_leg.addOrReplaceChild("right_thigh_r1", CubeListBuilder.create().texOffs(80, 69).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.0F, 0.0F, -7.0F, -1.4482F, 0.3006F, -0.0547F));

		PartDefinition right_calf = right_leg.addOrReplaceChild("right_calf", CubeListBuilder.create(), PartPose.offset(-5.0F, 11.0F, -7.0F));

		PartDefinition right_calf_r1 = right_calf.addOrReplaceChild("right_calf_r1", CubeListBuilder.create().texOffs(32, 81).addBox(-3.0F, -11.0F, -3.0F, 6.0F, 11.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, -1.0F, 0.0F, 0.3054F, 0.0F));

		PartDefinition left_leg = pelvis.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(9.0F, -1.0F, 1.0F));

		PartDefinition right_thigh_r2 = left_leg.addOrReplaceChild("right_thigh_r2", CubeListBuilder.create().texOffs(0, 81).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.0F, 0.0F, -7.0F, -1.4482F, -0.3006F, 0.0547F));

		PartDefinition left_calf = left_leg.addOrReplaceChild("left_calf", CubeListBuilder.create(), PartPose.offset(5.0F, 11.0F, -7.0F));

		PartDefinition left_calf_r1 = left_calf.addOrReplaceChild("left_calf_r1", CubeListBuilder.create().texOffs(84, 0).addBox(-3.0F, -11.0F, -3.0F, 6.0F, 11.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 0.0F, -1.0F, 0.0F, -0.3054F, 0.0F));

		PartDefinition body = pelvis.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0.0F, -13.0F, -4.0F));

		PartDefinition body_r1 = body.addOrReplaceChild("body_r1", CubeListBuilder.create().texOffs(56, 49).addBox(-7.0F, -5.0F, -1.0F, 14.0F, 12.0F, 9.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -3.0F, -5.0F, 0.3927F, 0.0F, 0.0F));

		PartDefinition shoulders = body.addOrReplaceChild("shoulders", CubeListBuilder.create(), PartPose.offset(-0.5F, -8.0F, -1.0F));

		PartDefinition shoulders_r1 = shoulders.addOrReplaceChild("shoulders_r1", CubeListBuilder.create().texOffs(0, 0).addBox(-22.0F, -7.0F, -4.0F, 31.0F, 10.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(7.0F, 0.0F, -5.0F, 0.2618F, 0.0F, 0.0F));

		PartDefinition right_arm = shoulders.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-11.5F, 0.0F, -4.0F));

		PartDefinition right_arm_r1 = right_arm.addOrReplaceChild("right_arm_r1", CubeListBuilder.create().texOffs(60, 21).addBox(-3.0F, -21.0F, -3.0F, 6.0F, 21.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 19.0F, -10.0F, -0.48F, 0.0F, 0.0F));

		PartDefinition right_forearm = right_arm.addOrReplaceChild("right_forearm", CubeListBuilder.create(), PartPose.offset(0.0F, 18.0F, -10.0F));

		PartDefinition right_forearm_r1 = right_forearm.addOrReplaceChild("right_forearm_r1", CubeListBuilder.create().texOffs(84, 17).addBox(-2.0F, -14.0F, -2.0F, 4.0F, 14.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 12.0864F, 4.0066F, 0.3491F, 0.0F, 0.0F));

		PartDefinition left_arm = shoulders.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(12.5F, 0.0F, -4.0F));

		PartDefinition left_arm_r1 = left_arm.addOrReplaceChild("left_arm_r1", CubeListBuilder.create().texOffs(56, 69).addBox(-3.0F, -21.0F, -3.0F, 6.0F, 21.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 19.0F, -10.0F, -0.48F, 0.0F, 0.0F));

		PartDefinition left_forearm = left_arm.addOrReplaceChild("left_forearm", CubeListBuilder.create(), PartPose.offset(0.0F, 18.0F, -10.0F));

		PartDefinition left_forearm_r1 = left_forearm.addOrReplaceChild("left_forearm_r1", CubeListBuilder.create().texOffs(80, 87).addBox(-2.0F, -14.0F, -2.0F, 4.0F, 14.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 12.0864F, 4.0066F, 0.3491F, 0.0F, 0.0F));

		PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 49).addBox(-6.0F, -12.0F, -9.0F, 12.0F, 16.0F, 16.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, -13.0F, -5.0F));

		PartDefinition eye_r1 = head.addOrReplaceChild("eye_r1", CubeListBuilder.create().texOffs(40, 98).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(3.0F, -11.0F, 1.0F, -0.0184F, -0.0395F, 0.4367F));

		PartDefinition eye_r2 = head.addOrReplaceChild("eye_r2", CubeListBuilder.create().texOffs(32, 98).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.0F, -11.0F, 2.0F, -0.4724F, -0.043F, -0.1747F));

		PartDefinition eye_r3 = head.addOrReplaceChild("eye_r3", CubeListBuilder.create().texOffs(100, 21).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.2F)), PartPose.offsetAndRotation(2.0F, -11.0F, 5.0F, 0.438F, 0.6051F, 0.1017F));

		PartDefinition eye_r4 = head.addOrReplaceChild("eye_r4", CubeListBuilder.create().texOffs(100, 17).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(4.0F, -11.0F, -6.0F, 0.3566F, -0.043F, -0.1747F));

		PartDefinition eye_r5 = head.addOrReplaceChild("eye_r5", CubeListBuilder.create().texOffs(96, 95).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.8F)), PartPose.offsetAndRotation(1.0F, -11.0F, -4.0F, -0.1233F, -0.043F, -0.1747F));

		PartDefinition eye_r6 = head.addOrReplaceChild("eye_r6", CubeListBuilder.create().texOffs(92, 43).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.0F, -11.0F, -9.0F, -0.0094F, -0.0426F, 0.2184F));

		PartDefinition eye_r7 = head.addOrReplaceChild("eye_r7", CubeListBuilder.create().texOffs(96, 91).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.2F)), PartPose.offsetAndRotation(6.0F, -1.0F, -7.0F, -0.004F, 0.3492F, 0.0858F));

		PartDefinition eye_r8 = head.addOrReplaceChild("eye_r8", CubeListBuilder.create().texOffs(56, 96).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.08F)), PartPose.offsetAndRotation(6.0F, -5.0F, -7.0F, -0.0038F, -0.0435F, 0.0873F));

		PartDefinition eye_r9 = head.addOrReplaceChild("eye_r9", CubeListBuilder.create().texOffs(0, 99).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.0F, -4.0F, 0.0F, 0.0F, -0.2173F, 0.1754F));

		PartDefinition eye_r10 = head.addOrReplaceChild("eye_r10", CubeListBuilder.create().texOffs(8, 99).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-6.0F, -1.0F, -7.0F, -0.004F, -0.3053F, 0.0884F));

		PartDefinition eye_r11 = head.addOrReplaceChild("eye_r11", CubeListBuilder.create().texOffs(16, 99).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.0F, -5.0F, -6.5F, -0.0039F, 0.262F, 0.0862F));

		PartDefinition eye_r12 = head.addOrReplaceChild("eye_r12", CubeListBuilder.create().texOffs(24, 99).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.6F)), PartPose.offsetAndRotation(-6.0F, -8.0F, -3.0F, 0.1691F, -0.0822F, -0.0596F));

		PartDefinition eye_r13 = head.addOrReplaceChild("eye_r13", CubeListBuilder.create().texOffs(96, 99).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(-6.0F, -8.0F, 2.0F, -0.0595F, 0.1182F, -0.1789F));

		PartDefinition eye_r14 = head.addOrReplaceChild("eye_r14", CubeListBuilder.create().texOffs(64, 96).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.14F)), PartPose.offsetAndRotation(6.0F, -4.0F, -2.0F, -0.0478F, -0.2121F, 0.3986F));

		PartDefinition eye_r15 = head.addOrReplaceChild("eye_r15", CubeListBuilder.create().texOffs(72, 96).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(6.0F, -8.0F, 2.0F, -0.0095F, 0.1319F, 0.2167F));

		PartDefinition eye_r16 = head.addOrReplaceChild("eye_r16", CubeListBuilder.create().texOffs(96, 87).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.2F)), PartPose.offsetAndRotation(7.0F, -10.0F, -6.0F, 0.1745F, 0.2618F, 0.0F));

		PartDefinition eye_r17 = head.addOrReplaceChild("eye_r17", CubeListBuilder.create().texOffs(92, 39).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(3.0F, 2.0F, -9.0F, 0.0019F, -0.0436F, -0.0437F));

		PartDefinition eye_r18 = head.addOrReplaceChild("eye_r18", CubeListBuilder.create().texOffs(92, 35).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, -8.0F, -9.0F, -0.0128F, -0.7407F, 0.2266F));

		PartDefinition eye_r19 = head.addOrReplaceChild("eye_r19", CubeListBuilder.create().texOffs(84, 43).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.0F, 0.0F, -9.0F, 0.0167F, -0.0403F, -0.393F));

		PartDefinition eye_r20 = head.addOrReplaceChild("eye_r20", CubeListBuilder.create().texOffs(84, 39).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(3.0F, -3.0F, -9.0F, -0.0094F, -0.0426F, 0.2184F));

		PartDefinition eye_r21 = head.addOrReplaceChild("eye_r21", CubeListBuilder.create().texOffs(84, 35).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.0F, -5.0F, -9.0F, 0.1745F, 0.2618F, 0.0F));

		PartDefinition eye_r22 = head.addOrReplaceChild("eye_r22", CubeListBuilder.create().texOffs(48, 98).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.1F)), PartPose.offsetAndRotation(-3.0F, -11.0F, -2.0F, 0.0084F, 0.437F, -0.1708F));

		return LayerDefinition.create(meshdefinition, 128, 128);
	}

	@Override
	public void setupAnim(WohlemEntity entity, float limbSwing, float limbSwingAmount,
			float ageInTicks, float netHeadYaw, float headPitch) {
		this.root.getAllParts().forEach(ModelPart::resetPose);
		this.head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
		this.head.xRot = headPitch * Mth.DEG_TO_RAD;

		// Advance every state's clock — we call KeyframeAnimations directly
		// (for per-layer weights), which skips the updateTime that
		// HierarchicalModel.animate would otherwise do.
		entity.getIdleAnimationState().updateTime(ageInTicks, 1f);
		entity.getWalkingAnimationState().updateTime(ageInTicks, 1f);
		entity.getScratchAnimationState().updateTime(ageInTicks, 1f);
		entity.getAttackAnimationState().updateTime(ageInTicks, 1f);

		// Sub-tick-smooth idle/walk cross-fade (see CatalogerModel for the
		// rationale on lerping the previous/current blend by partial tick).
		float partialTick = Mth.clamp(ageInTicks - (float) entity.tickCount, 0f, 1f);
		float rawBlend = Mth.lerp(partialTick, entity.getWalkBlendOld(), entity.getWalkBlend());
		final float blend = rawBlend * rawBlend * rawBlend * (rawBlend * (rawBlend * 6f - 15f) + 10f);   // smootherstep

		if (blend < 1f) {
			float idleWeight = 1f - blend;
			if (entity.getScratchAnimationState().isStarted()) {
				entity.getScratchAnimationState().ifStarted(state -> KeyframeAnimations.animate(
					this, WohlemAnimations.idle_scratch, state.getAccumulatedTime(), idleWeight,
					ANIMATION_VECTOR_CACHE));
			} else {
				entity.getIdleAnimationState().ifStarted(state -> KeyframeAnimations.animate(
					this, WohlemAnimations.idle, state.getAccumulatedTime(), idleWeight,
					ANIMATION_VECTOR_CACHE));
			}
		}
		if (blend > 0f) {
			entity.getWalkingAnimationState().ifStarted(state -> KeyframeAnimations.animate(
				this, WohlemAnimations.walk, state.getAccumulatedTime(), blend,
				ANIMATION_VECTOR_CACHE));
		}
		entity.getAttackAnimationState().ifStarted(state -> KeyframeAnimations.animate(
			this, WohlemAnimations.attack, state.getAccumulatedTime(), 1f,
			ANIMATION_VECTOR_CACHE));
	}
}
