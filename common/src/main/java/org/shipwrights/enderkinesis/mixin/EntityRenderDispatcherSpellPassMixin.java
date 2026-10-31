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

/** Hooks the dispatcher, not each renderer, so spell deaths and crystals work on all entities that render. */
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
        if (!EntityPasses.needsPass(entity)) {
            original.call(renderer, entity, yaw, partialTick, pose, buffers, light);
            EntityPasses.after(entity, partialTick, pose, buffers, light);
            return;
        }
        EntityPasses.render(entity, partialTick, pose, buffers, light, source -> original.call(renderer, entity, yaw, partialTick, pose, source, light));
    }
}
