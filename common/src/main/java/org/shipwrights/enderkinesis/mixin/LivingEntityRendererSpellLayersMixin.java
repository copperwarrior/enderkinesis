package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.client.SpellLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws {@link SpellLayers} (Frozen ice, Entangled roots, Crystallized crystals) after any
 *  living entity, so every mob gets them without a layer per renderer. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererSpellLayersMixin {

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("TAIL"))
    private void enderkinesis$spellLayers(LivingEntity entity, float yaw, float partialTick, PoseStack pose,
                                          MultiBufferSource buffers, int light, CallbackInfo ci) {
        SpellLayers.render(entity, partialTick, pose, buffers, light);
    }
}
