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
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Inscription
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.Spell

object EKCreativeTab {
    private val TABS: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.CREATIVE_MODE_TAB)

    val MAIN: RegistrySupplier<CreativeModeTab> = TABS.register("enderkinesis") {
        CreativeTabRegistry.create(Component.translatable("itemGroup.enderkinesis")) {
            ItemStack(EKItems.CREPUSCULITE.get())
        }
    }

    val SCROLLS: RegistrySupplier<CreativeModeTab> = TABS.register("enderkinesis_ulder_scrolls") {
        CreativeTabRegistry.create(Component.translatable("itemGroup.enderkinesis.ulder_scrolls")) {
            scroll(List(Inscription.MAX_GLYPHS) { Element.VOID })
        }
    }

    private fun scroll(glyphs: List<Element>): ItemStack =
        ItemStack(EKItems.ULDER_SCROLL.get()).apply { orCreateTag.put(Spell.NBT_GLYPHS, Spell(glyphs).save()) }

    @Suppress("LongMethod") // Registration: one flat list of creative-tab entries.
    fun register() {
        TABS.register()
        CreativeTabRegistry.modify(SCROLLS) { _, output, _ ->
            // Sundering is never inscribed.
            for (element in Element.entries) if (element != Element.SUNDERING) output.accept(scroll(List(Inscription.MAX_GLYPHS) { element }))
            for (signature in Signature.entries) output.accept(scroll(signature.glyphs))
        }
        // Main tab in board-column order: Ygann (with all of the End), Sselith, Wohlon, Sureibjin, General.
        CreativeTabRegistry.modify(MAIN) { _, output, _ ->
            // Ygann, with everything of the End: materials, blocks, devices, tools, books, wearables, food, music
            output.accept(EKItems.CREPUSCULITE.get())
            output.accept(EKItems.CREPUSCULITE_ORE_ITEM.get())
            output.accept(EKItems.RAW_ANCRITE_ITEM.get())
            output.accept(EKItems.REFINED_ANCRITE_ITEM.get())
            output.accept(EKItems.ANCRITE_SCRAP.get())
            output.accept(EKItems.CREPUSCULITE_BLOCK_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_GLASS_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_LATTICE_ITEM.get())
            output.accept(EKItems.CREPUSCULITE_LIGHTSTONE_ITEM.get())
            output.accept(EKItems.ANCRITE_BLOCK_ITEM.get())
            output.accept(EKItems.ANCRITE_STAIRS_ITEM.get())
            output.accept(EKItems.ANCRITE_SLAB_ITEM.get())
            output.accept(EKItems.ANCRITE_WALL_ITEM.get())
            output.accept(EKItems.REFINED_ANCRITE_STAIRS_ITEM.get())
            output.accept(EKItems.REFINED_ANCRITE_SLAB_ITEM.get())
            output.accept(EKItems.REFINED_ANCRITE_WALL_ITEM.get())
            output.accept(EKItems.ANCRITE_GRATE_ITEM.get())
            output.accept(EKItems.ANCRITE_BARS_ITEM.get())
            output.accept(EKItems.ANCRITE_BEAM_ITEM.get())
            output.accept(EKItems.ENDER_ASTROLABE_ITEM.get())
            output.accept(EKItems.LODESTAR_LAUNCHER_ITEM.get())
            output.accept(EKItems.EYEROSCOPE_ITEM.get())
            output.accept(EKItems.PURPUR_BALLAST_ITEM.get())
            output.accept(EKItems.ENDER_LINKAGE_ITEM.get())
            output.accept(EKItems.PLANAR_ANCHOR_ITEM.get())
            output.accept(EKItems.ANCRITE_CHAIN_ITEM.get())
            output.accept(EKItems.ANCRITE_EYE_ITEM.get())
            output.accept(EKItems.VOID_HARNESS_ITEM.get())
            output.accept(EKItems.VOID_HOOK_ITEM.get())
            output.accept(EKItems.SHULKER_PUFFER_ITEM.get())
            output.accept(EKItems.SHULKER_STRUT_ITEM.get())
            output.accept(EKItems.AETHER_PAD_ITEM.get())
            output.accept(EKItems.CRYSTAL_EXPLOSIVE_ITEM.get())
            output.accept(EKItems.ULDER_BEAM_ANCHOR_ITEM.get())
            output.accept(EKItems.ORB_OF_LINKING_ITEM.get())
            output.accept(EKItems.QUASAR_OF_PURITY_ITEM.get())
            output.accept(EKItems.ROD_OF_LEVITATION.get())
            output.accept(EKItems.LODESTAR_DART.get())
            output.accept(EKItems.GRAND_STAFF_OF_DREAD.get())
            output.accept(EKItems.ALMANAC_OF_EVERYWHERE.get())
            output.accept(EKItems.INCANTATION_OF_THE_ABYSS.get())
            output.accept(EKItems.SCROLL_OF_ANIMATION.get())
            output.accept(EKItems.CREPUSCULITE_CHARM.get())
            output.accept(EKItems.END_CULT_HOOD.get())
            output.accept(EKItems.END_CULT_ROBES.get())
            output.accept(EKItems.END_CULT_ROBE_BOTTOMS.get())
            output.accept(EKItems.END_CULT_SHOES.get())
            output.accept(EKItems.YGANN_ACUUL.get())
            output.accept(EKItems.MUSIC_DISC_MYSTERY.get())
            output.accept(EKItems.SHELL_OF_TIDES.get())
            output.accept(EKItems.HRASS_SACK.get())
            output.accept(EKItems.GAS_VENT_ITEM.get())

            // Sselith: materials, blocks, devices, tools, books, wearables, food
            output.accept(EKItems.OLD_PAPER.get())
            output.accept(EKItems.ICHAERIC_INK_BOTTLE.get())
            output.accept(EKItems.ICHAERIC_INK_BUCKET.get())
            output.accept(EKItems.SSELITH_BOOKSHELF_ITEM.get())
            output.accept(EKItems.SSELITH_LANTERN_ITEM.get())
            output.accept(EKItems.SSELITH_LAMP_ITEM.get())
            output.accept(EKItems.SSELITH_LADDER_ITEM.get())
            output.accept(EKItems.SSELITH_TRAPDOOR_ITEM.get())
            output.accept(EKItems.OWL_DEEPSLATE_ITEM.get())
            output.accept(EKItems.SSELITH_LECTERN_ITEM.get())
            // Vanilla wool order.
            for (color in net.minecraft.world.item.DyeColor.entries) {
                EKItems.MOTH_CLOTH_ITEMS_BY_COLOR[color]?.get()?.let(output::accept)
            }
            for (statue in EKItems.STATUE_ITEMS) output.accept(statue.get())
            output.accept(EKItems.SCRIBES_DESK_ITEM.get())
            output.accept(EKItems.OARSCRIBE_ITEM.get())
            output.accept(EKItems.ICHAERIC_INK_JAR_ITEM.get())
            output.accept(EKItems.INEGRIC_RETORT_ITEM.get())
            output.accept(EKItems.JAR_RECEPTACLE_ITEM.get())
            output.accept(EKItems.FORGOTTEN_ITEM_ITEM.get())
            output.accept(EKItems.ALTAR_OF_ILLUMINATION_ITEM.get())
            output.accept(EKItems.ORB_OF_SCRYING_ITEM.get())
            output.accept(EKItems.ECHO_CANNON_ITEM.get())
            output.accept(EKItems.PARALLELOGRAPH.get())
            output.accept(EKItems.PENFEATHER_OF_THE_ACCURSED_OWL.get())
            output.accept(EKItems.EIDETIC_BOOKMARK.get())
            output.accept(EKItems.SATCHEL_OF_SHELVING.get())
            output.accept(EKItems.GRAND_STAFF_OF_THE_DEAD.get())
            output.accept(EKItems.SSELITH_BOOK.get())
            output.accept(EKItems.WYLLAND_TOME.get())
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
            output.accept(EKItems.INCANTATION_OF_WINDS.get())
            output.accept(EKItems.SCROLL_OF_UNRAVELLING.get())
            output.accept(EKItems.SCROLL_OF_SCULK_CATASTROPHE.get())
            output.accept(EKItems.SCHOLAR_HOOD.get())
            output.accept(EKItems.SCHOLAR_ROBES.get())
            output.accept(EKItems.SCHOLAR_ROBE_BOTTOMS.get())
            output.accept(EKItems.SCHOLAR_SHOES.get())
            output.accept(EKItems.WAYBREAD.get())

            // Wohlon: blocks, nature, devices, tools, books, wearables, food, music
            output.accept(EKItems.WOGOR_LOG_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_ITEM.get())
            output.accept(EKItems.STRIPPED_WOGOR_LOG_ITEM.get())
            output.accept(EKItems.STRIPPED_WOGOR_WOOD_ITEM.get())
            output.accept(EKItems.WOGOR_PLANKS_ITEM.get())
            output.accept(EKItems.WOGOR_LEAVES_ITEM.get())
            output.accept(EKItems.WOGOR_STAIRS_ITEM.get())
            output.accept(EKItems.WOGOR_SLAB_ITEM.get())
            output.accept(EKItems.WOGOR_FENCE_ITEM.get())
            output.accept(EKItems.WOGOR_FENCE_GATE_ITEM.get())
            output.accept(EKItems.WOGOR_DOOR_ITEM.get())
            output.accept(EKItems.WOGOR_TRAPDOOR_ITEM.get())
            output.accept(EKItems.WOGOR_PRESSURE_PLATE_ITEM.get())
            output.accept(EKItems.WOGOR_BUTTON_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_STAIRS_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_SLAB_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_FENCE_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_WALL_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_PANE_ITEM.get())
            output.accept(EKItems.WOGOR_WOOD_BUTTON_ITEM.get())
            output.accept(EKItems.WOGOR_SIGN_ITEM.get())
            output.accept(EKItems.WOGOR_HANGING_SIGN_ITEM.get())
            output.accept(EKItems.WOGOR_BOAT.get())
            output.accept(EKItems.WOGOR_CHEST_BOAT.get())
            output.accept(EKItems.WOHLON_MUD_ITEM.get())
            output.accept(EKItems.WOHLON_MOSS_ITEM.get())
            output.accept(EKItems.WOHLON_MOSS_CARPET_ITEM.get())
            output.accept(EKItems.WOHLON_ROOT_ITEM.get())
            output.accept(EKItems.WOHLON_GRASS_ITEM.get())
            output.accept(EKItems.WOHLON_FERN_ITEM.get())
            output.accept(EKItems.WOHLON_TALL_FERN_ITEM.get())
            output.accept(EKItems.WOHLON_LICHEN_ITEM.get())
            output.accept(EKItems.WOHLON_REED_ITEM.get())
            output.accept(EKItems.WOHLON_LILY_PAD_ITEM.get())
            output.accept(EKItems.WOHLON_DUCKWEED_ITEM.get())
            output.accept(EKItems.WOHLON_LEAF_LITTER_ITEM.get())
            output.accept(EKItems.WOHLON_HANGING_ROOTS_ITEM.get())
            output.accept(EKItems.BINDING_ROOTS_ITEM.get())
            output.accept(EKItems.WOHLON_LUMPY_MUSHROOM_ITEM.get())
            output.accept(EKItems.WOHLON_CUP_MUSHROOM_ITEM.get())
            output.accept(EKItems.WOHLON_LUMPY_MUSHROOM_BLOCK_ITEM.get())
            output.accept(EKItems.WOHLON_CUP_MUSHROOM_BLOCK_ITEM.get())
            output.accept(EKItems.WOHLON_MUSHROOM_STEM_ITEM.get())
            output.accept(EKItems.HEARTFIRE_ITEM.get())
            output.accept(EKItems.HEART_CANDLE_ITEM.get())
            output.accept(EKItems.WILLFAER.get())
            output.accept(EKItems.WILLFAER_LANTERN_ITEM.get())
            output.accept(EKItems.WILLFAER_TORCH_ITEM.get())
            output.accept(EKItems.HEART_OF_THE_WILD_ITEM.get())
            output.accept(EKItems.CRYSTAL_PEDESTAL_ITEM.get())
            output.accept(EKItems.GUARDIAN_SPIKE_ITEM.get())
            output.accept(EKItems.BLESSED_RAZOR.get())
            output.accept(EKItems.WOGGAK_VINE.get())
            output.accept(EKItems.WEHELO_BLADE.get())
            output.accept(EKItems.WAND_OF_GAMYLAN.get())
            output.accept(EKItems.STAFF_OF_COMMAND.get())
            output.accept(EKItems.STAFF_OF_CONCEALMENT.get())
            output.accept(EKItems.STAFF_OF_DENSITY.get())
            output.accept(EKItems.STAFF_OF_SCALES.get())
            output.accept(EKItems.STAFF_OF_FUSION.get())
            output.accept(EKItems.STAFF_OF_AEGIS.get())
            output.accept(EKItems.STAFF_OF_SUNDERING.get())
            output.accept(EKItems.GRAND_STAFF_OF_DECAY.get())
            output.accept(EKItems.INCANTATION_OF_THE_RIVER.get())
            output.accept(EKItems.BLUE_WITCH_HAT.get())
            output.accept(EKItems.BLUE_WITCH_ROBES.get())
            output.accept(EKItems.BLUE_WITCH_ROBE_BOTTOMS.get())
            output.accept(EKItems.BLUE_WITCH_SANDALS.get())
            output.accept(EKItems.WEYYE_FRUIT.get())
            output.accept(EKItems.RAW_KEKHEKH.get())
            output.accept(EKItems.COOKED_KEKHEKH.get())
            output.accept(EKItems.KEKHEKH_BUCKET.get())
            // One stack for each cave instrument. The instruments are datapack-loaded, so the NBT is a plain id string.
            for (idx in 1..19) {
                val stack = ItemStack(EKItems.PRISMATIC_GOAT_HORN.get())
                stack.orCreateTag.putString("instrument", "enderkinesis:cave_$idx")
                output.accept(stack)
            }

            // Sureibjin
            output.accept(EKItems.CROWN_UNTESTED.get())
            output.accept(EKItems.CROWN_OF_THE_DARK_STAR.get())

            // General: materials, tools
            output.accept(EKItems.MUNDANE_ORE_ITEM.get())
            output.accept(EKItems.MUNDANE_STONE.get())
            output.accept(EKItems.ULDER_CITRINE.get())
            output.accept(EKItems.ULDER_ONYX.get())
            output.accept(EKItems.ULDER_JADE.get())
            output.accept(EKItems.ULDER_AQUAMARINE.get())
            output.accept(EKItems.ULDER_MOONSTONE.get())
            output.accept(EKItems.ULDER_SPINEL.get())
            output.accept(EKItems.ULDER_BLOODSTONE.get())
            output.accept(EKItems.ULDER_CITRINE_SHARD.get())
            output.accept(EKItems.ULDER_ONYX_SHARD.get())
            output.accept(EKItems.ULDER_JADE_SHARD.get())
            output.accept(EKItems.ULDER_AQUAMARINE_SHARD.get())
            output.accept(EKItems.ULDER_MOONSTONE_SHARD.get())
            output.accept(EKItems.ULDER_SPINEL_SHARD.get())
            output.accept(EKItems.ULDER_BLOODSTONE_SHARD.get())
            output.accept(EKItems.SILVERPOINT.get())
        }
        // Mob eggs go in vanilla's Spawn Eggs tab, where players look for them.
        CreativeTabRegistry.modify(CreativeTabRegistry.defer(CreativeModeTabs.SPAWN_EGGS)) { _, output, _ ->
            output.accept(EKItems.IRREVERENT_REMNANT_SPAWN_EGG.get())
            output.accept(EKItems.CATALOGER_SPAWN_EGG.get())
            output.accept(EKItems.ARCHIVE_SPAWN_EGG.get())
            output.accept(EKItems.SSELITH_MOTH_SPAWN_EGG.get())
            output.accept(EKItems.CONSTRILIAR_SPAWN_EGG.get())
            output.accept(EKItems.SHULKONAUT_SPAWN_EGG.get())
            output.accept(EKItems.SHILLEAGK_DEER_SPAWN_EGG.get())
            output.accept(EKItems.KEKHEKH_SPAWN_EGG.get())
            output.accept(EKItems.EAUHORRANA_SPAWN_EGG.get())
            output.accept(EKItems.MOSKUO_SPAWN_EGG.get())
            output.accept(EKItems.WILL_O_THE_WISP_SPAWN_EGG.get())
        }
    }
}
