package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.shipwrights.enderkinesis.client.SselithRepertoryLighting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom 16×16 lightmap for {@link org.shipwrights.enderkinesis.dimension.SselithRepertory}
 * — sky cells tint yellow (match the dim's sun), block cells stay warm torch, mixed cells
 * blend additively (so a torch in a covered area stays orange, not yellow).
 *
 * <p>FULL_BRIGHT (15, 15) is hard-pinned to white — GUI text, HUD, inventory icons, and
 * held-item overlays sample this slot for their lightmap UV; tinting it colours every
 * screen-space label. World blocks never reach (15, 15), so the seam is invisible.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureSselithRepertoryMixin {

    @Shadow private boolean updateLightTexture;
    @Shadow @Final private NativeImage lightPixels;
    @Shadow @Final private DynamicTexture lightTexture;

    @Inject(method = "updateLightTexture", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$sselithLightmap(float partialTick, CallbackInfo ci) {
        if (!SselithRepertoryLighting.INSTANCE.isInRepertory()) return;
        // During an eclipse, force per-frame recompute so the ramp fades smoothly
        // instead of stepping at the vanilla 20Hz `updateLightTexture` flag rate.
        // Outside eclipse, fall back to vanilla's once-per-tick gate.
        boolean force = SselithRepertoryLighting.INSTANCE.currentEclipseIntensity(partialTick) > 0f;
        if (!this.updateLightTexture && !force) return;
        this.updateLightTexture = false;
        SselithRepertoryLighting.INSTANCE.writeLightmap(this.lightPixels, partialTick);
        this.lightTexture.upload();
        ci.cancel();
    }
}
