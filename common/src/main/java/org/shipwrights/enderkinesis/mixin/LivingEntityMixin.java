package org.shipwrights.enderkinesis.mixin;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.duck.SwimmingHolder;
import org.shipwrights.enderkinesis.registry.EKEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two-purpose mixin on {@link LivingEntity}:
 *
 * <ol>
 *   <li>Accessor: exposes vanilla's {@code protected boolean jumping} field via
 *       {@link SwimmingHolder#enderkinesis$isJumping()} so the Crepusculite Lattice
 *       (Kotlin, separate package) can read it.</li>
 *
 *   <li>Drown-immunity in the fake fluid: cancels {@code DROWN} damage when the
 *       entity has the lattice's {@code CREPUSCULAR_FLOATATION} effect *and* there's
 *       no real water fluid at the entity's position. Vanilla's water-sensitive damage
 *       path (endermen / blazes / snow golems / striders) checks
 *       {@code isInWater() && isSensitiveToWater()} and hurts them with
 *       {@code damageSources().drown()}. Our {@link EntityMixin} water-fake makes
 *       {@code isInWater()} return true for them in the lattice's region, which
 *       silently routed those mobs into the damage path — silly. The dual-condition
 *       check here lets them keep all the other water mechanics (buoyancy, swim,
 *       wave-push) while skipping the damage tick that the fake fluid had no business
 *       triggering. Real water still drowns them.</li>
 * </ol>
 *
 * The submerged-marker state and the {@code updateSwimming} hook live on
 * {@link EntityMixin} (Entity is the target there because that's where the method is
 * declared); this mixin handles the LivingEntity-specific cases.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin implements SwimmingHolder {

    @Shadow
    protected boolean jumping;

    @Override
    @Unique
    public boolean enderkinesis$isJumping() {
        return this.jumping;
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$skipDrownFromFakeFluid(
        DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!source.is(DamageTypeTags.IS_DROWNING)) return;
        LivingEntity self = (LivingEntity) (Object) this;
        // Only cancel drown damage caused by the lattice's fake fluid. Real water (with the
        // effect or without) still drowns — the fake fluid isn't a damage-immunity field.
        if (!self.hasEffect(EKEffects.INSTANCE.getCREPUSCULAR_FLOATATION().get())) return;
        if (self.level().getFluidState(self.blockPosition()).is(FluidTags.WATER)) return;
        cir.setReturnValue(false);
    }
}
