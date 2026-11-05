package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Sets the field without setLevel, whose overrides can register the block entity in the world.
@Mixin(BlockEntity.class)
public interface BlockEntityLevelAccessor {

    @Accessor("level")
    void enderkinesis$setLevel(Level level);
}
