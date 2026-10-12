package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.GamylanCageRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws every active Ga'Mylan crystal cage from the
 * {@link GamylanCageRenderer} pure-function renderer just before the
 * vanilla {@code renderDebug} call at the end of {@code renderLevel}.
 *
 * <p>Choice of injection point: renderLevel wraps the clouds + weather
 * passes with a {@code RenderSystem.getModelViewStack().pushPose()} /
 * {@code mulPoseMatrix(poseStack.last().pose())} / {@code popPose()}
 * bracket so those passes get the world-view transform baked into the
 * GPU {@code ModelViewMat} uniform. Injecting inside that bracket
 * (e.g. at the "weather" profiler transition) means our vertex shader
 * multiplies the world transform in twice — everything flies. The
 * pre-renderDebug slot sits after the popPose so we're back to the
 * same neutral {@code ModelViewMat} state as at "translucent", but
 * after water, particles, clouds, and weather have all drawn, so the
 * crystal reads on top of them while still depth-testing against
 * solid terrain.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererGamylanCageMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V"
        )
    )
    private void enderkinesis$renderGamylanCages(
        PoseStack poseStack,
        float partialTick,
        long finishNanoTime,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f projection,
        CallbackInfo ci
    ) {
        GamylanCageRenderer.INSTANCE.render(poseStack, camera, partialTick);
        // The captured ghost entity emits into vanilla entity render
        // types (armor cutout, held-item BakedModels, glint, name-tag
        // billboard). Those queue in the shared bufferSource; vanilla
        // renderDebug at the next bytecode swaps render types and
        // triggers fallback flushes with WHATEVER camera / matrix state
        // renderDebug has set — held items shift with the view because
        // billboarded / camera-relative pieces get flushed against the
        // wrong reference. Explicit endBatch() here forces the ghost's
        // queued vanilla types to flush now, at our known-good
        // post-popPose state, so the items stay glued to the ghost.
        MultiBufferSource.BufferSource source = Minecraft.getInstance().renderBuffers().bufferSource();
        source.endBatch();
    }
}
