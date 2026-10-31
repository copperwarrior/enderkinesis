package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.shipwrights.enderkinesis.client.SatchelOfShelvingLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** HumanoidArmorLayer draws only ArmorItems, so a satchel in the chest slot needs its own layer. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererSatchelLayerMixin
    extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    protected PlayerRendererSatchelLayerMixin(
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
    private void enderkinesis$addSatchelLayer(EntityRendererProvider.Context ctx, boolean slim, CallbackInfo ci) {
        this.addLayer(new SatchelOfShelvingLayer<>(this));
    }
}
