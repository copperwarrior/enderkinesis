package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.enchantment.LoveOfTheMotherEnchantment

object EKEnchantments {
    val ENCHANTMENTS: DeferredRegister<Enchantment> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.ENCHANTMENT)

    val LOVE_OF_THE_MOTHER: RegistrySupplier<Enchantment> =
        ENCHANTMENTS.register("love_of_the_mother") { LoveOfTheMotherEnchantment() }

    @JvmStatic
    fun holdsLoveOfTheMother(player: Player): Boolean =
        EnchantmentHelper.getItemEnchantmentLevel(LOVE_OF_THE_MOTHER.get(), player.mainHandItem) > 0

    fun register() = ENCHANTMENTS.register()
}
