package org.shipwrights.enderkinesis.fabric.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LootTable.class)
public interface LootTableFabricAccessor {
    @Accessor("pools")
    LootPool[] enderkinesis$getPools();
}
