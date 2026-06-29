package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.shipwrights.enderkinesis.sselith.SselithEclipse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drives sky-light reads on the Sselith level toward 0 with eclipse intensity. At
 * full eclipse every {@code SkyLightEngine.getLightValue} call returns 0 regardless
 * of the underlying storage value, so debug overlays / mob-spawn / plant-growth /
 * lightmap UV all agree that "there is no sky" right now.
 *
 * <p>Pair to {@link BlockLightEngineSselithEclipseMixin}. Light-damage detection is
 * intentionally moved off the raw light value over in {@code SselithEclipse} (uses
 * {@code level.canSeeSky} instead) — depending on the suppressed value would mean
 * eclipses do no damage.
 *
 * <p>Server-side and client-side reads share {@link SselithEclipse#resolveLevelForLightEngine}
 * to identify the sselith Level reference, so the same intensity formula runs on
 * both sides against the same (gameTime, manualTriggerStart) inputs.
 */
@Mixin(LightEngine.class)
public abstract class SkyLightEngineSselithEclipseMixin {

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Unique private boolean enderkinesis$resolved;
    @Unique private Level enderkinesis$sselithLevel;

    @Inject(
        method = "getLightValue(Lnet/minecraft/core/BlockPos;)I",
        at = @At("RETURN"),
        cancellable = true
    )
    private void enderkinesis$suppressSkyLightEclipse(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!(((Object) this) instanceof SkyLightEngine)) return;
        Level level = enderkinesis$resolveSselithLevel();
        if (level == null) return;
        float eclipse = SselithEclipse.intensity(level.getGameTime());
        if (eclipse <= 0f) return;
        int original = cir.getReturnValueI();
        if (original <= 0) return;
        cir.setReturnValue(Math.round(original * (1f - eclipse)));
    }

    @Unique
    private Level enderkinesis$resolveSselithLevel() {
        if (enderkinesis$resolved) return enderkinesis$sselithLevel;
        enderkinesis$resolved = true;
        Level level = SselithEclipse.resolveLevelForLightEngine(chunkSource);
        if (level != null && level.dimension() == SselithRepertory.INSTANCE.getLEVEL_KEY()) {
            enderkinesis$sselithLevel = level;
        }
        return enderkinesis$sselithLevel;
    }
}
