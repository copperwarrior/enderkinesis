package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to {@link CompositeEntryBase#children} — covers
 * `AlternativesEntry`, `SequentialEntry`, and `EntryGroup` so loot
 * tables built from composite entries (e.g. sheep wool colour
 * alternatives) can be walked transitively.
 */
@Mixin(CompositeEntryBase.class)
public interface CompositeEntryBaseAccessor {
    @Accessor("children")
    LootPoolEntryContainer[] enderkinesis$getChildren();
}
