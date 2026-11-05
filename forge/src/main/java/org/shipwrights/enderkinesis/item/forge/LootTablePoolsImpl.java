package org.shipwrights.enderkinesis.item.forge;

import java.util.List;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.shipwrights.enderkinesis.forge.mixin.LootTableForgeAccessor;

public final class LootTablePoolsImpl {
    private LootTablePoolsImpl() {}

    public static List<LootPool> of(LootTable table) {
        List<LootPool> pools = ((LootTableForgeAccessor) table).enderkinesis$getPools();
        return pools == null ? List.of() : pools;
    }
}
