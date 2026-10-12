package org.shipwrights.enderkinesis.mixin;

import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.light.flat.FlatLightPipeline;
import me.jellysquid.mods.sodium.client.model.light.smooth.SmoothLightPipeline;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.shipwrights.enderkinesis.client.SselithRepertoryLighting;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium and Embeddium mirror of {@link ModelBlockRendererAOSselithMixin} and
 * {@link ModelBlockRendererFlatSselithMixin}: their chunk meshing does not call
 * {@code ModelBlockRenderer}. Gated by {@link EnderkinesisMixinPlugin}.
 */
@Mixin(value = {FlatLightPipeline.class, SmoothLightPipeline.class}, remap = false)
public abstract class SodiumLightPipelineSselithMixin {

    // Method name only: the descriptor has Minecraft types, and its names differ between loaders.
    @Inject(method = "calculate", at = @At("RETURN"), remap = false)
    private void enderkinesis$attenuateSselithSky(
        ModelQuadView quad, BlockPos pos, QuadLightData out,
        Direction cullFace, Direction lightFace, boolean shade,
        CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != SselithRepertory.INSTANCE.getLEVEL_KEY()) {
            return;
        }
        for (int i = 0; i < out.lm.length; i++) {
            out.lm[i] = SselithRepertoryLighting.attenuateSkyLight(out.lm[i], lightFace);
        }
    }
}
