package org.shipwrights.enderkinesis.duck;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

public interface ImbuedProjectile {
    @Nullable CompoundTag enderkinesis$getImbue();

    void enderkinesis$setImbue(@Nullable CompoundTag imbue);
}
