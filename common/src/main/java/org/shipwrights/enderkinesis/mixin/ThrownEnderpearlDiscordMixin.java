package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.shipwrights.enderkinesis.spell.DiscordTeleports;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A pearl thrown by someone under Discord fizzles where it lands: no teleport, no fall damage,
 *  no endermite. See {@link DiscordTeleports}. */
@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlDiscordMixin {

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$discord(HitResult hit, CallbackInfo ci) {
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        Entity owner = self.getOwner();
        if (self.level().isClientSide || !DiscordTeleports.disrupted(owner)) return;
        DiscordTeleports.fizzle(self.level(), self.position());
        self.discard();
        ci.cancel();
    }
}
