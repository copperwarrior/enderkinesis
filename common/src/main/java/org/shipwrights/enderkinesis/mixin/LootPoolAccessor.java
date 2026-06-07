package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to {@link LootPool#entries} — paired with {@link
 * LootTableAccessor} for offline loot-table walking.
 */
@Mixin(LootPool.class)
public interface LootPoolAccessor {
    @Accessor("entries")
    LootPoolEntryContainer[] enderkinesis$getEntries();
}
