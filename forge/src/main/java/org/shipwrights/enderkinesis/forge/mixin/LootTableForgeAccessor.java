package org.shipwrights.enderkinesis.forge.mixin;

import java.util.List;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LootTable.class)
public interface LootTableForgeAccessor {
    // Forge retypes pools to a List, so the Mojang name no longer maps; the SRG name is the same in dev and prod.
    @Accessor(value = "f_79109_", remap = false)
    List<LootPool> enderkinesis$getPools();
}
