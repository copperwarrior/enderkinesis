package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "org.valkyrienskies.mod.common.assembly.ShipWelder$Move", remap = false)
public interface ShipWelderMoveAccessor {
    @Accessor("from")
    BlockPos enderkinesis$getFrom();

    @Accessor("to")
    BlockPos enderkinesis$getTo();
}
