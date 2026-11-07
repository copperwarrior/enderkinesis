package org.shipwrights.enderkinesis.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.ritual.SelvesBodies;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Fabric only: Forge moves this drowning check into ForgeHooks.onLivingBreathe (see ForgeHooksSelvesMixin).
@Mixin(LivingEntity.class)
public abstract class LivingEntitySelvesFabricMixin {

    // At the drowning check, because water mobs override canBreatheUnderwater.
    @ModifyExpressionValue(
        method = "baseTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;canBreatheUnderwater()Z")
    )
    private boolean enderkinesis$selvesBodyDrowns(boolean original) {
        return original && !SelvesBodies.losesTrait((LivingEntity) (Object) this);
    }
}
