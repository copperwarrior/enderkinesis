package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ThrownTridentRenderer;
import net.minecraft.world.entity.projectile.ThrownTrident;
import org.shipwrights.enderkinesis.client.ImbueVfx;
import org.shipwrights.enderkinesis.client.SpellGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A thrown trident shimmers with its imbue's glint ({@link SpellGlint}) while the spell waits
 *  in it, in place of vanilla's purple one, and stops once it strikes ({@link ImbueVfx#shotGlint}). */
@Mixin(ThrownTridentRenderer.class)
public abstract class ThrownTridentRendererSpellGlintMixin {
    @ModifyVariable(
        method = "render(Lnet/minecraft/world/entity/projectile/ThrownTrident;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"), argsOnly = true
    )
    private MultiBufferSource enderkinesis$spellGlint(MultiBufferSource buffers, @Local(argsOnly = true) ThrownTrident trident) {
        return SpellGlint.over(buffers, ImbueVfx.INSTANCE.shotGlint(trident.getId()));
    }
}
