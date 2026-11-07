package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.animal.axolotl.Axolotl;
import org.shipwrights.enderkinesis.ritual.SelvesBodies;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A player body does not dry out on land; it drowns as players do.
@Mixin(Axolotl.class)
public abstract class AxolotlSelvesMixin {

    @Inject(method = "handleAirSupply", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$selvesBodyAir(int air, CallbackInfo ci) {
        if (SelvesBodies.losesTrait((Axolotl) (Object) this)) ci.cancel();
    }
}
