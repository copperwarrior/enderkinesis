package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code Slot.y} is final; the Satchel of Shelving screen moves its slots to scroll smoothly. */
@Mixin(Slot.class)
public interface SlotPositionAccessor {

    @Mutable
    @Accessor("y")
    void enderkinesis$setY(int y);
}
