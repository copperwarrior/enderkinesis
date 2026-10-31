package org.shipwrights.enderkinesis.mixin;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import me.jellysquid.mods.sodium.client.world.biome.BiomeSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import org.shipwrights.enderkinesis.dimension.WohlonBiomePresence;
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium-path mirror of {@link LayerLightEngineWohlonAmbientMixin}.
 *
 * <p>Sodium's chunk-meshing pipeline reads block-light straight out of cached
 * {@code DataLayer} arrays in {@code WorldSlice.getBrightness} — it never calls
 * {@code LightEngine.getLightValue(BlockPos)}, so the vanilla-side ambient
 * floor never shows up under Sodium. This mixin applies the same biome-aware
 * boost at Sodium's read site instead.
 *
 * <p>Math matches the vanilla mixin: 3×3 XZ biome sample around the centre
 * quart-cell, scaled boost = round((wohlon cells / 9) × 5).
 *
 * <p>Gated by {@link EnderkinesisMixinPlugin#shouldApplyMixin} on Sodium's
 * presence — when Sodium isn't installed the {@code WorldSlice} target class
 * doesn't exist and the mixin would fail PREPARE.
 */
@Mixin(value = WorldSlice.class, remap = false)
public abstract class WorldSliceWohlonAmbientMixin {

    // The mesh build runs on worker threads, so read the slice snapshot, never the ClientLevel.
    @Shadow @Final
    private BiomeSlice biomeSlice;

    private static final int WOHLON_AMBIENT_BLOCKLIGHT = 5;

    // BiomeSlice holds 3×3×3 sections as 12 quart cells on each axis.
    private static final int SLICE_QUARTS = 12;

    @Inject(
        method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"),
        cancellable = true,
        // 0: the name mapping of this vanilla override is not verified on Embeddium; no match must not stop the launch.
        require = 0,
        remap = true
    )
    private void enderkinesis$wohlonAmbientFloor(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (layer != LightLayer.BLOCK) return;
        if (!WohlonBiomePresence.nearby) return;
        int stored = cir.getReturnValueI();
        if (stored >= WOHLON_AMBIENT_BLOCKLIGHT) return;

        BiomeSliceAccessor slice = (BiomeSliceAccessor) biomeSlice;
        Holder<Biome>[] biomes = slice.enderkinesis$getBiomes();
        int centreQx = (pos.getX() - slice.enderkinesis$getWorldX()) >> 2;
        int qy = (pos.getY() - slice.enderkinesis$getWorldY()) >> 2;
        int centreQz = (pos.getZ() - slice.enderkinesis$getWorldZ()) >> 2;
        if (qy < 0 || qy >= SLICE_QUARTS) return;
        int wohlonCount = 0;
        for (int dqx = -1; dqx <= 1; dqx++) {
            int qx = centreQx + dqx;
            if (qx < 0 || qx >= SLICE_QUARTS) continue;
            for (int dqz = -1; dqz <= 1; dqz++) {
                int qz = centreQz + dqz;
                if (qz < 0 || qz >= SLICE_QUARTS) continue;
                Holder<Biome> sample = biomes[(qx * SLICE_QUARTS + qy) * SLICE_QUARTS + qz];
                if (sample != null && sample.is(Wohlonnogondonia.BIOME_KEY)) wohlonCount++;
            }
        }
        if (wohlonCount == 0) return;
        // Cells outside the slice count as non-Wohlon, so the divisor stays 9 like the vanilla mixin.
        int boost = Math.round((float) wohlonCount / 9f * WOHLON_AMBIENT_BLOCKLIGHT);
        if (boost > stored) cir.setReturnValue(boost);
    }
}
