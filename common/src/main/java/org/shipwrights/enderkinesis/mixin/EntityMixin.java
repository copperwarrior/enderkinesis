package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.duck.VirtualFluidHolder;
import org.shipwrights.enderkinesis.physics.CrepusculiteLatticeFluidProbe;
import org.shipwrights.enderkinesis.physics.LatticeCatchZone;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin implements VirtualFluidHolder {

    @Shadow protected boolean wasTouchingWater;

    @Unique
    private boolean enderkinesis$inVirtualFluid;

    @Override
    @Unique
    public boolean enderkinesis$isInVirtualFluid() {
        return this.enderkinesis$inVirtualFluid;
    }

    // Entity.baseTick calls updateInWaterStateAndDoFluidPushing before updateSwimming, so the flag is current here.
    @Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$updateSwimmingInCrepusculite(CallbackInfo ci) {
        if (!this.enderkinesis$inVirtualFluid) return;
        LivingEntity living = (LivingEntity) (Object) this;
        living.setSwimming(living.isSprinting() && !living.isPassenger());
        ci.cancel();
    }

    @Inject(method = "updateInWaterStateAndDoFluidPushing", at = @At("RETURN"))
    private void enderkinesis$treatVirtualFluidAsWater(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        // The probe runs in the entity's own tick, so there is no 1-tick wait for the lattice block entity.
        LatticeCatchZone zone = CrepusculiteLatticeFluidProbe.INSTANCE.findContainingZone(self);
        boolean inFluid = zone != null;
        if (inFluid) {
            // Side-of-network: server pushes non-players, client pushes players.
            CrepusculiteLatticeFluidProbe.INSTANCE.applyWavePush(self, zone);
        } else {
            inFluid = CrepusculiteLatticeFluidProbe.INSTANCE.inGrace(self);
        }
        this.enderkinesis$inVirtualFluid = inFluid;
        if (inFluid) {
            this.wasTouchingWater = true;
        }
    }
}
