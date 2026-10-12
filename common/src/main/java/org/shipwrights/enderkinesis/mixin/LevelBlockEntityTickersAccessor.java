package org.shipwrights.enderkinesis.mixin;

import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface LevelBlockEntityTickersAccessor {
    @Accessor("blockEntityTickers")
    List<TickingBlockEntity> getBlockEntityTickers();
}
