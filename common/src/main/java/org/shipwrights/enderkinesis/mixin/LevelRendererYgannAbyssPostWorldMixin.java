package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.IrisCompat;
import org.shipwrights.enderkinesis.client.YgannAbyssWatchingEyes;
import org.shipwrights.enderkinesis.client.YgannAbyssWrithingSea;
import org.shipwrights.enderkinesis.dimension.YgannAbyss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shader-pack render path for the Ygann's Abyss sky geometry. The default route
 * ({@link LevelRendererYgannAbyssEyesMixin}) injects mid-{@code renderSky}, which Iris/Oculus
 * routes through pack-dependent {@code gbuffers_sky*} programs — output is inconsistent
 * across packs. Injecting at {@code TAIL} of {@code renderLevel} (after the deferred
 * composite has finished) draws straight on top, giving consistent output at the cost of
 * any shader post-processing.
 *
 * <p>At TAIL, {@code poseStack.last().pose()} still holds view rotation (vanilla hasn't
 * popped XP·YP yet), {@code modelViewMatrix} is identity, and {@code projectionMatrix} is
 * the world perspective — the contract {@link YgannAbyssWrithingSea#renderSky} expects.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererYgannAbyssPostWorldMixin {

    @Shadow @Nullable private ClientLevel level;

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void enderkinesis$drawYgannAbyssPostComposite(
        PoseStack poseStack,
        float partialTick,
        long startNanoTime,
        boolean renderBlockOutline,
        Camera camera,
        GameRenderer gameRenderer,
        LightTexture lightTexture,
        Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        if (!IrisCompat.INSTANCE.isShaderPackInUse()) return;
        ClientLevel lvl = this.level;
        if (lvl == null) return;
        if (!lvl.dimension().equals(YgannAbyss.INSTANCE.getLEVEL_KEY())) return;

        YgannAbyssWrithingSea.INSTANCE.renderSky(poseStack, partialTick);
        if (YgannAbyssWatchingEyes.INSTANCE.isShowing(lvl)) {
            YgannAbyssWatchingEyes.INSTANCE.renderSky(poseStack, partialTick);
        }
    }
}
