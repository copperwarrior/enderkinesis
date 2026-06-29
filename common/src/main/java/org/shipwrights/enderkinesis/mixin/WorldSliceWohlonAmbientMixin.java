package org.shipwrights.enderkinesis.mixin;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
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

    @Shadow @Final
    private ClientLevel world;

    private static final int WOHLON_AMBIENT_BLOCKLIGHT = 5;

    @Inject(
        method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"),
        cancellable = true,
        remap = true
    )
    private void enderkinesis$wohlonAmbientFloor(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (layer != LightLayer.BLOCK) return;
        int stored = cir.getReturnValueI();
        if (stored >= WOHLON_AMBIENT_BLOCKLIGHT) return;

        int centreQx = pos.getX() >> 2;
        int centreQy = pos.getY() >> 2;
        int centreQz = pos.getZ() >> 2;
        int wohlonCount = 0;
        int totalCount = 0;
        for (int dqx = -1; dqx <= 1; dqx++) {
            for (int dqz = -1; dqz <= 1; dqz++) {
                Holder<Biome> sample = world.getNoiseBiome(centreQx + dqx, centreQy, centreQz + dqz);
                totalCount++;
                if (sample.is(Wohlonnogondonia.BIOME_KEY)) wohlonCount++;
            }
        }
        if (wohlonCount == 0) return;
        int boost = Math.round((float) wohlonCount / (float) totalCount * WOHLON_AMBIENT_BLOCKLIGHT);
        if (boost > stored) cir.setReturnValue(boost);
    }
}
