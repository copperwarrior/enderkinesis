package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.spell.ElementalEnvironment;
import org.spongepowered.asm.mixin.Mixin;

// LIVING_HURT fires before shields, Fire Resistance and i-frames; only a health or absorption loss is a damage deed.
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtElementSourceMixin {

    @WrapMethod(method = "hurt")
    private boolean enderkinesis$damageDeed(DamageSource source, float amount, Operation<Boolean> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        float health = self.getHealth();
        float absorption = self.getAbsorptionAmount();
        boolean hurt = original.call(source, amount);
        if (self.getHealth() < health || self.getAbsorptionAmount() < absorption) ElementalEnvironment.damaged(self, source);
        return hurt;
    }
}
