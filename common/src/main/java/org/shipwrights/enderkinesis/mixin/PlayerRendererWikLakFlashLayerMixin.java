package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.shipwrights.enderkinesis.client.WikLakRedirectFlashLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the {@link WikLakRedirectFlashLayer} to every {@link PlayerRenderer}
 * instance (vanilla creates two: "default" and "slim") at construction time,
 * so the layer's per-frame render hook fires for every rendered player. No
 * per-loader Layers-Add hook is needed — this mixin runs identically on
 * Fabric and Forge.
 *
 * <p>Extends {@link LivingEntityRenderer} (the parent of
 * {@link PlayerRenderer}) so the protected {@code addLayer} method is
 * accessible from inside the class hierarchy. The constructor here is
 * declarative only — Mixin merges the class with {@link PlayerRenderer}
 * and never instantiates it directly.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererWikLakFlashLayerMixin
    extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    protected PlayerRendererWikLakFlashLayerMixin(
        EntityRendererProvider.Context ctx,
        PlayerModel<AbstractClientPlayer> model,
        float shadowRadius
    ) {
        super(ctx, model, shadowRadius);
    }

    @Inject(
        method = "<init>(Lnet/minecraft/client/renderer/entity/EntityRendererProvider$Context;Z)V",
        at = @At("RETURN")
    )
    private void enderkinesis$addWikLakFlashLayer(
        EntityRendererProvider.Context ctx, boolean slim, CallbackInfo ci
    ) {
        this.addLayer(new WikLakRedirectFlashLayer(this));
    }
}
