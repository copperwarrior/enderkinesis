package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.ScryingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Force the local player's body to render while a scry session is active.
 *
 * <p>Two cull gates would otherwise drop the player from the camera's view at the
 * target: {@code Entity.noCulling} (frustum/distance bypass — already set true on
 * scry start) AND {@code Entity.shouldRender(camX, camY, camZ)} which does the
 * default ~64-block distance check BEFORE noCulling is consulted in
 * {@code EntityRenderer.shouldRender}. With a far target the player is well past
 * 64 blocks from the camera and gets culled here.
 *
 * <p>This mixin head-injects the entity-side distance check and forces a true return
 * when the entity is {@code Minecraft.player} and a session is live. Combined with
 * the existing {@code noCulling = true}, the player's model survives both gates and
 * vanilla's frustum check decides whether the model is actually drawn — i.e., it
 * shows up when the camera angle puts the source in view.
 */
@Mixin(Entity.class)
public abstract class EntityScryingRenderMixin {

    @Inject(method = "shouldRender(DDD)Z", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$bypassDistanceCullingForScryingPlayer(
        double camX, double camY, double camZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!ScryingClient.isActive()) return;
        if ((Object) this != Minecraft.getInstance().player) return;
        cir.setReturnValue(true);
    }
}
