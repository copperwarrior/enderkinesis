package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.shipwrights.enderkinesis.dimension.Sureibjin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sureibjin atmospheric tint — above-water (#1f272f) and submerged (#020202) paths stomped
 * to fixed colours. Vanilla's underwater path doesn't honour the biome's
 * {@code water_fog_color} JSON (reads white in-world even when set to #020202), so the
 * override happens here.
 *
 * <p>Three injections: {@code setupColor}/{@code setupFog} TAIL set the statics, and a
 * {@code levelFogColor} WrapOperation on {@code setShaderFogColor} re-pushes the right
 * colour just before GPU upload — vanilla's call path resets the statics on some frames
 * before they reach the shader.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererSureibjinMixin {

    // Above-water fog tint: #1f272f.
    private static final float SUREIBJIN_FOG_R = 0.122f;
    private static final float SUREIBJIN_FOG_G = 0.153f;
    private static final float SUREIBJIN_FOG_B = 0.184f;

    // Underwater fog tint: #020202. Near-black.
    private static final float SUREIBJIN_WATER_FOG_R = 0.008f;
    private static final float SUREIBJIN_WATER_FOG_G = 0.008f;
    private static final float SUREIBJIN_WATER_FOG_B = 0.008f;

    private static final float SUREIBJIN_FOG_START_FRAC = 0.30f;
    private static final float SUREIBJIN_FOG_END_FRAC   = 0.95f;

    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    @Inject(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$applySureibjinFogColor(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount,
        CallbackInfo ci
    ) {
        if (level.dimension() != Sureibjin.INSTANCE.getLEVEL_KEY()) return;
        FogType fluid = camera.getFluidInCamera();
        if (fluid == FogType.WATER) {
            fogRed = SUREIBJIN_WATER_FOG_R;
            fogGreen = SUREIBJIN_WATER_FOG_G;
            fogBlue = SUREIBJIN_WATER_FOG_B;
        } else if (fluid == FogType.NONE) {
            fogRed = SUREIBJIN_FOG_R;
            fogGreen = SUREIBJIN_FOG_G;
            fogBlue = SUREIBJIN_FOG_B;
        }
    }

    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$applySureibjinFogDistance(
        Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance,
        boolean shouldBeFoggy, float partialTick,
        CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        if (level.dimension() != Sureibjin.INSTANCE.getLEVEL_KEY()) return;
        if (camera.getFluidInCamera() != FogType.NONE) return;
        float start = farPlaneDistance * SUREIBJIN_FOG_START_FRAC;
        float end = farPlaneDistance * SUREIBJIN_FOG_END_FRAC;
        RenderSystem.setShaderFogStart(start);
        RenderSystem.setShaderFogEnd(end);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }

    @WrapOperation(
        method = "levelFogColor()V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogColor(FFF)V"
        )
    )
    private static void enderkinesis$pushSureibjinFogColor(
        float r, float g, float b,
        Operation<Void> original
    ) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != Sureibjin.INSTANCE.getLEVEL_KEY()) {
            original.call(r, g, b);
            return;
        }
        FogType fluid = mc.gameRenderer.getMainCamera().getFluidInCamera();
        if (fluid == FogType.WATER) {
            original.call(SUREIBJIN_WATER_FOG_R, SUREIBJIN_WATER_FOG_G, SUREIBJIN_WATER_FOG_B);
        } else if (fluid == FogType.NONE) {
            original.call(SUREIBJIN_FOG_R, SUREIBJIN_FOG_G, SUREIBJIN_FOG_B);
        } else {
            original.call(r, g, b);
        }
    }
}
