package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.MothClothOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires the Moth Cloth glint overlay pass at the pre-{@code renderDebug}
 * slot — same injection point as {@link LevelRendererGamylanCageMixin},
 * chosen because at that bytecode offset the pose stack has been popped
 * back to the neutral camera-rotation state (no camera translation baked
 * in) and the GPU {@code ModelViewMat} uniform is at the same neutral
 * state as at the translucent pass — so a straight
 * {@code pose.translate(pos - cam)} produces vertices at correct world
 * positions rather than flying with the camera.
 *
 * <p>Runs uniformly for world blocks and ship-mounted blocks — the overlay
 * renderer applies VS2's per-ship transform for any position that
 * resolves to a {@code ClientShip}.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMothClothOverlayMixin {

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V"
        )
    )
    private void enderkinesis$renderMothClothOverlay(
        PoseStack poseStack,
        float partialTick,
        long finishTimeNano,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f projection,
        CallbackInfo ci
    ) {
        net.minecraft.world.phys.Vec3 pos = camera.getPosition();
        MothClothOverlayRenderer.render(poseStack, pos.x, pos.y, pos.z);
    }
}
