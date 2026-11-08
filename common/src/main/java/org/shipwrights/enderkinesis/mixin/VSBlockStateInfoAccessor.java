package org.shipwrights.enderkinesis.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "org.valkyrienskies.mod.common.config.VSBlockStateInfo", remap = false)
public interface VSBlockStateInfoAccessor {
    @Accessor("noCollision")
    @Nullable
    Boolean enderkinesis$getNoCollision();
}
