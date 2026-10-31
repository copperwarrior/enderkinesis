package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.SpellGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Items vanilla draws as models of their own (a shield, a trident, in the hand or the
 *  inventory) shimmer with their imbue's glint ({@link SpellGlint}) in place of vanilla's
 *  purple one. Ordinary items are seen to by {@link ItemRendererSpellGlintMixin}. */
@Mixin(BlockEntityWithoutLevelRenderer.class)
public abstract class BlockEntityWithoutLevelRendererSpellGlintMixin {
    @ModifyVariable(method = "renderByItem", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource enderkinesis$spellGlint(MultiBufferSource buffers, @Local(argsOnly = true) ItemStack stack) {
        return SpellGlint.over(buffers, stack);
    }
}
