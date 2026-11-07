package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.shipwrights.enderkinesis.client.SilverpointView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererSilverpointMixin {
    // The depth clear before the hand: the level is drawn, its depth is still there, and the projection is the level's.
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void enderkinesis$silverpointDepth(float partialTick, long finishNanoTime, PoseStack pose, CallbackInfo ci) {
        SilverpointView.afterLevel();
    }

    // Frame start, before any target is bound for the frame: the viewfinder's gather and bake run here each frame.
    @Inject(method = "render", at = @At("HEAD"))
    private void enderkinesis$silverpointFrame(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        SilverpointView.frame();
    }
}
