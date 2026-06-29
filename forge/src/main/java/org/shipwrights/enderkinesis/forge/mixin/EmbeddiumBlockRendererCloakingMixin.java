package org.shipwrights.enderkinesis.forge.mixin;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Embeddium-side counterpart to {@code BlockRenderDispatcherCloakingMixin}. Embeddium
 * is the Forge port of Sodium and keeps the same class names — Sodium's
 * {@code me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer}
 * exists verbatim in Embeddium jars. Same mixin code as Fabric's Sodium variant.
 *
 * <p>Gated by the Forge mixin plugin on Embeddium presence (the JSON-resource probe).
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class EmbeddiumBlockRendererCloakingMixin {

    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, remap = false)
    private void enderkinesis$cancelIfConcealed(
        BlockRenderContext ctx, ChunkBuildBuffers buffers, CallbackInfo ci
    ) {
        if (CloakingMixinSupport.isPositionConcealed(ctx.pos())) {
            ci.cancel();
        }
    }
}
