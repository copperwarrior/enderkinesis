package org.shipwrights.enderkinesis.mixin;

import java.util.Optional;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Properties.offsetType only takes the vanilla XZ/XYZ functions; this sets a custom one. */
@Mixin(BlockBehaviour.Properties.class)
public interface BlockPropertiesAccessor {

    @Accessor("offsetFunction")
    void enderkinesis$setOffsetFunction(Optional<BlockBehaviour.OffsetFunction> function);
}
