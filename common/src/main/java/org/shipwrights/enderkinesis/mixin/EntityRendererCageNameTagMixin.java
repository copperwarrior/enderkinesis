package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.shipwrights.enderkinesis.client.GamylanCageRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * When rendering the frozen ghost inside a Ga'Mylan cage, swap vanilla's full
 * 3D camera billboard for a yaw-only rotation around the mob's local +Y axis
 * aimed at the camera. The mob's tilt still comes through on the name tag; only
 * the yaw compensates for the viewer position. Bypasses cleanly outside a cage
 * ghost render — vanilla's camera orientation is called through the Operation.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCageNameTagMixin {

    @WrapOperation(
        method = "renderNameTag",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;cameraOrientation()Lorg/joml/Quaternionf;"
        )
    )
    private Quaternionf enderkinesis$replaceCameraOrient(
        EntityRenderDispatcher dispatcher, Operation<Quaternionf> original,
        Entity entity, Component name, PoseStack pose, MultiBufferSource buffers, int light
    ) {
        if (!GamylanCageRenderer.getPendingGhostRender()) {
            return original.call(dispatcher);
        }
        return GamylanCageRenderer.computeLocalYawTowardCamera(pose);
    }
}
