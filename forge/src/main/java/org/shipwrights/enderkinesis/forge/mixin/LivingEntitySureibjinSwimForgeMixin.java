package org.shipwrights.enderkinesis.forge.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fluids.FluidType;
import org.shipwrights.enderkinesis.dimension.Sureibjin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Forge aiStep calls jumpInFluid, not jumpInLiquid, so the common LivingEntitySureibjinSwimMixin never runs on Forge.
@Mixin(LivingEntity.class)
public abstract class LivingEntitySureibjinSwimForgeMixin {

    @Redirect(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;jumpInFluid(Lnet/minecraftforge/fluids/FluidType;)V",
            remap = false
        )
    )
    private void enderkinesis$weakenSwimInSureibjin(LivingEntity self, FluidType type) {
        if (!Sureibjin.applyWeakSwim(self)) self.jumpInFluid(type);
    }
}
