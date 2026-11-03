package org.shipwrights.enderkinesis.mixin;

import org.shipwrights.enderkinesis.compat.distanthorizons.DistantHorizonsCompat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.shipwrights.enderkinesis.client.WeheloPocketClient;
import org.shipwrights.enderkinesis.dimension.WeheloPocket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Same three hooks as FogRendererSureibjinMixin: vanilla resets the colour statics on some frames before the upload.
@Mixin(value = FogRenderer.class, priority = DistantHorizonsCompat.FOG_MIXIN_PRIORITY)
public abstract class FogRendererWeheloPocketMixin {

    private static final float POCKET_FOG_GREY = WeheloPocketClient.FOG_GREY;
    private static final float POCKET_FOG_START = WeheloPocketClient.FOG_START;
    private static final float POCKET_FOG_END = WeheloPocketClient.FOG_END;

    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    private static boolean enderkinesis$pocketFogApplies(Camera camera, ClientLevel level) {
        if (level == null || level.dimension() != WeheloPocket.LEVEL_KEY) return false;
        if (camera.getFluidInCamera() != FogType.NONE) return false;
        // Blindness and Darkness fog are near and dark. The pocket fog must not replace them.
        return !(camera.getEntity() instanceof LivingEntity living
            && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS)));
    }

    @Inject(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$applyPocketFogColor(
        Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenWorldAmount, CallbackInfo ci
    ) {
        if (!enderkinesis$pocketFogApplies(camera, level)) return;
        fogRed = POCKET_FOG_GREY;
        fogGreen = POCKET_FOG_GREY;
        fogBlue = POCKET_FOG_GREY;
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0f);
    }

    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$applyPocketFogDistance(
        Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance, boolean shouldBeFoggy, float partialTick, CallbackInfo ci
    ) {
        if (!enderkinesis$pocketFogApplies(camera, Minecraft.getInstance().level)) return;
        RenderSystem.setShaderFogStart(POCKET_FOG_START);
        RenderSystem.setShaderFogEnd(POCKET_FOG_END);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }

    @WrapOperation(
        method = "levelFogColor()V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogColor(FFF)V")
    )
    private static void enderkinesis$pushPocketFogColor(float r, float g, float b, Operation<Void> original) {
        Minecraft mc = Minecraft.getInstance();
        if (enderkinesis$pocketFogApplies(mc.gameRenderer.getMainCamera(), mc.level)) {
            original.call(POCKET_FOG_GREY, POCKET_FOG_GREY, POCKET_FOG_GREY);
        } else {
            original.call(r, g, b);
        }
    }
}
