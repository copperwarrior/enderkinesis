package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Discord's client-only stand-in ({@link org.shipwrights.enderkinesis.client.DiscordDisguise})
 *  sit on what its ally rides, for its renderer's riding pose, without joining the vehicle's own
 *  passengers. */
@Mixin(Entity.class)
public interface EntityVehicleAccessor {
    @Accessor("vehicle")
    void enderkinesis$setVehicle(Entity vehicle);
}
