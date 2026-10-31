package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.shipwrights.enderkinesis.spell.SpellVisuals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the VS2 body rotation since the freeze, about the mob middle, before the model facing. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererFrozenTumbleMixin {

    @Inject(method = "setupRotations", at = @At("HEAD"))
    private void enderkinesis$tumble(LivingEntity entity, PoseStack pose, float ageInTicks, float rotationYaw,
                                     float partialTick, CallbackInfo ci) {
        Quaternionf tumble = SpellVisuals.clientPose(entity.getId(), partialTick);
        if (tumble == null) return;
        float mid = entity.getBbHeight() / 2f;
        pose.translate(0f, mid, 0f);
        pose.mulPose(tumble);
        pose.translate(0f, -mid, 0f);
    }
}
