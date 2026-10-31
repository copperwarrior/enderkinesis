package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.shipwrights.enderkinesis.spell.DarkZones;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lowers block and sky light where a spell has darkened the world ({@link DarkZones}), on the
 * server (spawning, burning undead, growth) and on the client (terrain, smooth lighting,
 * entities, particles) alike: every light query goes through {@code getLightValue}. Light
 * propagation reads its own storage, so what's stored is never changed.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineSpellDarknessMixin {

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Inject(method = "getLightValue(Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$darken(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (chunkSource == null || !(chunkSource.getLevel() instanceof Level level)) return;
        if (DarkZones.isEmpty(level.isClientSide())) return;
        int levels = DarkZones.levelsAt(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (levels <= 0) return;
        cir.setReturnValue(Math.max(0, cir.getReturnValueI() - levels));
    }
}
