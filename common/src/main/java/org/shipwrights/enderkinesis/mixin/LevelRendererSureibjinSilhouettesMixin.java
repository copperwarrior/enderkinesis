package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.SureibjinSilhouettes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook that draws Sureibjin's cardinal player silhouettes in
 * {@code LevelRenderer.renderLevel} at the "translucent" string-constant
 * transition. At this point the opaque, cutout and entity passes have
 * finished, so the silhouettes depth-test correctly against the world
 * (occluded by dunes and tendrils, visible across open beach) while still
 * drawing before the translucent pass. Same injection spot as
 * {@code LevelRendererTransportationGhostsMixin}.
 *
 * <p>Dimension gate lives in {@link SureibjinSilhouettes#render} so the
 * mixin stays a thin pass-through.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSureibjinSilhouettesMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "CONSTANT",
            args = "stringValue=translucent"
        )
    )
    private void enderkinesis$renderSureibjinSilhouettes(
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
        SureibjinSilhouettes.render(poseStack, camera, partialTick);
    }
}
