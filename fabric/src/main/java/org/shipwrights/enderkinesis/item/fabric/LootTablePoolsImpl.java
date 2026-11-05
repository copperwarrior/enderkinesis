package org.shipwrights.enderkinesis.item.fabric;

import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.shipwrights.enderkinesis.fabric.mixin.LootTableFabricAccessor;

public final class LootTablePoolsImpl {
    private LootTablePoolsImpl() {}

    public static List<LootPool> of(LootTable table) {
        LootPool[] pools = ((LootTableFabricAccessor) table).enderkinesis$getPools();
        return pools == null ? List.of() : Arrays.asList(pools);
    }
}
