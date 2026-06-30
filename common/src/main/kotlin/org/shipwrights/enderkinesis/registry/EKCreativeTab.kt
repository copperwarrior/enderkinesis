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
            output.accept(EKItems.CREPUSCULITE_CHARM.get())
            output.accept(EKItems.LEDGER_OF_WATCHING_EYES.get())
            output.accept(EKItems.LEDGER_OF_HUNTING_PINCERS.get())
            output.accept(EKItems.CREPUSCULITE_ORE_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_BLOCK_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_GLASS_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_LATTICE_ITEM.get())
            output.accept(EKItems.ENDER_ASTROLABE_ITEM.get())
            output.accept(EKItems.MAGIC_MISSILE_LAUNCHER_ITEM.get())
            output.accept(EKItems.EYEROSCOPE_ITEM.get())
            output.accept(EKItems.PURPUR_BALLAST_ITEM.get())
            output.accept(EKItems.ENDER_LINKAGE_ITEM.get())
            output.accept(EKItems.ROD_OF_LEVITATION.get())
            output.accept(EKItems.MAGIC_MISSILE.get())
            output.accept(EKItems.PLANAR_ANCHOR_ITEM.get())
            output.accept(EKItems.ANCRITE_CHAIN_ITEM.get())
            output.accept(EKItems.ANCRITE_EYE_ITEM.get())
            output.accept(EKItems.VOID_HARNESS_ITEM.get())
            output.accept(EKItems.VOID_HOOK_ITEM.get())
            output.accept(EKItems.SHULKER_PUFFER_ITEM.get())
            output.accept(EKItems.SHULKER_STRUT_ITEM.get())
            output.accept(EKItems.AETHER_PAD_ITEM.get())
            output.accept(EKItems.ANCRITE_SCRAP.get())
            output.accept(EKItems.WEYYE_FRUIT.get())
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
            output.accept(EKItems.SSELITH_LANTERN_ITEM.get())
            output.accept(EKItems.SSELITH_LAMP_ITEM.get())
            output.accept(EKItems.SSELITH_LECTERN_ITEM.get())
            output.accept(EKItems.ORB_OF_LINKING_ITEM.get())
            output.accept(EKItems.ORB_OF_SCRYING_ITEM.get())
            output.accept(EKItems.HEART_OF_THE_WILD_ITEM.get())
            output.accept(EKItems.CRYSTAL_EXPLOSIVE_ITEM.get())
            output.accept(EKItems.HEART_CANDLE_ITEM.get())
            output.accept(EKItems.WOGOR_LOG_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_ITEM.get())
            output.accept(EKItems.WOGOR_PLANKS_ITEM.get())
            output.accept(EKItems.WOGOR_STAIRS_ITEM.get())
            output.accept(EKItems.WOGOR_SLAB_ITEM.get())
            output.accept(EKItems.WOGOR_FENCE_ITEM.get())
            output.accept(EKItems.WOGOR_FENCE_GATE_ITEM.get())
            output.accept(EKItems.WOGOR_PRESSURE_PLATE_ITEM.get())
            output.accept(EKItems.WOGOR_BUTTON_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_STAIRS_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_SLAB_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_FENCE_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_WALL_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_PANE_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_BUTTON_ITEM.get())
            output.accept(EKItems.BINDING_ROOTS_ITEM.get())
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
            output.accept(EKItems.TOME_OF_VENTRILOQUISM.get())
            output.accept(EKItems.TOME_OF_SPRING.get())
            output.accept(EKItems.TOME_OF_COUPLING.get())
            output.accept(EKItems.TOME_OF_FILTERING.get())
            output.accept(EKItems.TOME_OF_SCRYING.get())

            output.accept(EKItems.STAFF_OF_COMMAND.get())
            output.accept(EKItems.STAFF_OF_CONCEALMENT.get())
            output.accept(EKItems.STAFF_OF_DENSITY.get())
            output.accept(EKItems.STAFF_OF_SCALES.get())
            output.accept(EKItems.STAFF_OF_RECITAL.get())
            output.accept(EKItems.STAFF_OF_AEGIS.get())
            output.accept(EKItems.STAFF_OF_SUNDERING.get())

            output.accept(EKItems.MYSTIC_CITRINE.get())
            output.accept(EKItems.MYSTIC_ONYX.get())
            output.accept(EKItems.MYSTIC_JADE.get())
            output.accept(EKItems.MYSTIC_AQUAMARINE.get())
            output.accept(EKItems.MYSTIC_MOONSTONE.get())
            output.accept(EKItems.MYSTIC_SPINEL.get())
            output.accept(EKItems.MYSTIC_BLOODSTONE.get())

            output.accept(EKItems.END_CULT_HOOD.get())
            output.accept(EKItems.END_CULT_ROBES.get())
            output.accept(EKItems.END_CULT_ROBE_BOTTOMS.get())
            output.accept(EKItems.END_CULT_SHOES.get())
            output.accept(EKItems.SCHOLAR_HOOD.get())
            output.accept(EKItems.SCHOLAR_ROBES.get())
            output.accept(EKItems.SCHOLAR_ROBE_BOTTOMS.get())
            output.accept(EKItems.SCHOLAR_SHOES.get())
            output.accept(EKItems.BLUE_WITCH_HAT.get())
            output.accept(EKItems.BLUE_WITCH_ROBES.get())
            output.accept(EKItems.BLUE_WITCH_ROBE_BOTTOMS.get())
            output.accept(EKItems.BLUE_WITCH_SANDALS.get())

            output.accept(EKItems.SCROLL_OF_UNRAVELLING.get())
            output.accept(EKItems.SCROLL_OF_SCULK_CATASTROPHE.get())
            output.accept(EKItems.ECHO_CANNON_ITEM.get())

            for (statue in EKItems.STATUE_ITEMS) output.accept(statue.get())

            // Prismatic-goat-horn variants — 19 ItemStacks, one per
            // cave instrument, the same way vanilla shows 8 distinct
            // goat-horn stacks. The `instrument` NBT key is a plain
            // string (the instrument's ResourceLocation) — see
            // `InstrumentItem.setSoundVariantId` — so we don't need a
            // holder lookup. That's important: our 19 instruments are
            // datapack-loaded (`data/enderkinesis/instrument/cave_*.json`)
            // and live in the world's RegistryAccess, not the
            // built-in registry the Architectury modify callback has
            // access to. Hand-building the NBT bypasses the lookup
            // and works regardless of when the callback fires.
            for (idx in 1..19) {
                val stack = ItemStack(EKItems.PRISMATIC_GOAT_HORN.get())
                stack.orCreateTag.putString("instrument", "enderkinesis:cave_$idx")
                output.accept(stack)
            }

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
            output.accept(EKItems.ARCHIVE_SPAWN_EGG.get())
        }
    }
}
