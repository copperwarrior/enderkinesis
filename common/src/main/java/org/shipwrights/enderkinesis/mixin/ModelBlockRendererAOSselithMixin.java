package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.client.SselithRepertoryLighting;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

/**
 * Attenuates the sky-light component of the per-vertex packed lightmap UVs for blocks
 * rendered with smooth (ambient-occlusion) lighting in
 * {@link org.shipwrights.enderkinesis.dimension.SselithRepertory}. Block-light is left
 * untouched — torches stay full brightness on shadow faces.
 *
 * <p>{@code AmbientOcclusionFace.calculate(...)} populates the inner class's
 * {@code lightmap[4]} field with the per-corner packed lightmap UVs sampled from the
 * face's neighbour blocks. We @Inject at TAIL with the {@code Direction} already in the
 * method signature and rewrite each entry through
 * {@link SselithRepertoryLighting#attenuateSkyLight(int, Direction)}, which scales only
 * the sky-light nibble.
 *
 * <p>Companion: {@link ModelBlockRendererFlatSselithMixin} handles the flat-lighting
 * code path.
 */
@Mixin(targets = "net.minecraft.client.renderer.block.ModelBlockRenderer$AmbientOcclusionFace")
public abstract class ModelBlockRendererAOSselithMixin {

    @Shadow @Final int[] lightmap;

    @Inject(method = "calculate", at = @At("TAIL"))
    private void enderkinesis$attenuateSselithSkyAO(
        BlockAndTintGetter level, BlockState state, BlockPos pos, Direction direction,
        float[] brightness, BitSet sides, boolean useAo,
        CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != SselithRepertory.INSTANCE.getLEVEL_KEY()) {
            return;
        }
        for (int i = 0; i < this.lightmap.length; i++) {
            this.lightmap[i] = SselithRepertoryLighting.attenuateSkyLight(this.lightmap[i], direction);
        }
    }
}
