package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.dimension.WeatherlessDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla shares overworld weather with every level; isRaining and isThundering derive from these two getters. */
@Mixin(Level.class)
public abstract class LevelWeatherlessMixin {

    @Inject(method = "getRainLevel(F)F", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noRain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (WeatherlessDimensions.contains(((Level) (Object) this).dimension())) cir.setReturnValue(0.0F);
    }

    @Inject(method = "getThunderLevel(F)F", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noThunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (WeatherlessDimensions.contains(((Level) (Object) this).dimension())) cir.setReturnValue(0.0F);
    }
}
