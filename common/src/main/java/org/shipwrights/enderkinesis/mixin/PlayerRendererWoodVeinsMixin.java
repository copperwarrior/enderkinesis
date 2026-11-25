package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.shipwrights.enderkinesis.client.WoodVeinLayer;
import org.shipwrights.enderkinesis.client.WoodVeins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The first-person arm and sleeve are drawn in renderHand, outside the render layers.
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererWoodVeinsMixin
    extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    protected PlayerRendererWoodVeinsMixin(
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
    private void enderkinesis$addWoodVeinLayer(EntityRendererProvider.Context ctx, boolean slim, CallbackInfo ci) {
        this.addLayer(new WoodVeinLayer(this));
    }

    @Inject(method = "renderHand", at = @At("TAIL"))
    private void enderkinesis$woodVeinHand(PoseStack poseStack, MultiBufferSource buffers, int light,
                                           AbstractClientPlayer player, ModelPart arm, ModelPart sleeve,
                                           CallbackInfo ci) {
        WoodVeins.renderHand(poseStack, buffers, light, player, arm, arm == this.getModel().rightArm);
    }
}
