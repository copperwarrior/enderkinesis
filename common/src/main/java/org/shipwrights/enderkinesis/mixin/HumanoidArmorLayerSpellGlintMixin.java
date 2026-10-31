package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.client.SpellGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Imbued armour, worn, shimmers with its spell's glint ({@link SpellGlint}) in place of
 *  vanilla's purple one. */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerSpellGlintMixin {
    @ModifyVariable(method = "renderArmorPiece", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource enderkinesis$spellGlint(MultiBufferSource buffers, @Local(argsOnly = true) LivingEntity entity, @Local(argsOnly = true) EquipmentSlot slot) {
        return SpellGlint.over(buffers, entity.getItemBySlot(slot));
    }
}
