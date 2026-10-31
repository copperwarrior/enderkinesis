package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.WohlonWaterMurk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Copies main depth before the translucent chunk layer, so the water murk knows the depth behind the water. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererWohlonWaterMurkMixin {

    // Both renderLevel branches (Fabulous and not) call this; Sodium replaces the body, not the call.
    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V"
        )
    )
    private void enderkinesis$copyDepthBeforeTranslucent(
        LevelRenderer instance, RenderType type, PoseStack poseStack, double camX, double camY, double camZ,
        Matrix4f projection, Operation<Void> original
    ) {
        if (type == RenderType.translucent()) WohlonWaterMurk.beforeTranslucent(Minecraft.getInstance().getMainRenderTarget());
        original.call(instance, type, poseStack, camX, camY, camZ, projection);
    }
}
