package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels {@link Entity#onBelowWorld()} (the old "outOfWorld" — renamed by Mojang in 1.18)
 * while inside {@link org.shipwrights.enderkinesis.dimension.SselithRepertory}.
 *
 * <p>Sselith's void wrap (see
 * {@link org.shipwrights.enderkinesis.dimension.SselithVoidWrap}) catches entities at
 * y &lt; -256 and teleports them back to the top. Vanilla, though, calls
 * {@code onBelowWorld()} as soon as an entity drops below {@code minBuildHeight − 64},
 * which is well above -256. For non-living entities (item drops, arrows, XP orbs, …) the
 * default {@code onBelowWorld()} body is {@code discard()} — they'd vanish before the wrap
 * could catch them.
 *
 * <p>Cancelling at HEAD in Sselith short-circuits the discard entirely; the entity keeps
 * falling until the wrap handler picks it up next tick. {@code LivingEntity} overrides
 * {@code onBelowWorld()} to call {@code hurt(fellOutOfWorld, 4)} instead of discarding, so
 * this mixin doesn't affect living-entity damage — that's handled separately by
 * {@code SselithVoidWrap.onLivingHurt} cancelling the damage event.
 */
@Mixin(Entity.class)
public abstract class EntitySselithVoidWrapMixin {

    @Inject(method = "onBelowWorld", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$skipOnBelowWorldInSselith(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level().dimension() == SselithRepertory.INSTANCE.getLEVEL_KEY()) {
            ci.cancel();
        }
    }
}
