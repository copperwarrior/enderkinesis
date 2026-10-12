package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.worldgen.ShipCrew;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
public class WorldGenRegionCrewCaptureMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$captureCrew(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (ShipCrew.capture(entity)) cir.setReturnValue(true);
    }
}
