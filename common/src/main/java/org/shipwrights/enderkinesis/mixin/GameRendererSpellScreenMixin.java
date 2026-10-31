package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.shipwrights.enderkinesis.client.SpellCubeFx;
import org.shipwrights.enderkinesis.client.SpellScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spell cube effects apply before the hand, while the world depth stays. Hollowed applies after the hand, before the HUD. */
@Mixin(GameRenderer.class)
public abstract class GameRendererSpellScreenMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void enderkinesis$levelStarts(float partialTick, long finishNanoTime, PoseStack pose, CallbackInfo ci) {
        SpellCubeFx.beginLevel();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void enderkinesis$beforeHand(float partialTick, long finishNanoTime, PoseStack pose, CallbackInfo ci) {
        SpellScreen.beforeHand();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void enderkinesis$spellScreen(float partialTick, long finishNanoTime, PoseStack pose, CallbackInfo ci) {
        SpellScreen.render();
    }

    @Inject(method = "bobHurt", at = @At("RETURN"))
    private void enderkinesis$weightedRoll(PoseStack pose, float partialTick, CallbackInfo ci) {
        SpellScreen.roll(pose, partialTick);
    }
}
