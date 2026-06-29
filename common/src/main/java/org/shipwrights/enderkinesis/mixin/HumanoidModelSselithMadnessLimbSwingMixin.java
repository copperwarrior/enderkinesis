package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.registry.EKEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slows walk-cycle limbs to quarter rate × half amplitude while
 * {@link org.shipwrights.enderkinesis.effect.SselithMadnessEffect} is active, without
 * changing movement speed.
 *
 * <p>Adds the dampened walk-cycle delta rather than overwriting — preserves every other
 * contribution {@code setupAnim} makes (crouching, riding, elytra, swimming, bow draw,
 * attack swing). Targeting {@code HumanoidModel} (not {@code PlayerModel}) keeps the skin
 * and every armor layer in lockstep: armor layers' {@code copyPropertiesTo} fires
 * {@code setupAnim} on each layer's own {@code HumanoidModel}.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelSselithMadnessLimbSwingMixin {

    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
        at = @At("TAIL")
    )
    private void enderkinesis$dampenMadnessLimbs(
        LivingEntity entity, float limbSwing, float limbSwingAmount,
        float ageInTicks, float netHeadYaw, float headPitch,
        CallbackInfo ci
    ) {
        if (!(entity instanceof Player player)) return;
        if (!player.hasEffect(EKEffects.sselithMadnessEffect())) return;

        HumanoidModel<?> self = (HumanoidModel<?>) (Object) this;

        // Vanilla walk-cycle contributions in setupAnim (`PI` = (float) Math.PI):
        //   rightArm.xRot = cos(limbSwing × 0.6662 + π) × 2.0 × limbSwingAmount × 0.5
        //    leftArm.xRot = cos(limbSwing × 0.6662)     × 2.0 × limbSwingAmount × 0.5
        //   rightLeg.xRot = cos(limbSwing × 0.6662)     × 1.4 × limbSwingAmount
        //    leftLeg.xRot = cos(limbSwing × 0.6662 + π) × 1.4 × limbSwingAmount
        //
        // For each part, subtract the original walk-cycle contribution
        // and add a scaled one — **quarter** cycle rate (slow gait) with
        // **half** swing amplitude. Anything else setupAnim has piled
        // onto the same rotation — crouching, swimming, attacking, bow
        // draw, spyglass pose, elytra flight, riding — survives this
        // delta untouched.
        final float scaledSwing = limbSwing * 0.25f;
        final float scaledAmount = limbSwingAmount * 0.5f;
        final float phase = limbSwing * 0.6662f;
        final float scaledPhase = scaledSwing * 0.6662f;
        final float pi = (float) Math.PI;

        // Arms (xRot uses ×2.0 × amount × 0.5 = ×1.0 × amount).
        final float origRightArm = Mth.cos(phase + pi) * 2.0f * limbSwingAmount * 0.5f;
        final float origLeftArm = Mth.cos(phase) * 2.0f * limbSwingAmount * 0.5f;
        final float newRightArm = Mth.cos(scaledPhase + pi) * 2.0f * scaledAmount * 0.5f;
        final float newLeftArm = Mth.cos(scaledPhase) * 2.0f * scaledAmount * 0.5f;
        self.rightArm.xRot += (newRightArm - origRightArm);
        self.leftArm.xRot += (newLeftArm - origLeftArm);

        // Legs (xRot uses ×1.4 × amount).
        final float origRightLeg = Mth.cos(phase) * 1.4f * limbSwingAmount;
        final float origLeftLeg = Mth.cos(phase + pi) * 1.4f * limbSwingAmount;
        final float newRightLeg = Mth.cos(scaledPhase) * 1.4f * scaledAmount;
        final float newLeftLeg = Mth.cos(scaledPhase + pi) * 1.4f * scaledAmount;
        self.rightLeg.xRot += (newRightLeg - origRightLeg);
        self.leftLeg.xRot += (newLeftLeg - origLeftLeg);
    }

}
