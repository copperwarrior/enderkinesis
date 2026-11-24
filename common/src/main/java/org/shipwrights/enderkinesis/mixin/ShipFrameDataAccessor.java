package org.shipwrights.enderkinesis.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.valkyrienskies.core.api.ships.ClientShip;

@Mixin(targets = "org.valkyrienskies.mod.common.render.batched.ShipBatchRenderer$ShipFrameData", remap = false)
public interface ShipFrameDataAccessor {
    @Accessor("ship")
    ClientShip enderkinesis$getShip();
}
