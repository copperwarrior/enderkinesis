package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to {@link LootTable#pools} so {@link
 * org.shipwrights.enderkinesis.item.OrbFilterLootIndex} can walk every
 * entity's loot table once at filter-set time and build a reverse map
 * (Item → entities that drop it) for the Disintegration filter.
 */
@Mixin(LootTable.class)
public interface LootTableAccessor {
    @Accessor("pools")
    LootPool[] enderkinesis$getPools();
}
