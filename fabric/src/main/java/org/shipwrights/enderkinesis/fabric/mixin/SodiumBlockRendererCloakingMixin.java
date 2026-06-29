package org.shipwrights.enderkinesis.fabric.mixin;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium-side counterpart to {@code BlockRenderDispatcherCloakingMixin}. Sodium
 * bypasses vanilla's {@code BlockRenderDispatcher.renderBatched} entirely with its own
 * chunk render pipeline, so the vanilla mixin's HEAD cancel never fires for Sodium
 * users. This mixin intercepts Sodium's equivalent path: {@code BlockRenderer.renderModel},
 * which is called once per block during chunk compilation.
 *
 * <p>{@code BlockRenderContext.pos()} returns the *shipyard* coords for ship chunks
 * (VS2 chunks live at shipyard coords in the level), so it feeds directly into
 * {@link CloakingMixinSupport#isPositionConcealed} the same way the vanilla mixin
 * does.
 *
 * <p>Gated by the Fabric mixin config — only loaded when Sodium is on the classpath,
 * which the mod loader handles automatically: the class refs above would fail to load
 * without Sodium, so we put this mixin in the Sodium-conditional Fabric config.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class SodiumBlockRendererCloakingMixin {

    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, remap = false)
    private void enderkinesis$cancelIfConcealed(
        BlockRenderContext ctx, ChunkBuildBuffers buffers, CallbackInfo ci
    ) {
        if (CloakingMixinSupport.isPositionConcealed(ctx.pos())) {
            ci.cancel();
        }
    }
}
