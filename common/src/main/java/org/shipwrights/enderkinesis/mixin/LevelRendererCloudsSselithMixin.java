package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses cloud rendering inside {@link SselithRepertory}.
 *
 * <p>Sselith uses {@code minecraft:overworld} dimension effects (so the lightmap follows
 * the natural day curve and sky + block light combine additively), but overworld effects
 * also place clouds at {@code y = 128}. That puts a flat cloud layer right inside the
 * dimension's column-stack world, which reads as random sheets of texture intersecting
 * the bookshelves. Cancelling the cloud render at HEAD when the player is in Sselith
 * keeps the overworld lightmap behaviour without dragging the cloud layer along.
 *
 * <p>Cloud rendering is per-frame regardless of mode (cloud option only changes the cloud
 * geometry); a HEAD-cancel covers all paths.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCloudsSselithMixin {

    @Shadow @Nullable private ClientLevel level;

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$skipCloudsInSselith(
        PoseStack poseStack, Matrix4f projectionMatrix,
        float partialTick, double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        ClientLevel lvl = this.level;
        if (lvl != null && lvl.dimension() == SselithRepertory.INSTANCE.getLEVEL_KEY()) {
            ci.cancel();
        }
    }
}
