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
 * Reduces block-light readings during a Sselith Eclipse so torches/lanterns/etc.
 * effectively give off less light ("blocklight of 5 reads as 3").
 *
 * <p>Wraps {@link LightEngine#getLightValue(BlockPos)} so both rendering and
 * gameplay see the reduced value through a single shared formula. The engine's
 * stored values aren't modified — only the readings shift, so propagation reach
 * is unchanged and there's no relighting cost.
 *
 * <p>Server-side and client-side reads use the same {@link SselithEclipse#intensity}
 * computation against the same Level reference (resolved via
 * {@link SselithEclipse#resolveLevelForLightEngine}, which dispatches typed casts
 * for both {@code ServerChunkCache} and {@code ClientChunkCache}). The level
 * reference is cached per engine instance on first call.
 */
@Mixin(LightEngine.class)
public abstract class BlockLightEngineSselithEclipseMixin {

    /** At full eclipse: block-light reads multiplied by this. 0.60 hits "5 reads as 3". */
    private static final float ECLIPSE_BLOCK_LIGHT_SCALE = 0.60f;

    /** At full eclipse: a block-light read never falls more than this many units below
     *  its underlying value. Guarantees emissive sources (which propagate high stored
     *  values into their neighborhood) keep some visible illumination — a torch's
     *  adjacent stored 13 reads as at least 11, not as scale * 13 = 8. The user's
     *  "5 reads as 3" target stays intact because the scaled-down result (3) is still
     *  above the floor (5 - 2 = 3). */
    private static final int ECLIPSE_BLOCK_LIGHT_MAX_DROP = 2;

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
        float scale = 1f - eclipse + eclipse * ECLIPSE_BLOCK_LIGHT_SCALE;
        int scaled = Math.round(original * scale);
        int floor = original - Math.round(eclipse * ECLIPSE_BLOCK_LIGHT_MAX_DROP);
        cir.setReturnValue(Math.max(scaled, Math.max(0, floor)));
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
