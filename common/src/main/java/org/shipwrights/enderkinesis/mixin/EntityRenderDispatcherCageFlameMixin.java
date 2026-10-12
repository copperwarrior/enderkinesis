package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.shipwrights.enderkinesis.client.GamylanCageRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sibling of {@link EntityRendererCageNameTagMixin} for the fire overlay. Vanilla's
 * {@code renderFlame} yaw-billboards its quads via {@code Axis.YP.rotationDegrees(-camera.getYRot())};
 * we replace that with a yaw-only rotation around the mob's local +Y axis so the
 * fire tilts with the caged mob but still faces the camera around that tilted axis.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherCageFlameMixin {

    @WrapOperation(
        method = "renderFlame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/math/Axis;rotationDegrees(F)Lorg/joml/Quaternionf;"
        )
    )
    private Quaternionf enderkinesis$replaceFlameYaw(
        Axis axis, float degrees, Operation<Quaternionf> original,
        PoseStack pose, MultiBufferSource buffers, Entity entity
    ) {
        if (!GamylanCageRenderer.getPendingGhostRender()) {
            return original.call(axis, degrees);
        }
        return GamylanCageRenderer.computeLocalYawTowardCamera(pose);
    }
}
