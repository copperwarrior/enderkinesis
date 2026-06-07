package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.CreativeTabRegistry
import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.EnderkinesisMod

object EKCreativeTab {
    private val TABS: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.CREATIVE_MODE_TAB)

    val MAIN: RegistrySupplier<CreativeModeTab> = TABS.register("enderkinesis") {
        CreativeTabRegistry.create(Component.translatable("itemGroup.enderkinesis")) {
            ItemStack(EKItems.CREPUSCULITE.get())
        }
    }

    fun register() {
        TABS.register()
        // Populate the tab once everything is registered.
        CreativeTabRegistry.modify(MAIN) { _, output, _ ->
            output.accept(EKItems.CREPUSCULITE.get())
            output.accept(EKItems.CREPUSCULITE_ORE_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_BLOCK_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_GLASS_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_LATTICE_ITEM.get())
            output.accept(EKItems.ENDER_ASTROLABE_ITEM.get())
            output.accept(EKItems.PURPUR_BALLAST_ITEM.get())
            output.accept(EKItems.PLANAR_ANCHOR_ITEM.get())
            output.accept(EKItems.ANCRITE_CHAIN_ITEM.get())
            output.accept(EKItems.VOID_HARNESS_ITEM.get())
            output.accept(EKItems.SHULKER_PUFFER_ITEM.get())
            output.accept(EKItems.AETHER_PAD_ITEM.get())
            output.accept(EKItems.ANCRITE_SCRAP.get())
            output.accept(EKItems.RAW_ANCRITE_ITEM.get())
            output.accept(EKItems.REFINED_ANCRITE_ITEM.get())
            output.accept(EKItems.REFINED_ANCRITE_STAIRS_ITEM.get())
            output.accept(EKItems.REFINED_ANCRITE_SLAB_ITEM.get())
            output.accept(EKItems.REFINED_ANCRITE_WALL_ITEM.get())
            output.accept(EKItems.ANCRITE_BLOCK_ITEM.get())
            output.accept(EKItems.ANCRITE_STAIRS_ITEM.get())
            output.accept(EKItems.ANCRITE_SLAB_ITEM.get())
            output.accept(EKItems.ANCRITE_WALL_ITEM.get())
            output.accept(EKItems.ANCRITE_GRATE_ITEM.get())
            output.accept(EKItems.ANCRITE_BARS_ITEM.get())
            output.accept(EKItems.ANCRITE_BEAM_ITEM.get())
            output.accept(EKItems.SSELITH_BOOKSHELF_ITEM.get())
            output.accept(EKItems.ORB_OF_LINKING_ITEM.get())
            output.accept(EKItems.HEART_OF_THE_WILD_ITEM.get())
            output.accept(EKItems.HEART_CANDLE_ITEM.get())
            output.accept(EKItems.WOGOR_LOG_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_ITEM.get())
            output.accept(EKItems.WOGOR_PLANKS_ITEM.get())
            output.accept(EKItems.ALMANAC_OF_EVERYWHERE.get())
            output.accept(EKItems.WYLLAND_TOME.get())
            output.accept(EKItems.MUSIC_DISC_MYSTERY.get())
            output.accept(EKItems.GUIDE_TO_STRATUS.get())
            output.accept(EKItems.TOME_OF_SIGNAL.get())
            output.accept(EKItems.TOME_OF_BINDING.get())
            output.accept(EKItems.TOME_OF_CHAINING.get())
            output.accept(EKItems.TOME_OF_ELASTICITY.get())
            output.accept(EKItems.TOME_OF_TRANSPORTATION.get())
            output.accept(EKItems.TOME_OF_VACUUM.get())
            output.accept(EKItems.TOME_OF_PULLING.get())
            output.accept(EKItems.TOME_OF_PUSHING.get())
            output.accept(EKItems.TOME_OF_HINGING.get())
            output.accept(EKItems.TOME_OF_DISINTEGRATION.get())
            output.accept(EKItems.TOME_OF_FILTERING.get())
            // Cataloger spawn egg intentionally NOT added here — it
            // lives in the vanilla Spawn Eggs tab below.
        }
        // Cataloger spawn egg in vanilla's "Spawn Eggs" tab so it
        // groups with the other vanilla mob eggs (which is how players
        // expect to find new entity eggs). `defer(ResourceKey)` gives
        // us a DeferredSupplier that resolves the vanilla tab when the
        // tab registry is ready.
        CreativeTabRegistry.modify(CreativeTabRegistry.defer(CreativeModeTabs.SPAWN_EGGS)) { _, output, _ ->
            output.accept(EKItems.CATALOGER_SPAWN_EGG.get())
        }
    }
}
