package org.shipwrights.enderkinesis.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.shipwrights.enderkinesis.registry.EKFluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Boats float on Ichaeric Ink without it joining the water tag. Forge boats ask FluidType.supportsBoating instead. */
@Mixin(Boat.class)
public abstract class BoatIchaericInkMixin {

    @WrapOperation(
        method = {"checkInWater", "isUnderwater", "getWaterLevelAbove", "checkFallDamage"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z")
    )
    private boolean enderkinesis$boatsFloatOnInk(FluidState state, TagKey<Fluid> tag, Operation<Boolean> original) {
        return original.call(state, tag)
            || (tag == FluidTags.WATER && state.getType().isSame(EKFluids.INSTANCE.getICHAERIC_INK().get()));
    }
}
