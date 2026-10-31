package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleVelocityAccessor {
    @Accessor("xd") double enderkinesis$getXd();
    @Accessor("yd") double enderkinesis$getYd();
    @Accessor("zd") double enderkinesis$getZd();
    @Accessor("xd") void enderkinesis$setXd(double xd);
    @Accessor("yd") void enderkinesis$setYd(double yd);
    @Accessor("zd") void enderkinesis$setZd(double zd);
}
