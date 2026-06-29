package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.client.ScryingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Pitch-lock the local player's head model to the source orb during a scry session
 * without touching {@code Entity.xRot}.
 *
 * <p>{@code xRot} drives the camera direction through vanilla {@code Camera.setup}
 * (the pose-override mixin only replaces position, not rotation), so overriding it
 * on the entity would force the remote camera to look down at whatever's beneath the
 * orb instead of where the user's mouse points. The cleaner path is to intercept the
 * model setup: every {@code LivingEntityRenderer.render} call invokes
 * {@code EntityModel.setupAnim(entity, limbSwing, limbAmount, ageInTicks, netHeadYaw,
 * headPitch)} with the headPitch arg derived from {@code Mth.lerp(partialTick,
 * xRotO, xRot)}. {@code @ModifyArg index=5} substitutes our scry-derived pitch on
 * that one call when the entity is the local player and a session is live.
 *
 * <p>Pairs with the body / head yaw lock written into the entity from
 * {@link ScryingClient#lockPlayerLookAtOrb}: yaw can be set on the entity safely
 * because we leave {@code yRot} alone (yaw of the camera also pulled from {@code yRot}
 * through vanilla setup).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererScryingMixin {

    @ModifyArg(
        method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"),
        index = 5
    )
    private float enderkinesis$overrideHeadPitchForScrying(
        float headPitch,
        @Local(argsOnly = true) LivingEntity entity
    ) {
        if (entity != Minecraft.getInstance().player) return headPitch;
        Float scryPitch = ScryingClient.scryingHeadPitch();
        if (scryPitch == null) return headPitch;
        return scryPitch;
    }
}
