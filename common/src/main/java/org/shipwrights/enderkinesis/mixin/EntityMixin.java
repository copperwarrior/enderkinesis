package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.physics.CrepusculiteLatticeFluidProbe;
import org.shipwrights.enderkinesis.physics.LatticeCatchZone;
import org.shipwrights.enderkinesis.registry.EKEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {

    /** Effect duration above which the entity is treated as actively submerged. The
     *  lattice keeps duration in [50, 60] while submerged; this threshold sits below
     *  that range to absorb client/server timing skew. */
    private static final int ACTIVE_DURATION_THRESHOLD = 45;

    @Shadow protected boolean wasTouchingWater;

    @Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$updateSwimmingInCrepusculite(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!enderkinesis$inVirtualFluid(self)) return;
        LivingEntity living = (LivingEntity) self;
        living.setSwimming(living.isSprinting() && !living.isPassenger());
        ci.cancel();
    }

    @Inject(method = "updateInWaterStateAndDoFluidPushing", at = @At("RETURN"))
    private void enderkinesis$treatVirtualFluidAsWater(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        // Same-tick probe — re-derives the catch zone from the [LatticeRegistry] cache, closing
        // the 1-tick lag where a just-crossed wave plane reads no effect yet (lattice BE
        // hasn't ticked this frame) and an entity free-falls before vanilla water drag engages.
        LatticeCatchZone zone = CrepusculiteLatticeFluidProbe.INSTANCE.findContainingZone(self);
        if (zone != null) {
            this.wasTouchingWater = true;
            // Side-of-network: server pushes non-players, client pushes players.
            CrepusculiteLatticeFluidProbe.INSTANCE.applyWavePush(self, zone);
            return;
        }
        // Effect-based fallback covers the grace window after leaving the zone — the effect
        // persists briefly so the entity doesn't snap back to air physics on the boundary.
        if (enderkinesis$inVirtualFluid(self)) {
            this.wasTouchingWater = true;
        }
    }

    private static boolean enderkinesis$inVirtualFluid(Entity self) {
        if (!(self instanceof LivingEntity living)) return false;
        if (living.isPassenger() || living.isFallFlying()) return false;
        if (self instanceof Player p && (p.isSpectator() || p.getAbilities().flying)) return false;
        MobEffectInstance instance = living.getEffect(
            EKEffects.INSTANCE.getCREPUSCULAR_FLOATATION().get());
        return instance != null && instance.getDuration() >= ACTIVE_DURATION_THRESHOLD;
    }
}
