package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.remnant.IrreverantRemnantClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Sselith Remnant's cubes at the pre-{@code renderDebug}
 * slot — the same point {@link LevelRendererGamylanCageMixin} uses, chosen
 * because the pose stack is back to the neutral post-pop state there (so
 * world geometry draws at {@code worldPos − cameraPos}) and it sits after
 * water, particles, clouds, and weather, while still depth-testing against
 * solid terrain.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRemnantMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V"
        )
    )
    private void enderkinesis$renderRemnant(
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
        net.minecraft.world.phys.Vec3 pos = camera.getPosition();
        IrreverantRemnantClient.render(poseStack, pos.x, pos.y, pos.z, partialTick);
    }
}
