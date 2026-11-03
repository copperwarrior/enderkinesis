package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.shipwrights.enderkinesis.compat.distanthorizons.DistantHorizonsCompat;
import org.shipwrights.enderkinesis.compat.distanthorizons.TerrainFogSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FogRenderer.class, priority = DistantHorizonsCompat.FOG_SNAPSHOT_MIXIN_PRIORITY)
public abstract class FogRendererTerrainFogSnapshotMixin {

    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$snapshotTerrainFog(
        Camera camera, FogRenderer.FogMode mode, float farPlaneDistance, boolean nearby, float partialTick, CallbackInfo ci
    ) {
        if (mode == FogRenderer.FogMode.FOG_TERRAIN) {
            TerrainFogSnapshot.record(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
        }
    }
}
