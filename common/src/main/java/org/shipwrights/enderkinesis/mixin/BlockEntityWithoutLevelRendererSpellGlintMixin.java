package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.SpellGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Covers items with their own models (shield, trident). {@link ItemRendererSpellGlintMixin} covers all other items. */
@Mixin(BlockEntityWithoutLevelRenderer.class)
public abstract class BlockEntityWithoutLevelRendererSpellGlintMixin {
    @ModifyVariable(method = "renderByItem", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource enderkinesis$spellGlint(MultiBufferSource buffers, @Local(argsOnly = true) ItemStack stack) {
        return SpellGlint.over(buffers, stack);
    }
}
