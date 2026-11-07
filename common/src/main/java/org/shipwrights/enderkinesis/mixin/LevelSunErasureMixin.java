package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.ritual.SunErasure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// skyDarken drives sky light, so the erasure darkens light levels, spawning and undead burning.
@Mixin(Level.class)
public abstract class LevelSunErasureMixin {

    @Shadow private int skyDarken;

    @Inject(method = "updateSkyBrightness", at = @At("TAIL"))
    private void enderkinesis$erasedSunDarkens(CallbackInfo ci) {
        skyDarken = SunErasure.skyDarken((Level) (Object) this, skyDarken);
    }
}
