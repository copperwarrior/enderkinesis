package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.client.YgannAbyssWatchingEyes;
import org.shipwrights.enderkinesis.client.YgannAbyssWrithingSea;
import org.shipwrights.enderkinesis.dimension.YgannAbyss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link YgannAbyssWatchingEyes} stamp a glimpsed pair of glowing enderman eyes onto the
 * sky after the vanilla End skybox has finished drawing.
 *
 * <p>Initially we injected at {@code TAIL} of {@link LevelRenderer#renderSky}. That target
 * <em>is</em> after the End sky cube draws, but it's also after vanilla's
 * {@code RenderSystem.depthMask(true); RenderSystem.disableBlend();} cleanup at the end of
 * {@code renderEndSky}, and depending on whose mixins / shader mods are also touching
 * {@code renderSky} the TAIL slot can end up being "after some other sky-replacement has
 * had its turn" — not a stable place to draw on top of the End sky specifically.
 *
 * <p>This shifts the target to {@code INVOKE} of {@code renderEndSky}, {@code Shift.AFTER}: we
 * draw immediately after the End sky cube has returned, inside the same conditional branch,
 * before any later code (or third-party mixin) gets a turn at the sky pass. The render-state
 * cleanup at the end of {@code renderEndSky} has run, so we set up our own state from a
 * known baseline and restore it the same way the cube did.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererYgannAbyssEyesMixin {

    @Shadow @Nullable private ClientLevel level;

    @Inject(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEndSky(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            shift = At.Shift.AFTER
        )
    )
    private void enderkinesis$drawYgannAbyssEyesAfterEndSky(
        com.mojang.blaze3d.vertex.PoseStack poseStack,
        org.joml.Matrix4f projectionMatrix,
        float partialTick,
        net.minecraft.client.Camera camera,
        boolean isFoggy,
        Runnable setupFog,
        CallbackInfo ci
    ) {
        ClientLevel lvl = this.level;
        if (lvl == null) return;
        if (!lvl.dimension().equals(YgannAbyss.INSTANCE.getLEVEL_KEY())) return;
        // The writhing sea is a permanent feature of the abyss — drawn every frame, below
        // the horizon. The watching eyes are a rare apparition gated on their own state.
        // Sea first so the upper-sky eyes layer cleanly on top of the lower-hemisphere sheet.
        YgannAbyssWrithingSea.INSTANCE.renderSky(poseStack, partialTick);
        if (!YgannAbyssWatchingEyes.INSTANCE.isShowing(lvl)) return;
        YgannAbyssWatchingEyes.INSTANCE.renderSky(poseStack, partialTick);
    }
}
