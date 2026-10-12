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

    // The weld drive copies gravityOverride here; a write after this copy leaves the drive
    // and the attachment one game tick apart.
    @Inject(method = "tick", at = @At("HEAD"))
    private static void enderkinesis$applyGravityHolds(ServerLevel level, CallbackInfo ci) {
        ShipGravityHold.applyAll(level);
    }
}
