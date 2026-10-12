package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.shipwrights.enderkinesis.dimension.WohlonBiomePresence;
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wohlon-biome ambient-light floor — synthetic block-light value 5 in Wohlon biome
 * cells, the Overworld-side approximation of the Wohlon dimension's
 * {@code ambient_light: 0.1}.
 *
 * <p>Inject at {@code getLightValue} (the external-caller path), NOT at
 * {@code getStoredLevel}: the BFS propagation walks {@code getStoredLevel} directly,
 * and injecting there would leak the floor across the biome edge through propagation.
 *
 * <p>Level 5 is the closest integer Overworld-ramp value ({@code g(5) ≈ 0.111}) to
 * the Nether's ramp at {@code i=0} ({@code 0.1}).
 */
@Mixin(LightEngine.class)
public abstract class LayerLightEngineWohlonAmbientMixin {

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    /**
     * Approximate raw-light equivalent of the Nether's {@code ambient_light: 0.1}
     * brightness floor — Overworld ramp {@code g(5) ≈ 0.111}, Nether ramp at {@code i=0}
     * is {@code 0.1}.
     */
    private static final int WOHLON_AMBIENT_BLOCKLIGHT = 5;

    @Inject(method = "getLightValue(Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$wohlonAmbientFloor(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        // Block light layer only — sky light already covered by `has_skylight`.
        if (!(((Object) this) instanceof BlockLightEngine)) return;

        // First: one field read ends the hook in each world with no Wohlon cell in range.
        if (!WohlonBiomePresence.nearby) return;

        int stored = cir.getReturnValueI();
        if (stored >= WOHLON_AMBIENT_BLOCKLIGHT) return;

        // Client-only, so spawn and growth checks on the server see the real light. A class-name
        // compare fails in Fabric production (intermediary names).
        if (!(chunkSource.getLevel() instanceof Level level) || !level.isClientSide()) return;

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        BlockGetter getter = chunkSource.getChunkForLighting(x >> 4, z >> 4);
        if (!(getter instanceof ChunkAccess access)) return;

        // ----- 3×3 biome-cell sample around the centre cell ----------------------
        // For each of the 9 cells in the XZ disc around (qx, qy, qz) at the
        // centre's qy, count how many are Wohlon. Boost is scaled by the
        // fraction — a position deep inside Wohlon sees all 9 cells as Wohlon
        // (boost = 5), a position one cell outside the biome line sees 3/9
        // (boost ≈ 2), straddling the edge sees somewhere in between. Result
        // is a smooth gradient across the boundary that hides the 4 × 4 × 4
        // cell grid in dim areas.
        //
        // Sampling is XZ-only at the centre's Y — Y-edges (caves vs surface)
        // would benefit from a 3D blend, but the per-query cost would triple
        // and Y-edges are rare enough in practice not to be worth it.
        int centreQx = x >> 2;
        int centreQy = y >> 2;
        int centreQz = z >> 2;
        int centreCx = x >> 4;
        int centreCz = z >> 4;
        int wohlonCount = 0;
        int totalCount = 0;
        for (int dqx = -1; dqx <= 1; dqx++) {
            for (int dqz = -1; dqz <= 1; dqz++) {
                int sampleQx = centreQx + dqx;
                int sampleQz = centreQz + dqz;
                int sampleCx = sampleQx >> 2;   // 4 quart cells per chunk
                int sampleCz = sampleQz >> 2;
                ChunkAccess sampleAccess;
                if (sampleCx == centreCx && sampleCz == centreCz) {
                    sampleAccess = access;
                } else {
                    BlockGetter sg = chunkSource.getChunkForLighting(sampleCx, sampleCz);
                    if (!(sg instanceof ChunkAccess)) {
                        // Unloaded neighbour chunk → treat as non-Wohlon.
                        // Lets the gradient taper smoothly into view-distance
                        // edges instead of clamping artificially high.
                        totalCount++;
                        continue;
                    }
                    sampleAccess = (ChunkAccess) sg;
                }
                Holder<Biome> sample = sampleAccess.getNoiseBiome(sampleQx, centreQy, sampleQz);
                totalCount++;
                if (sample.is(Wohlonnogondonia.BIOME_KEY)) wohlonCount++;
            }
        }
        if (wohlonCount == 0) return;   // No Wohlon nearby — fall through to vanilla.

        int boost = Math.round((float) wohlonCount / (float) totalCount * WOHLON_AMBIENT_BLOCKLIGHT);
        if (boost > stored) cir.setReturnValue(boost);
    }
}
