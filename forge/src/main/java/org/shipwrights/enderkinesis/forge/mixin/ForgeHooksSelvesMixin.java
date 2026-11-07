package org.shipwrights.enderkinesis.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidType;
import org.shipwrights.enderkinesis.ritual.SelvesBodies;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Forge only: Forge baseTick has no canBreatheUnderwater call, so the Fabric baseTick hook has no target here.
@Mixin(value = ForgeHooks.class, remap = false)
public abstract class ForgeHooksSelvesMixin {

    // At the drowning check, because water mobs override canBreatheUnderwater.
    @WrapOperation(
        method = "onLivingBreathe",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;canDrownInFluidType(Lnet/minecraftforge/fluids/FluidType;)Z"
        )
    )
    private static boolean enderkinesis$selvesBodyDrowns(LivingEntity entity, FluidType type, Operation<Boolean> original) {
        return original.call(entity, type)
            || (type == ForgeMod.WATER_TYPE.get() && SelvesBodies.losesTrait(entity));
    }
}
