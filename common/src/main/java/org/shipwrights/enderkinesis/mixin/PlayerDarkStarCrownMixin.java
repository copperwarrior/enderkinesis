package org.shipwrights.enderkinesis.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.item.CrownOfTheDarkStar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerDarkStarCrownMixin {

    // The gate that hides creative players from TargetingConditions, Sensor and LivingEntity.canAttack.
    @Inject(method = "canBeSeenAsEnemy", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$crownUnseen(CallbackInfoReturnable<Boolean> cir) {
        if (CrownOfTheDarkStar.isWorn((Player) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$crownName(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        if (CrownOfTheDarkStar.isWorn(self)) cir.setReturnValue(CrownOfTheDarkStar.obfuscatedName(self));
    }
}
