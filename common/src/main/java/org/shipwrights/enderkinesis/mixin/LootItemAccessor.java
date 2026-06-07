package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to {@link LootItem#item}'s underlying {@link Item}
 * reference — the actual item the loot entry can drop.
 */
@Mixin(LootItem.class)
public interface LootItemAccessor {
    @Accessor("item")
    Item enderkinesis$getItem();
}
