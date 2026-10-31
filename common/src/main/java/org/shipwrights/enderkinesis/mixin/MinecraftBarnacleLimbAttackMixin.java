package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import org.shipwrights.enderkinesis.client.YgannBarnacleLimbClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftBarnacleLimbAttackMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$cutBarnacleLimb(CallbackInfoReturnable<Boolean> cir) {
        if (YgannBarnacleLimbClient.tryCut((Minecraft) (Object) this)) cir.setReturnValue(false);
    }

    // continueAttack reads hitResult on its own, so without this a held click mines the hull behind the limb.
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$stopMiningThroughLimb(boolean pressed, CallbackInfo ci) {
        if (!pressed) return;
        Minecraft self = (Minecraft) (Object) this;
        if (self.gameMode == null || !YgannBarnacleLimbClient.blocksMining(self)) return;
        self.gameMode.stopDestroyBlock();
        ci.cancel();
    }
}
