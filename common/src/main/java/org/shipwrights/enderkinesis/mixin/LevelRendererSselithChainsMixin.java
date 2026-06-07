package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.SselithChainRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook that lets {@link SselithChainRenderer} draw its beacon-beam-style
 * chain strips inside {@code LevelRenderer.renderLevel} after the opaque
 * block layers have been drawn. The string-constant injection point fires
 * just before the profiler transitions into the "translucent" phase — at
 * that moment the depth buffer is fully populated by SOLID, CUTOUT_MIPPED,
 * CUTOUT, and the entity pass, so the chain quads depth-test correctly
 * (occluded by walls, visible through air), and the subsequent translucent
 * blocks are still drawn after them and blend correctly on top.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSselithChainsMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "CONSTANT",
            args = "stringValue=translucent"
        )
    )
    private void enderkinesis$renderSselithChains(
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
        SselithChainRenderer.INSTANCE.render(poseStack, projection, camera);
    }
}
