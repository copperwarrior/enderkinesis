package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
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
 * Reduces server block-light reads during a Sselith Eclipse. Stored values do not change,
 * so there is no relight cost. The client shows the same drop in its lightmap.
 */
@Mixin(LightEngine.class)
public abstract class BlockLightEngineSselithEclipseMixin {

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
    private void enderkinesis$reduceBlockLightEclipse(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!(((Object) this) instanceof BlockLightEngine)) return;
        Level level = enderkinesis$resolveSselithLevel();
        if (level == null) return;
        float eclipse = SselithEclipse.intensity(level.getGameTime());
        if (eclipse <= 0f) return;
        int original = cir.getReturnValueI();
        if (original <= 0) return;
        cir.setReturnValue(Math.round(SselithEclipse.eclipsedBlockLight(original, eclipse)));
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
