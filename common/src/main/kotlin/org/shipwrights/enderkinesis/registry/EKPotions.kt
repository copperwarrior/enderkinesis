package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.Potion
import net.minecraft.world.item.alchemy.PotionBrewing
import net.minecraft.world.item.alchemy.Potions
import org.shipwrights.enderkinesis.EnderkinesisMod

object EKPotions {
    private val POTIONS: DeferredRegister<Potion> = DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.POTION)

    // All three share one name, so they share the item name keys, as vanilla long and strong potions do.
    private const val PHYLLOBATIC_NAME = "phyllobatic_toxicity"
    private const val NORMAL_TICKS = 3600
    private const val LONG_TICKS = 9600
    private const val STRONG_TICKS = 1800

    val PHYLLOBATIC_TOXICITY: RegistrySupplier<Potion> = POTIONS.register(PHYLLOBATIC_NAME) {
        Potion(PHYLLOBATIC_NAME, MobEffectInstance(EKEffects.PHYLLOBATIC_TOXICITY.get(), NORMAL_TICKS))
    }
    val LONG_PHYLLOBATIC_TOXICITY: RegistrySupplier<Potion> = POTIONS.register("long_$PHYLLOBATIC_NAME") {
        Potion(PHYLLOBATIC_NAME, MobEffectInstance(EKEffects.PHYLLOBATIC_TOXICITY.get(), LONG_TICKS))
    }
    val STRONG_PHYLLOBATIC_TOXICITY: RegistrySupplier<Potion> = POTIONS.register("strong_$PHYLLOBATIC_NAME") {
        Potion(PHYLLOBATIC_NAME, MobEffectInstance(EKEffects.PHYLLOBATIC_TOXICITY.get(), STRONG_TICKS, 1))
    }

    fun register() = POTIONS.register()

    /** Call from LifecycleEvent.SETUP: the potions and items exist on both loaders by then. */
    fun registerBrewing() {
        // addMix is private in vanilla; the access widener opens it (an access transformer on Forge).
        PotionBrewing.addMix(Potions.AWKWARD, EKItems.FROG_LIVER.get(), PHYLLOBATIC_TOXICITY.get())
        PotionBrewing.addMix(PHYLLOBATIC_TOXICITY.get(), Items.REDSTONE, LONG_PHYLLOBATIC_TOXICITY.get())
        PotionBrewing.addMix(PHYLLOBATIC_TOXICITY.get(), Items.GLOWSTONE_DUST, STRONG_PHYLLOBATIC_TOXICITY.get())
    }
}
