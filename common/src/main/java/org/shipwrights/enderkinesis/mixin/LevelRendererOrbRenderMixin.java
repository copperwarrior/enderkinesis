package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.OrbOfPotentialRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the Orb of Potential renderer from the world-render pass.
 *
 *  Injecting at the TAIL of {@code renderLevel} keeps the renderer
 *  invocation lined up with the same convention the bubble pass and
 *  the Ygann-abyss post-world pass already use — after all opaque,
 *  entity, and translucent geometry has rendered, the depth buffer
 *  is fully populated, and the orb's own LEQUAL depth test against
 *  it produces correct front-vs-behind occlusion against ordinary
 *  geometry. The orb may visually win over translucent blocks that
 *  it should sit behind — acceptable for the first cut; a stricter
 *  injection point (between entities and translucent) is a follow-up.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererOrbRenderMixin {

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void enderkinesis$renderOrbs(
        PoseStack poseStack, float partialTick, long nanoTime, boolean renderBlockOutline,
        Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        OrbOfPotentialRenderer.INSTANCE.render(poseStack, partialTick, camera, projectionMatrix);
    }
}
