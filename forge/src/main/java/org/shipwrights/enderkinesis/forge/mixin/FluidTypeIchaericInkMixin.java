package org.shipwrights.enderkinesis.forge.mixin;

import net.minecraftforge.fluids.FluidType;
import org.shipwrights.enderkinesis.registry.EKFluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forge's entity code skips FluidTypes that report air, so Ichaeric Ink gets no Forge fluid physics, breathing, or
 * screen overlay; IchaericInkPhysics gives it the same swimming as on Fabric. Only entity code reads isAir.
 * Forge boats ask supportsBoating; Fabric boats are handled in BoatIchaericInkMixin.
 */
@Mixin(value = FluidType.class, remap = false)
public abstract class FluidTypeIchaericInkMixin {

    @Inject(method = "isAir", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$inkIsNoForgeFluid(CallbackInfoReturnable<Boolean> cir) {
        if (enderkinesis$isInk()) cir.setReturnValue(true);
    }

    @Inject(method = "supportsBoating*", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$boatsFloatOnInk(CallbackInfoReturnable<Boolean> cir) {
        if (enderkinesis$isInk()) cir.setReturnValue(true);
    }

    private boolean enderkinesis$isInk() {
        return (Object) this == EKFluids.INSTANCE.getICHAERIC_INK().get().getFluidType();
    }
}
