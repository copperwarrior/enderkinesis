package org.shipwrights.enderkinesis.registry

import dev.architectury.core.item.ArchitecturySpawnEggItem
import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Instrument
import net.minecraft.world.item.InstrumentItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.RecordItem
import net.minecraft.world.item.Rarity
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.AlmanacOfEverywhereItem
import org.shipwrights.enderkinesis.item.GuideToStratusItem
import org.shipwrights.enderkinesis.item.TomeOfBindingItem
import org.shipwrights.enderkinesis.item.TomeOfChainingItem
import org.shipwrights.enderkinesis.item.TomeOfElasticityItem
import org.shipwrights.enderkinesis.item.TomeOfSignalItem
import org.shipwrights.enderkinesis.item.TomeOfDisintegrationItem
import org.shipwrights.enderkinesis.item.TomeOfFilteringItem
import org.shipwrights.enderkinesis.item.TomeOfHingingItem
import org.shipwrights.enderkinesis.item.TomeOfPullingItem
import org.shipwrights.enderkinesis.item.TomeOfPushingItem
import org.shipwrights.enderkinesis.item.TomeOfTransportationItem
import org.shipwrights.enderkinesis.item.TomeOfVacuumItem
import org.shipwrights.enderkinesis.item.WyllandTomeItem

/**
 * All items added by Enderkinesis, including the [BlockItem]s for [EKBlocks].
 */
