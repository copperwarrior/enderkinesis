package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.SelvesClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Any vanilla mob can be a player body, so the body renderer is picked per entity, not per type.
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherSelvesMixin {

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void enderkinesis$selvesBodyRenderers(ResourceManager manager, CallbackInfo ci, @Local EntityRendererProvider.Context context) {
        SelvesClient.bakeBodyRenderers(context);
    }

    @ModifyReturnValue(method = "getRenderer", at = @At("RETURN"))
    private EntityRenderer<?> enderkinesis$selvesBodyRenderer(EntityRenderer<?> original, Entity entity) {
        EntityRenderer<?> body = SelvesClient.bodyRenderer(entity);
        return body != null ? body : original;
    }
}
