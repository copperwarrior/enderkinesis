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
 * Custom lightmap for {@link org.shipwrights.enderkinesis.dimension.SselithRepertory} that
 * gives skylight a yellow tint to match the dimension's sun while leaving block-light
 * cells warm/torch-coloured.
 *
 * <p>The lightmap is a 16×16 RGBA texture indexed by {@code (block_light, sky_light)}. The
 * shader samples a single cell and multiplies it into the rendered colour, so the
 * structure of the texture is what differentiates "lit by sky" vs "lit by torch":
 *
 *  - Pure sky cells (block_light=0, sky_light=15) → yellow.
 *  - Pure block cells (block_light=15, sky_light=0) → warm vanilla torch colour.
 *  - Mixed cells → additive blend.
 *
 * <p>This is the key fix over the earlier {@code max(block, sky)} formula: that version
 * applied a yellow tint to *every* cell including pure-torch ones, so torches in covered
 * areas read as yellow instead of warm. With additive separation, a torch-lit shadow face
 * keeps its identifiable orange/red colour.
 *
 * <p>FULL_BRIGHT slot (15, 15) is hard-pinned to pure white because GUI text, HUD
 * elements, inventory icons, and held-item overlays sample it for their lightmap UV;
 * tinting that slot turns every screen-space label coloured. World blocks effectively
 * never sample (15, 15) — that would need full torch *and* full sun on the same voxel —
 * so the seam is invisible in-world.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureSselithRepertoryMixin {

    @Shadow private boolean updateLightTexture;
    @Shadow @Final private NativeImage lightPixels;
    @Shadow @Final private DynamicTexture lightTexture;

    @Inject(method = "updateLightTexture", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$sselithLightmap(float partialTick, CallbackInfo ci) {
        if (!this.updateLightTexture) return;
        if (!SselithRepertoryLighting.INSTANCE.isInRepertory()) return;
        this.updateLightTexture = false;
        SselithRepertoryLighting.INSTANCE.writeLightmap(this.lightPixels);
        this.lightTexture.upload();
        ci.cancel();
    }
}
