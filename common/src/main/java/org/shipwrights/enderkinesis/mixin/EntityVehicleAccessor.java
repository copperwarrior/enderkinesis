package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the client-only Discord disguise sit on a vehicle without becoming one of its passengers. */
@Mixin(Entity.class)
public interface EntityVehicleAccessor {
    @Accessor("vehicle")
    void enderkinesis$setVehicle(Entity vehicle);
}
