package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes {@link Particle}'s protected world-coord fields for the concealment helper.
 *  Particle doesn't ship public getters for {@code x/y/z}, but those are exactly the
 *  values we need to test against concealed ships' shipyard volumes. */
@Mixin(Particle.class)
public interface ParticlePositionAccessor {
    @Accessor("x") double enderkinesis$getX();
    @Accessor("y") double enderkinesis$getY();
    @Accessor("z") double enderkinesis$getZ();
}
