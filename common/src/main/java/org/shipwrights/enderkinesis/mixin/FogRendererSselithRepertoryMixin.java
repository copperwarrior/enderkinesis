package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.shipwrights.enderkinesis.sselith.SselithEclipse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sselith fog overrides — colour and distance.
 *
 * <p>The dimension declares {@code effects: minecraft:the_end}, whose
 * {@code getBrightnessDependentFogColor} scales the biome's fog colour by 0.15× and
 * washes the intended yellow into muddy brown. TAIL-inject {@code setupColor} to
 * overwrite {@code fogRed/Green/Blue} back to the intended tint, and TAIL-inject
 * {@code setupFog} to stomp the start/end uniforms past vanilla's {@code farPlaneDistance}
 * clamp.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererSselithRepertoryMixin {

    private static final float SSELITH_FOG_START = 16.0f;
    private static final float SSELITH_FOG_END = 64.0f;

    /** Pre-End-dimming biome fog_color (#FFE066). Alpha is set per-frame from
     *  [SselithRepertory.fogDensityAt]. */
    private static final float SSELITH_FOG_R = 1.00f;
    private static final float SSELITH_FOG_G = 0.88f;
    private static final float SSELITH_FOG_B = 0.40f;

    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    @Inject(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$applySselithFogColor(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount,
        CallbackInfo ci
    ) {
        if (level.dimension() != SselithRepertory.INSTANCE.getLEVEL_KEY()) return;
        // Drive fog toward pure black at full eclipse so distant geometry behind a
        // pitch-black lightmap doesn't get re-tinted by the standard yellow fog blend.
        // Fractional gameTime + partialTick → per-frame smoothness across the ramps.
        float eclipse = SselithEclipse.intensity((double) level.getGameTime() + partialTick);
        float darken = 1.0f - eclipse;
        // Uniform push is in `levelFogColor` (hooked below); writing the static fields here is enough.
        fogRed = SSELITH_FOG_R * darken;
        fogGreen = SSELITH_FOG_G * darken;
        fogBlue = SSELITH_FOG_B * darken;
    }

    /**
     * Replaces the 3-arg {@code setShaderFogColor(r, g, b)} call inside
     * {@code FogRenderer.levelFogColor()} (the per-frame push of the fog colour uniform
     * to the shader) with the 4-arg version using a height-driven alpha.
     *
     * <p>This is the only place in the vanilla render path where the fog-colour uniform
     * is pushed every frame. Vanilla's 3-arg call resets alpha to 1, which clobbered the
     * alpha I'd written in {@code setupColor} a moment earlier — that's why terrain fog
     * wasn't responding to altitude even though the sky already was. Catching the push
     * here ensures the shader sees the correct {@link SselithRepertory#fogDensityAt}
     * alpha when it actually mixes the fog into the block colour.
     */
    @WrapOperation(
        method = "levelFogColor()V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogColor(FFF)V"
        )
    )
    private static void enderkinesis$applySselithFogAlpha(
            float r, float g, float b, Operation<Void> original) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension() == SselithRepertory.INSTANCE.getLEVEL_KEY()) {
            Camera camera = mc.gameRenderer.getMainCamera();
            float alpha = SselithRepertory.INSTANCE.fogDensityAt(camera.getPosition().y);
            RenderSystem.setShaderFogColor(r, g, b, alpha);
        } else {
            // Chain to the next @WrapOperation (or the original
            // 3-arg call if none). The Wohlon biome wrapper sits
            // on this same call site and handles its own check.
            original.call(r, g, b);
        }
    }

    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$applySselithFogRange(
        Camera camera, FogRenderer.FogMode mode,
        float farPlaneDistance, boolean nearby, float partialTick,
        CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (mc.level.dimension() != SselithRepertory.INSTANCE.getLEVEL_KEY()) return;
        RenderSystem.setShaderFogStart(SSELITH_FOG_START);
        RenderSystem.setShaderFogEnd(SSELITH_FOG_END);
        // SKY=SPHERE (radial horizon-ring falloff), TERRAIN=CYLINDER (horizontal-leaning so
        // diagonal sight-lines fog less than radial would).
        if (mode == FogRenderer.FogMode.FOG_SKY) {
            RenderSystem.setShaderFogShape(FogShape.SPHERE);
        } else {
            RenderSystem.setShaderFogShape(FogShape.CYLINDER);
        }
    }

    /**
     * Force {@link FogShape#CYLINDER} for *every* {@code setShaderFogShape} call inside
     * {@code setupFog} when we're in Sselith — but only when the call site is the
     * terrain-mode path; the TAIL inject above then has the final word per FogMode. This
     * redirect catches vanilla's own SPHERE-setting branches (lava fog, powder snow, the
     * early water-fog conditional) so the shape uniform isn't briefly SPHERE between the
     * vanilla call and our TAIL inject for the terrain pass.
     *
     * <p>Outside Sselith we forward the original shape unchanged.
     */
    @Redirect(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogShape(Lcom/mojang/blaze3d/shaders/FogShape;)V"
        )
    )
    private static void enderkinesis$forceCylinderInSselith(FogShape shape) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension() == SselithRepertory.INSTANCE.getLEVEL_KEY()) {
            RenderSystem.setShaderFogShape(FogShape.CYLINDER);
        } else {
            RenderSystem.setShaderFogShape(shape);
        }
    }
}
