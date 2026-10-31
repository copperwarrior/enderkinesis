package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.EntityPasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Every entity's drawing goes through {@link EntityPasses}, whatever its renderer, so a spell's
 *  death and Crystallized's crystals work on anything that renders. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherSpellPassMixin {

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    )
    private void enderkinesis$spellPass(
        EntityRenderer<Entity> renderer, Entity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
        Operation<Void> original
    ) {
        EntityPasses.render(entity, partialTick, pose, buffers, light, source -> original.call(renderer, entity, yaw, partialTick, pose, source, light));
    }
}
