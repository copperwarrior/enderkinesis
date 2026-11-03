package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.shipwrights.enderkinesis.item.CrownOfTheDarkStar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Warden.class)
public abstract class WardenDarkStarCrownMixin {

    // The Warden checks NO_CREATIVE_OR_SPECTATOR, not canBeSeenAsEnemy.
    @Inject(method = "canTargetEntity", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$crownUntargetable(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (CrownOfTheDarkStar.isWorn(entity)) cir.setReturnValue(false);
    }
}
