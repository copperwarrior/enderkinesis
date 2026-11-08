package org.shipwrights.enderkinesis.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.valkyrienskies.mod.common.assembly.WeldRotation;

// Elements of the move list are ShipWelder.Move; read them through ShipWelderMoveAccessor.
@Mixin(targets = "org.valkyrienskies.mod.common.assembly.ShipWelder$Plan", remap = false)
public interface ShipWelderPlanAccessor {
    @Accessor("rotation")
    WeldRotation enderkinesis$getRotation();

    @Accessor("moves")
    List<?> enderkinesis$getMoves();
}
