package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.ScaleCountBySizeFunction
import org.shipwrights.enderkinesis.item.SetScrapFunction

object EKLootFunctions {
    val LOOT_FUNCTIONS: DeferredRegister<LootItemFunctionType> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.LOOT_FUNCTION_TYPE)

    val SET_SCRAP: RegistrySupplier<LootItemFunctionType> =
        LOOT_FUNCTIONS.register("set_scrap") { LootItemFunctionType(SetScrapFunction.Serializer()) }
    val SCALE_COUNT_BY_SIZE: RegistrySupplier<LootItemFunctionType> =
        LOOT_FUNCTIONS.register("scale_count_by_size") { LootItemFunctionType(ScaleCountBySizeFunction.Serializer()) }

    fun register() = LOOT_FUNCTIONS.register()
}
