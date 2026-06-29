package org.shipwrights.enderkinesis.mixin;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.shipwrights.enderkinesis.body.OrbDynamicLightMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium-path mirror of {@link BlockLightEngineOrbDynamicLightMixin}. Sodium's mesh
 * pipeline reads block-light directly from cached {@code DataLayer} arrays in
 * {@code WorldSlice.getBrightness} and never calls
 * {@code LightEngine.getLightValue(BlockPos)}, so the vanilla-side hook never shows up in
 * Sodium-rendered terrain — this applies the same Manhattan-distance contribution at
 * Sodium's read site.
 *
 * <p>Gated by {@link EnderkinesisMixinPlugin#shouldApplyMixin} on Sodium's presence — the
 * {@code WorldSlice} target class doesn't exist without Sodium and the mixin would fail
 * PREPARE.
 */
@Mixin(value = WorldSlice.class, remap = false)
public abstract class WorldSliceOrbDynamicLightMixin {

    @Inject(
        method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"),
        cancellable = true,
        remap = true
    )
    private void enderkinesis$boostFromOrbs(
        LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir
    ) {
        if (layer != LightLayer.BLOCK) return;
        if (OrbDynamicLightMap.isEmpty()) return;
        int stored = cir.getReturnValueI();
        if (stored >= 15) return;
        int orb = OrbDynamicLightMap.contributionAt(pos.getX(), pos.getY(), pos.getZ());
        if (orb > stored) cir.setReturnValue(orb);
    }
}
