package org.shipwrights.enderkinesis.duck;

/**
 * Duck-type accessor for {@code LivingEntity.jumping} (vanilla declares it
 * {@code protected}, so we need an accessor through a mixin {@code @Shadow}). Implemented
 * by {@code LivingEntityMixin} so the lattice (Kotlin, separate package) can read it.
 *
 * Lives in {@code duck}, NOT {@code mixin} — Mixin's package is private to mixin
 * processing and refuses to let regular classloaders touch classes inside it.
 *
 * (The earlier "actively submerged" stamp lived here too, but it didn't replicate to the
 * client, so vanilla's tick-side {@code updateSwimming} would undo our swim flag the very
 * next client tick. We use the effect duration as the active-submerged signal instead —
 * it's already replicated.)
 */
public interface SwimmingHolder {
    /** Vanilla {@code LivingEntity.jumping} via {@code @Shadow}; false for non-living. */
    boolean enderkinesis$isJumping();
}
