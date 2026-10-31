package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.monster.EnderMan;
import org.shipwrights.enderkinesis.spell.DiscordTeleports;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An enderman under Discord can't teleport: every try sputters out where it stands, so it can't
 *  blink away or dodge a projectile. See {@link DiscordTeleports}. */
@Mixin(EnderMan.class)
public abstract class EnderManDiscordMixin {

    @Inject(method = "teleport(DDD)Z", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$discord(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        EnderMan self = (EnderMan) (Object) this;
        if (self.level().isClientSide || !DiscordTeleports.disrupted(self)) return;
        DiscordTeleports.fizzle(self.level(), self.position().add(0.0, self.getBbHeight() * 0.5, 0.0));
        cir.setReturnValue(false);
    }
}
