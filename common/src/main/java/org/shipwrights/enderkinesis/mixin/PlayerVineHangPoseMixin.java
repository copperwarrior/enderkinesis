package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.vine.VineHoldsKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Sneak is the climb-down key on a vine, so it must not crouch the hang into a 1.5 box.
@Mixin(Player.class)
public abstract class PlayerVineHangPoseMixin {

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$standWhileHanging(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!VineHoldsKt.isHangingFromVine(self)) return;
        self.setPose(Pose.STANDING);
        ci.cancel();
    }
}
