package org.shipwrights.enderkinesis.mixin;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to {@link TagEntry#tag} so the loot-table walker
 * can expand `#tag` entries into their member items (e.g. sheep also
 * drops one block of `#minecraft:wool`).
 */
@Mixin(TagEntry.class)
public interface TagEntryAccessor {
    @Accessor("tag")
    TagKey<Item> enderkinesis$getTag();
}
