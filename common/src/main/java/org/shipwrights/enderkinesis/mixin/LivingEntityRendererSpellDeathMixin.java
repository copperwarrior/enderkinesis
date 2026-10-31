package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.spell.SpellDeaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererSpellDeathMixin {

    @Inject(method = "getFlipDegrees", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noTip(LivingEntity entity, CallbackInfoReturnable<Float> cir) {
        if (!SpellDeaths.tipsOver(entity)) cir.setReturnValue(0f);
    }
}
