package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.shipwrights.enderkinesis.client.SpellCubeFx;
import org.shipwrights.enderkinesis.client.SpellScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player's own spell statuses on the whole view ({@link SpellScreen}): the spell cubes'
 *  effects (the world's while the world's depth is still there, before the hand), Hollowed
 *  draining the colour from the whole view, the hand included, before the HUD is drawn, and
 *  Weighted listing the view along with vanilla's hurt tilt. */
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
