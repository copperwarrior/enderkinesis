package org.shipwrights.enderkinesis.item;

import dev.architectury.injectables.annotations.ExpectPlatform;
import java.util.List;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;

/** Forge patches {@code LootTable.pools} from an array to a {@code List}, so each loader reads it with its own accessor. */
public final class LootTablePools {
    private LootTablePools() {}

    @ExpectPlatform
    public static List<LootPool> of(LootTable table) {
        throw new AssertionError();
    }
}
