package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerLevel;
import org.shipwrights.enderkinesis.physics.ShipGravityHold;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.assembly.ShipWelder;

@Mixin(value = ShipWelder.class, remap = false)
public class ShipWelderGravityHoldMixin {

    // VS2 calls ShipWelder.tick once per level each server tick; this is the only caller of ShipGravityHold.applyAll.
    @Inject(method = "tick", at = @At("HEAD"))
    private static void enderkinesis$applyGravityHolds(ServerLevel level, CallbackInfo ci) {
        ShipGravityHold.applyAll(level);
    }
}