object EKItems {
    val ITEMS: DeferredRegister<Item> = DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.ITEM)

    private fun props() = Item.Properties()

    private fun blockItem(name: String, block: RegistrySupplier<net.minecraft.world.level.block.Block>) =
        ITEMS.register(name) { BlockItem(block.get(), props()) }

    /** Raw crepusculite, smelted/used in crafting. */
    val CREPUSCULITE: RegistrySupplier<Item> = ITEMS.register("crepusculite") { Item(props()) }

    /** Ancrite scrap — raw item form of the ender-metal "ancrite". */
    val ANCRITE_SCRAP: RegistrySupplier<Item> = ITEMS.register("ancrite_scrap") { Item(props()) }

    /** The Almanac of Everywhere — obtained by right-clicking a lattice with a book. */
    val ALMANAC_OF_EVERYWHERE: RegistrySupplier<Item> =
        ITEMS.register("almanac_of_everywhere") { AlmanacOfEverywhereItem(props().stacksTo(1)) }

    /** Wylland Tome — Sselith "gravity gun": left-click + hold grabs ships /
     *  entities and drags them around. See [WyllandTomeItem]. */
    val WYLLAND_TOME: RegistrySupplier<Item> =
        ITEMS.register("wylland_tome") {
            // `durability(...)` implies a stack size of 1 and gives the
            // tome a damage bar; it drains while grabbing and is refilled
            // by feeding it written books (see [WyllandTomeItem.use]).
            WyllandTomeItem(props().durability(WyllandTomeItem.MAX_DURABILITY))
        }

    /** Guide to Stratus — right-click a lattice to raise its sea level, left-click to lower it.
     *  See [GuideToStratusItem] and [org.shipwrights.enderkinesis.item.GuideToStratus]. */
    val GUIDE_TO_STRATUS: RegistrySupplier<Item> =
        ITEMS.register("guide_to_stratus") { GuideToStratusItem(props().stacksTo(1)) }

    /** Tome of Signal — right-click orbs of linking to wire one SEND to many RECEIVErs, mirroring
     *  the SEND's neighbouring redstone signal (0–15) to every RECEIVE peer within 64 blocks of
     *  world-space (ship-inclusive). First member of the orb-tome suite. */
    val TOME_OF_SIGNAL: RegistrySupplier<Item> =
        ITEMS.register("tome_of_signal") { TomeOfSignalItem(props().stacksTo(1)) }

    /** Tome of Binding — welds two orbs together with a VSFixedJoint, locking the bodies'
     *  relative pose. World↔world and same-ship pairings are refused; world↔ship pins the
     *  ship in world space, ship↔ship locks the two ships into one rigid combined body. */
    val TOME_OF_BINDING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_binding") { TomeOfBindingItem(props().stacksTo(1)) }

    /** Tome of Chaining — loose VSDistanceJoint (rope) between two orbs. Bodies drift freely
     *  up to the rope's length (locked at link time); beyond that the rope pulls them back.
     *  Refuses world↔world and same-ship like Binding. */
    val TOME_OF_CHAINING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_chaining") { TomeOfChainingItem(props().stacksTo(1)) }

    /** Tome of Elasticity — 6-DOF soft-weld spring (VSFixedJoint with mass-aware compliance).
     *  Translation AND rotation are sprung: bodies pulled or twisted away from the link-time
     *  relative pose feel restoring force. Refuses world↔world and same-ship. */
    val TOME_OF_ELASTICITY: RegistrySupplier<Item> =
        ITEMS.register("tome_of_elasticity") { TomeOfElasticityItem(props().stacksTo(1)) }

    /** Tome of Transportation — moves item stacks between orbs at 1 block/tick. Each SEND
     *  orb pulls one stack per tick from its active-face container, dispatches it to the
     *  next receiver in round-robin order; receiver pushes into its own active-face
     *  container (or drops as item entity if no container). Ghost item visual + purple
     *  particle trail during transit. */
    val TOME_OF_TRANSPORTATION: RegistrySupplier<Item> =
        ITEMS.register("tome_of_transportation") { TomeOfTransportationItem(props().stacksTo(1)) }

    /** Tome of Vacuum — like Transportation, but the source is dropped item entities in a
     *  5×5×3 region around the SEND orb (3 along the active-face axis, 5 perpendicular)
     *  instead of an adjacent container. */
    val TOME_OF_VACUUM: RegistrySupplier<Item> =
        ITEMS.register("tome_of_vacuum") { TomeOfVacuumItem(props().stacksTo(1)) }

    /** Tome of Pulling — Chaining-style rope whose max length contracts with the SEND
     *  orb's active-face redstone signal: power 15 reels the bodies almost together,
     *  power 0 leaves them at their captured rope length. */
    val TOME_OF_PULLING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_pulling") { TomeOfPullingItem(props().stacksTo(1)) }

    /** Tome of Pushing — inverse of Pulling. The SEND orb's active-face signal forces a
     *  minimum separation in blocks: power 0 = no push (rope-like), power 15 = bodies
     *  forced 15+ blocks apart, smooth spring contraction. */
    val TOME_OF_PUSHING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_pushing") { TomeOfPushingItem(props().stacksTo(1)) }

    /** Tome of Filtering — sets the per-orb item filter consulted by item-mover tomes.
     *  Right-click an orb with the tome (item-to-filter in offhand) to set; empty offhand
     *  to clear. */
    val TOME_OF_FILTERING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_filtering") { TomeOfFilteringItem(props().stacksTo(1)) }

    /** Tome of Hinging — rotational hinge between two ship bodies. The orb-to-orb line is
     *  the hinge axis; redstone power on the SEND scales rotation resistance (0 = free
     *  spin, 15 = locked). */
    val TOME_OF_HINGING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_hinging") { TomeOfHingingItem(props().stacksTo(1)) }

    /** Tome of Disintegration — destructive beam from SEND toward each RECEIVE. Mines the
     *  first breakable block in the ray (Create-drill cadence, hardness-scaled), damages
     *  living entities along the unblocked segment, vacuums dropped items in the beam
     *  AABB. Drops flow through the standard mover dispatch into the receiver's container. */
    val TOME_OF_DISINTEGRATION: RegistrySupplier<Item> =
        ITEMS.register("tome_of_disintegration") { TomeOfDisintegrationItem(props().stacksTo(1)) }

    val CREPUSCULITE_ORE_ITEM = blockItem("crepusculite_ore", EKBlocks.CREPUSCULITE_ORE)
    val CREPUSCULITE_BLOCK_ITEM = blockItem("crepusculite_block", EKBlocks.CREPUSCULITE_BLOCK)
    val CREPUSCULITE_GLASS_ITEM = blockItem("crepusculite_glass", EKBlocks.CREPUSCULITE_GLASS)
    val CREPUSCULITE_LATTICE_ITEM = blockItem("crepusculite_lattice", EKBlocks.CREPUSCULITE_LATTICE)
    val ENDER_ASTROLABE_ITEM = blockItem("ender_astrolabe", EKBlocks.ENDER_ASTROLABE)
    val EYEROSCOPE_ITEM = blockItem("eyeroscope", EKBlocks.EYEROSCOPE)
    val PURPUR_BALLAST_ITEM = blockItem("purpur_ballast", EKBlocks.PURPUR_BALLAST)
    val PLANAR_ANCHOR_ITEM = blockItem("planar_anchor", EKBlocks.PLANAR_ANCHOR)
    val ANCRITE_CHAIN_ITEM = blockItem("ancrite_chain", EKBlocks.ANCRITE_CHAIN)
    val VOID_HARNESS_ITEM = blockItem("void_harness", EKBlocks.VOID_HARNESS)
    val SHULKER_PUFFER_ITEM = blockItem("shulker_puffer", EKBlocks.SHULKER_PUFFER)
    val AETHER_PAD_ITEM = blockItem("aether_pad", EKBlocks.AETHER_PAD)

    val ANCRITE_BLOCK_ITEM = blockItem("ancrite_block", EKBlocks.ANCRITE_BLOCK)
    val RAW_ANCRITE_ITEM = blockItem("raw_ancrite", EKBlocks.RAW_ANCRITE)
    val REFINED_ANCRITE_ITEM = blockItem("refined_ancrite", EKBlocks.REFINED_ANCRITE)
    val ANCRITE_STAIRS_ITEM = blockItem("ancrite_stairs", EKBlocks.ANCRITE_STAIRS)
    val ANCRITE_SLAB_ITEM = blockItem("ancrite_slab", EKBlocks.ANCRITE_SLAB)
    val ANCRITE_WALL_ITEM = blockItem("ancrite_wall", EKBlocks.ANCRITE_WALL)
    val REFINED_ANCRITE_STAIRS_ITEM = blockItem("refined_ancrite_stairs", EKBlocks.REFINED_ANCRITE_STAIRS)
    val REFINED_ANCRITE_SLAB_ITEM = blockItem("refined_ancrite_slab", EKBlocks.REFINED_ANCRITE_SLAB)
    val REFINED_ANCRITE_WALL_ITEM = blockItem("refined_ancrite_wall", EKBlocks.REFINED_ANCRITE_WALL)
    val ANCRITE_GRATE_ITEM = blockItem("ancrite_grate", EKBlocks.ANCRITE_GRATE)
    val ANCRITE_BARS_ITEM = blockItem("ancrite_bars", EKBlocks.ANCRITE_BARS)
    val ANCRITE_BEAM_ITEM = blockItem("ancrite_beam", EKBlocks.ANCRITE_BEAM)

    val SSELITH_BOOKSHELF_ITEM = blockItem("sselith_bookshelf", EKBlocks.SSELITH_BOOKSHELF)
    val ORB_OF_LINKING_ITEM = blockItem("orb_of_linking", EKBlocks.ORB_OF_LINKING)
    val HEART_OF_THE_WILD_ITEM = blockItem("heart_of_the_wild", EKBlocks.HEART_OF_THE_WILD)
    val HEART_CANDLE_ITEM = blockItem("heart_candle", EKBlocks.HEART_CANDLE)

    val WOGOR_LOG_ITEM = blockItem("wogor_log", EKBlocks.WOGOR_LOG)
    val WOGOR_WOOD_ITEM = blockItem("wogor_wood", EKBlocks.WOGOR_WOOD)
    val WOGOR_PLANKS_ITEM = blockItem("wogor_planks", EKBlocks.WOGOR_PLANKS)
    val WOGOR_LEAVES_ITEM = blockItem("wogor_leaves", EKBlocks.WOGOR_LEAVES)

    /** Tag of [Instrument] records — the 19 cave-ambience variants
     *  that the prismatic-goat horn picks from. Wired to
     *  `data/enderkinesis/tags/instrument/prismatic_goat_horns.json`,
     *  which lists `enderkinesis:cave_1` through `cave_19`. Used by
     *  both [PRISMATIC_GOAT_HORN]'s tag-based creative variant
     *  enumeration and [org.shipwrights.enderkinesis.entity.PrismaticGoat.createHorn]'s
     *  UUID-seeded random pick. */
    val PRISMATIC_GOAT_HORNS_TAG: TagKey<Instrument> = TagKey.create(
        Registries.INSTRUMENT,
        ResourceLocation(EnderkinesisMod.MOD_ID, "prismatic_goat_horns"),
    )

    /** Prismatic goat horn — single [InstrumentItem] whose specific
     *  cave-ambience variant is keyed by the `instrument` NBT tag
     *  (just like vanilla `goat_horn`: one item, 8 NBT-distinguished
     *  variants → one item, 19 NBT-distinguished variants here).
     *  Dropped by prismatic goats on ram impact; right-click to
     *  broadcast the cave noise across [Instrument.range] blocks
     *  for [Instrument.useDuration] ticks. Stacks to 1 to match
     *  vanilla horn behaviour. */
    val PRISMATIC_GOAT_HORN: RegistrySupplier<Item> =
        ITEMS.register("prismatic_goat_horn") {
            InstrumentItem(props().stacksTo(1).rarity(Rarity.UNCOMMON), PRISMATIC_GOAT_HORNS_TAG)
        }

    /** Music Disc — Mystery. "Enter the Mystery" by Tomato. 120 s
     *  long ⇒ 2400 ticks at 20 tps; back-buffered by
     *  [EKSounds.MUSIC_DISC_MYSTERY]. Comparator output 13 picked
     *  arbitrarily — vanilla discs span 1–15 and this lands in
     *  the unused-by-vanilla range as of 1.20.1. [Rarity.RARE]
     *  matches vanilla discs' tooltip colour. */
    val MUSIC_DISC_MYSTERY: RegistrySupplier<Item> =
        ITEMS.register("music_disc_mystery") {
            RecordItem(
                /* analogOutput = */ 13,
                /* sound        = */ EKSounds.MUSIC_DISC_MYSTERY.get(),
                /* properties   = */ props().stacksTo(1).rarity(Rarity.RARE),
                /* lengthInTicks = */ 2400,
            )
        }

    /** Cataloger spawn egg. Goes into the vanilla **Spawn Eggs** creative
     *  tab, not our mod tab — see [EKCreativeTab]. Colours match the
     *  cataloger's robe (#0D0D0D near-black) and hood trim (#D9C45B
     *  warm yellow). `ArchitecturySpawnEggItem` takes the entity-type
     *  supplier so deferred registration order works on both Fabric
     *  and Forge. */
    val CATALOGER_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("cataloger_spawn_egg") {
            ArchitecturySpawnEggItem(
                EKEntities.CATALOGER,
                0x0D0D0D,
                0xD9C45B,
                props(),
            )
        }

    fun register() = ITEMS.register()
}
