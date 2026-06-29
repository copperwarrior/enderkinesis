package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.shipwrights.enderkinesis.body.OrbDynamicLightMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks {@code LightEngine.getLightValue(BlockPos)} — Sodium / Iris / vanilla all route
 * per-block lightmap reads through here, so overriding lets Sodium's mesh-build pick up
 * the orb glow without any Sodium-specific compat code (same hook the Wohlon ambient floor
 * uses in {@link LayerLightEngineWohlonAmbientMixin}).
 *
 * <p>Trade-off vs true BFS propagation: the orb glow leaks through walls (no opacity
 * check). Intentional for a decorative magical orb, and dodges the Sodium-vs-vanilla
 * light-engine plumbing problem.
 */
@Mixin(LightEngine.class)
public abstract class BlockLightEngineOrbDynamicLightMixin {

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    private static final String CLIENT_CHUNK_CACHE_CLASS = "net.minecraft.client.multiplayer.ClientChunkCache";

    @Inject(
        method = "getLightValue(Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"),
        cancellable = true
    )
    private void enderkinesis$boostFromOrbs(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        // Block-light layer only — sky light is handled by the same
        // method on SkyLightEngine, which we don't want to touch.
        if (!(((Object) this) instanceof BlockLightEngine)) return;
        if (OrbDynamicLightMap.isEmpty()) return;
        // Client-only: skip on the server-side LightEngine so mob
        // spawning, plant growth, redstone, etc. all see the
        // unmodified vanilla light value. Same gate the Wohlon
        // ambient mixin uses.
        if (chunkSource == null
            || !CLIENT_CHUNK_CACHE_CLASS.equals(chunkSource.getClass().getName())) return;
        int stored = cir.getReturnValueI();
        if (stored >= 15) return;
        int orb = OrbDynamicLightMap.contributionAt(pos.getX(), pos.getY(), pos.getZ());
        if (orb > stored) cir.setReturnValue(orb);
    }
}
