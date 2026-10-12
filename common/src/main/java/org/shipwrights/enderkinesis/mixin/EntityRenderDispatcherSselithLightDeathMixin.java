package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.SselithEclipseClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherSselithLightDeathMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void enderkinesis$hideLightDeathBody(
        E entity, double x, double y, double z, float yaw, float partialTick,
        PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci
    ) {
        if (SselithEclipseClient.isDusted(entity)) ci.cancel();
    }
}
