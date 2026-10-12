package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.block.IchaericInkEntity;
import org.shipwrights.enderkinesis.block.IchaericInkPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityIchaericInkMixin implements IchaericInkEntity {

    @Unique private double enderkinesis$inkHeight;
    @Unique private boolean enderkinesis$eyesInInk;

    @Override
    public double enderkinesisInkHeight() {
        return enderkinesis$inkHeight;
    }

    @Override
    public boolean enderkinesisEyesInInk() {
        return enderkinesis$eyesInInk;
    }

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void enderkinesis$trackIchaericInk(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        enderkinesis$inkHeight = IchaericInkPhysics.updateCurrents(self);
        enderkinesis$eyesInInk = IchaericInkPhysics.eyesIn(self);
        if (enderkinesis$inkHeight > 0.0) {
            self.resetFallDistance();
            self.clearFire();
        }
    }

    // Vanilla's rule for water, applied to ink: swim while sprinting, start only with the eyes under.
    @Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$swimInIchaericInk(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (enderkinesis$inkHeight <= 0.0 || self.isInWater()) return;
        boolean keep = self.isSwimming() || enderkinesis$eyesInInk;
        self.setSwimming(self.isSprinting() && !self.isPassenger() && keep);
        ci.cancel();
    }
}
