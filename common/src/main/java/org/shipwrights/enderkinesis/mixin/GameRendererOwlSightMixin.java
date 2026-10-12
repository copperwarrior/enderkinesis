package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.shipwrights.enderkinesis.client.OwlSightPasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererOwlSightMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;F)V",
            shift = At.Shift.AFTER
        )
    )
    private void enderkinesis$owlSightHand(CallbackInfo ci) {
        OwlSightPasses.INSTANCE.onAfterHand();
    }

    // Ordinal 0 is the depth clear just before the GUI projection is set up.
    @Inject(
        method = "render(FJZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
            ordinal = 0,
            shift = At.Shift.AFTER
        )
    )
    private void enderkinesis$owlSightGuiBegin(CallbackInfo ci) {
        OwlSightPasses.INSTANCE.onGuiBegin();
    }

    @Inject(method = "render(FJZ)V", at = @At("TAIL"))
    private void enderkinesis$owlSightGuiEnd(CallbackInfo ci) {
        OwlSightPasses.INSTANCE.onGuiEnd();
    }
}
