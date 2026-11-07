package org.shipwrights.enderkinesis.registry

import dev.architectury.core.item.ArchitecturySpawnEggItem
import dev.architectury.core.item.ArchitecturyBucketItem
import dev.architectury.core.item.ArchitecturyMobBucketItem
import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.TagKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.DoubleHighBlockItem
import net.minecraft.world.item.HangingSignItem
import net.minecraft.world.item.PlaceOnWaterBlockItem
import net.minecraft.world.item.SignItem
import net.minecraft.world.item.Instrument
import net.minecraft.world.item.InstrumentItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.RecordItem
import net.minecraft.world.item.Rarity
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.material.Fluids
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.AlmanacOfEverywhereItem
import org.shipwrights.enderkinesis.item.BlessedRazorItem
import org.shipwrights.enderkinesis.item.CrepusculiteCharmItem
import org.shipwrights.enderkinesis.item.CrownOfTheDarkStarItem
import org.shipwrights.enderkinesis.item.SilverpointItem
import org.shipwrights.enderkinesis.item.LedgerOfHuntingPincersItem
import org.shipwrights.enderkinesis.item.LedgerOfWatchingEyesItem
import org.shipwrights.enderkinesis.item.ShellOfTidesItem
import org.shipwrights.enderkinesis.item.UlderGemItem
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.item.UlderShardItem
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.item.EideticBookmarkItem
import org.shipwrights.enderkinesis.item.OldPaperItem
import org.shipwrights.enderkinesis.item.SatchelOfShelvingItem
import org.shipwrights.enderkinesis.item.SselithBookItem
import org.shipwrights.enderkinesis.item.RobeArmorMaterial
import org.shipwrights.enderkinesis.item.ScalingRobeArmorItem
import net.minecraft.world.item.ArmorItem
import org.shipwrights.enderkinesis.item.ScrollOfSculkCatastropheItem
import org.shipwrights.enderkinesis.item.ScrollOfUnravellingItem
import org.shipwrights.enderkinesis.item.StrikeReadoutItem
import org.shipwrights.enderkinesis.item.StaffOfAegisItem
import org.shipwrights.enderkinesis.item.StaffOfCommandItem
import org.shipwrights.enderkinesis.item.StaffOfConcealmentItem
import org.shipwrights.enderkinesis.item.StaffOfDensityItem
import org.shipwrights.enderkinesis.item.StaffOfFusionItem
import org.shipwrights.enderkinesis.item.StaffOfScalesItem
import org.shipwrights.enderkinesis.item.GrandStaffOfDreadItem
import org.shipwrights.enderkinesis.item.GrandStaffOfDecayItem
import org.shipwrights.enderkinesis.item.GrandStaffOfTheDeadItem
import org.shipwrights.enderkinesis.item.StaffOfSunderingItem
import org.shipwrights.enderkinesis.item.TomeOfBindingItem
import org.shipwrights.enderkinesis.item.TomeOfChainingItem
import org.shipwrights.enderkinesis.item.TomeOfCouplingItem
import org.shipwrights.enderkinesis.item.TomeOfElasticityItem
import org.shipwrights.enderkinesis.item.TomeOfSignalItem
import org.shipwrights.enderkinesis.item.TomeOfSpringItem
import org.shipwrights.enderkinesis.item.TomeOfDisintegrationItem
import org.shipwrights.enderkinesis.item.TomeOfFilteringItem
import org.shipwrights.enderkinesis.item.TomeOfHingingItem
import org.shipwrights.enderkinesis.item.TomeOfPullingItem
import org.shipwrights.enderkinesis.item.TomeOfPushingItem
import org.shipwrights.enderkinesis.item.TomeOfScryingItem
import org.shipwrights.enderkinesis.item.TomeOfTransportationItem
import org.shipwrights.enderkinesis.item.TomeOfVacuumItem
import org.shipwrights.enderkinesis.item.TomeOfVentriloquismItem
import org.shipwrights.enderkinesis.item.WaybreadItem
import org.shipwrights.enderkinesis.item.WeyyeFruitItem
import org.shipwrights.enderkinesis.item.WogorBoatItem
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

    /** Rod of Levitation. Right-click an entity → Levitation I for 4 s. Right-click a
     *  block → assemble as a 1×1×1 ship that hovers 2 blocks up for 4 s then dissolves
     *  back to a [net.minecraft.world.entity.item.FallingBlockEntity]. Left-click a ship
     *  block → off-COM nudge in the player's look direction. See
     *  [org.shipwrights.enderkinesis.item.RodOfLevitationItem]. */
    val ROD_OF_LEVITATION: RegistrySupplier<Item> = ITEMS.register("rod_of_levitation") {
        org.shipwrights.enderkinesis.item.RodOfLevitationItem(props().stacksTo(1))
    }

    /** Wand of Ga'Mylan — captures a right-clicked entity into a
     *  [org.shipwrights.enderkinesis.block.GamylanEntityCageBlock] on a
     *  static VS2 ship, encased in a pink-glass crystal shell. See
     *  [org.shipwrights.enderkinesis.item.WandOfGamylanItem]. */
    val WAND_OF_GAMYLAN: RegistrySupplier<Item> = ITEMS.register("wand_of_gamylan") {
        org.shipwrights.enderkinesis.item.WandOfGamylanItem(props().stacksTo(1))
    }

    /** Lodestar Dart — homing projectile. Right-click to fire in look direction; the primary
     *  use case is from a dispenser. Stacks normally. See
     *  [org.shipwrights.enderkinesis.item.LodestarDartItem] and
     *  [org.shipwrights.enderkinesis.entity.LodestarDartEntity]. */
    val LODESTAR_DART: RegistrySupplier<Item> = ITEMS.register("lodestar_dart") {
        org.shipwrights.enderkinesis.item.LodestarDartItem(props().stacksTo(16))
    }

    /** Crepusculite Charm — passive levitation. While in the holder's inventory and the
     *  jump key is held, lifts the player up to 5 blocks above the ground beneath them
     *  (past their normal jump height). Has durability that drains while airborne in the
     *  charm's envelope; accepts Unbreaking + Mending via the standard
     *  [EnchantmentCategory.BREAKABLE] gate. See [CrepusculiteCharmClient]. */
    val CREPUSCULITE_CHARM: RegistrySupplier<Item> =
        ITEMS.register("crepusculite_charm") {
            CrepusculiteCharmItem(props().stacksTo(1).durability(CrepusculiteCharmItem.MAX_DURABILITY))
        }

    val CROWN_OF_THE_DARK_STAR: RegistrySupplier<Item> =
        ITEMS.register("crown_of_the_dark_star") { CrownOfTheDarkStarItem(props().stacksTo(1)) }

    /** Ledger of Watching Eyes — slot-companion for the Eyeroscope. While in the
     *  eyeroscope's slot, logs every ship that enters a 64-block cube around the
     *  eyeroscope (excluding the host and ships jointed to it). Right-clicking it in
     *  hand opens the book viewer with the current log; the NBT carries both the
     *  sighting list and a rendered `pages` tag so the vanilla
     *  [WrittenBookItem.use] path can render it without us writing a custom GUI. */
    val LEDGER_OF_WATCHING_EYES: RegistrySupplier<Item> =
        ITEMS.register("ledger_of_watching_eyes") {
            LedgerOfWatchingEyesItem(props().stacksTo(1))
        }

    /** Ledger of Hunting Pincers — same shape as [LEDGER_OF_WATCHING_EYES] but for
     *  `LivingEntity` targets (mobs, players, animals). The eyeroscope BE skips the UUID
     *  of whichever player placed the ledger so the holder doesn't end up tracked. */
    val LEDGER_OF_HUNTING_PINCERS: RegistrySupplier<Item> =
        ITEMS.register("ledger_of_hunting_pincers") {
            LedgerOfHuntingPincersItem(props().stacksTo(1))
        }

    val PARALLELOGRAPH: RegistrySupplier<Item> =
        ITEMS.register("parallelograph") {
            org.shipwrights.enderkinesis.item.ParallelographItem(props().stacksTo(1))
        }

    /** Ancrite scrap — raw item form of the ender-metal "ancrite". */
    val ANCRITE_SCRAP: RegistrySupplier<Item> = ITEMS.register("ancrite_scrap") { Item(props()) }

    val YGANN_ACUUL: RegistrySupplier<Item> = ITEMS.register("ygann_acuul") { Item(props()) }

    /** Wey'ye fruit — fully restores hunger + saturation (even when
     *  full) and irreversibly inflicts the permanent Ambrosial Craving
     *  debuff. See [WeyyeFruitItem]. */
    val WEYYE_FRUIT: RegistrySupplier<Item> =
        ITEMS.register("weyye_fruit") { WeyyeFruitItem(props()) }

    // Salmon food values.
    val RAW_KEKHEKH: RegistrySupplier<Item> = ITEMS.register("raw_kekhekh") {
        Item(props().food(FoodProperties.Builder().nutrition(2).saturationMod(0.1f).build()))
    }
    val COOKED_KEKHEKH: RegistrySupplier<Item> = ITEMS.register("cooked_kekhekh") {
        Item(props().food(FoodProperties.Builder().nutrition(6).saturationMod(0.8f).build()))
    }
    val KEKHEKH_BUCKET: RegistrySupplier<Item> = ITEMS.register("kekhekh_bucket") {
        ArchitecturyMobBucketItem(
            EKEntities.KEKHEKH,
            { Fluids.WATER },
            { SoundEvents.BUCKET_EMPTY_FISH },
            props().stacksTo(1),
        )
    }

    /** Java-callable accessor for [WEYYE_FRUIT.get()] — used by client
     *  and server code that compares stack items without bouncing
     *  through the Kotlin object's instance getter. */
    @JvmStatic
    fun weyyeFruit(): Item = WEYYE_FRUIT.get()

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

    val SHELL_OF_TIDES: RegistrySupplier<Item> =
        ITEMS.register("shell_of_tides") { ShellOfTidesItem(props().stacksTo(1)) }

    /** Irreverent Remnant "spawn egg" — testing tool that drops a single
     *  blob of the pseudo-entity. Not a real SpawnEggItem (there's no
     *  EntityType behind the remnant); the egg look comes from the vanilla
     *  template model plus an item-colour handler. */
    val IRREVERENT_REMNANT_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("irreverent_remnant_spawn_egg") {
            org.shipwrights.enderkinesis.item.IrreverentRemnantSpawnEggItem(props())
        }

    /** Incantation of Winds — hold right-click to blast ships in a wide box
     *  ahead of the player forward on the wind. 1 XP/s in survival. Renders
     *  as an empty hand with rising sselith dust. */
    val INCANTATION_OF_WINDS: RegistrySupplier<Item> =
        ITEMS.register("incantation_of_winds") {
            org.shipwrights.enderkinesis.item.IncantationOfWindsItem(props().stacksTo(1))
        }

    val INCANTATION_OF_THE_RIVER: RegistrySupplier<Item> =
        ITEMS.register("incantation_of_the_river") {
            org.shipwrights.enderkinesis.item.IncantationOfTheRiverItem(props().stacksTo(1))
        }

    val INCANTATION_OF_THE_ABYSS: RegistrySupplier<Item> =
        ITEMS.register("incantation_of_the_abyss") {
            org.shipwrights.enderkinesis.item.IncantationOfTheAbyssItem(props().stacksTo(1))
        }

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

    /** Tome of Scrying — single-use tome that converts an Orb of Linking into an
     *  [org.shipwrights.enderkinesis.block.OrbOfScryingBlock]. The scrying orb chunkloads
     *  its own chunk and is right-clickable to view through to the nearest other scrying
     *  orb in the player's look direction. */
    val TOME_OF_SCRYING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_scrying") { TomeOfScryingItem(props().stacksTo(1)) }

    /** Tome of Ventriloquism — sounds within 7 blocks of a SEND orb are mirrored to every
     *  linked RECEIVE orb at the same relative offset. Channels mirrored: BLOCKS, HOSTILE,
     *  NEUTRAL, PLAYERS, RECORDS, VOICE. MASTER/MUSIC/WEATHER/AMBIENT pass through unchanged. */
    val TOME_OF_VENTRILOQUISM: RegistrySupplier<Item> =
        ITEMS.register("tome_of_ventriloquism") { TomeOfVentriloquismItem(props().stacksTo(1)) }

    /** Tome of Spring — true 1D linear spring (VSSpringJoint) between two orbs. Bodies
     *  oscillate around the rest length captured at link time; rotation is free. Refuses
     *  world↔world and same-ship. */
    val TOME_OF_SPRING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_spring") { TomeOfSpringItem(props().stacksTo(1)) }

    /** Tome of Coupling — strong distance joint (VSDistanceJoint with min == max). Holds
     *  orbs at an exact constant distance; rotation around the joining axis is free.
     *  A rigid coupling rod. Refuses world↔world and same-ship. */
    val TOME_OF_COUPLING: RegistrySupplier<Item> =
        ITEMS.register("tome_of_coupling") { TomeOfCouplingItem(props().stacksTo(1)) }

    // Ulder Staves — powerful single-purpose tools. Each placeholder for now; their
    // models reference `minecraft:item/iron_sword` so they look like swords in hand and
    // hotbar until artwork lands. Behaviour added one staff at a time (see tasks).
    val STAFF_OF_COMMAND: RegistrySupplier<Item> =
        ITEMS.register("staff_of_command") { StaffOfCommandItem(props().stacksTo(1)) }
    val STAFF_OF_CONCEALMENT: RegistrySupplier<Item> =
        ITEMS.register("staff_of_concealment") { StaffOfConcealmentItem(props().stacksTo(1)) }
    val STAFF_OF_DENSITY: RegistrySupplier<Item> =
        ITEMS.register("staff_of_density") { StaffOfDensityItem(props().stacksTo(1)) }
    val STAFF_OF_SCALES: RegistrySupplier<Item> =
        ITEMS.register("staff_of_scales") { StaffOfScalesItem(props().stacksTo(1)) }
    val STAFF_OF_FUSION: RegistrySupplier<Item> =
        ITEMS.register("staff_of_fusion") { StaffOfFusionItem(props().stacksTo(1)) }
    val STAFF_OF_AEGIS: RegistrySupplier<Item> =
        ITEMS.register("staff_of_aegis") { StaffOfAegisItem(props().stacksTo(1)) }
    val STAFF_OF_SUNDERING: RegistrySupplier<Item> =
        ITEMS.register("staff_of_sundering") { StaffOfSunderingItem(props().stacksTo(1)) }
    val GRAND_STAFF_OF_THE_DEAD: RegistrySupplier<Item> =
        ITEMS.register("grand_staff_of_the_dead") { GrandStaffOfTheDeadItem(props().stacksTo(1)) }
    val GRAND_STAFF_OF_DREAD: RegistrySupplier<Item> =
        ITEMS.register("grand_staff_of_dread") { GrandStaffOfDreadItem(props().stacksTo(1)) }
    val GRAND_STAFF_OF_DECAY: RegistrySupplier<Item> =
        ITEMS.register("grand_staff_of_decay") { GrandStaffOfDecayItem(props().stacksTo(1)) }

    private fun shard(name: String, element: Element): RegistrySupplier<Item> =
        ITEMS.register("ulder_${name}_shard") { UlderShardItem(element, props().fireResistant()) }

    val ULDER_CITRINE_SHARD: RegistrySupplier<Item> = shard("citrine", Element.PYRE)
    val ULDER_ONYX_SHARD: RegistrySupplier<Item> = shard("onyx", Element.GRAVITY)
    val ULDER_JADE_SHARD: RegistrySupplier<Item> = shard("jade", Element.GENESIS)
    val ULDER_AQUAMARINE_SHARD: RegistrySupplier<Item> = shard("aquamarine", Element.WINTER)
    val ULDER_MOONSTONE_SHARD: RegistrySupplier<Item> = shard("moonstone", Element.QUINTESSENCE)
    val ULDER_SPINEL_SHARD: RegistrySupplier<Item> = shard("spinel", Element.CHAOS)
    val ULDER_BLOODSTONE_SHARD: RegistrySupplier<Item> = shard("bloodstone", Element.BLOOD)

    private val SHARDS: Map<Element, RegistrySupplier<Item>> by lazy {
        mapOf(
            Element.PYRE to ULDER_CITRINE_SHARD, Element.GRAVITY to ULDER_ONYX_SHARD,
            Element.GENESIS to ULDER_JADE_SHARD, Element.WINTER to ULDER_AQUAMARINE_SHARD,
            Element.QUINTESSENCE to ULDER_MOONSTONE_SHARD, Element.CHAOS to ULDER_SPINEL_SHARD,
            Element.BLOOD to ULDER_BLOODSTONE_SHARD,
        )
    }

    /** Null for the fusions and Sundering, which have no inscribable shard. */
    fun shardOf(element: Element): Item? = SHARDS[element]?.get()

    /** A fusion is never inscribed as a shard. These only draw its glyph on the altar. */
    private fun fusionShard(name: String): RegistrySupplier<Item> = ITEMS.register("ulder_${name}_shard") { Item(props()) }

    val ULDER_SUNFIRE_SHARD: RegistrySupplier<Item> = fusionShard("sunfire")
    val ULDER_CORRUPTION_SHARD: RegistrySupplier<Item> = fusionShard("corruption")
    val ULDER_VOID_SHARD: RegistrySupplier<Item> = fusionShard("void")

    private val FUSION_SHARDS: Map<Element, RegistrySupplier<Item>> by lazy {
        mapOf(Element.SUNFIRE to ULDER_SUNFIRE_SHARD, Element.CORRUPTION to ULDER_CORRUPTION_SHARD, Element.VOID to ULDER_VOID_SHARD)
    }

    /** Tinted from [NBT_SHARD_ELEMENT]. Only draws a glyph that has no shard (Sundering). */
    val ULDER_SHARD: RegistrySupplier<Item> = ITEMS.register("ulder_shard") { Item(props()) }

    const val NBT_SHARD_ELEMENT = "Element"

    /** Sunfire glyphs use the bright yellow of its shard. Its [Element.color] stays the same elsewhere. */
    fun glyphTint(element: Element): Int = if (element == Element.SUNFIRE) SUNFIRE_GLYPH else element.color

    private const val SUNFIRE_GLYPH = 0xFFE03A

    fun glyphStack(element: Element): ItemStack {
        (shardOf(element) ?: FUSION_SHARDS[element]?.get())?.let { return ItemStack(it) }
        return ItemStack(ULDER_SHARD.get()).apply { orCreateTag.putString(NBT_SHARD_ELEMENT, element.id) }
    }

    private fun gem(name: String, element: Element, shard: RegistrySupplier<Item>): RegistrySupplier<Item> =
        ITEMS.register("ulder_$name") { UlderGemItem(element, shard::get, props().stacksTo(1).fireResistant()) }

    val ULDER_CITRINE: RegistrySupplier<Item> = gem("citrine", Element.PYRE, ULDER_CITRINE_SHARD)
    val ULDER_ONYX: RegistrySupplier<Item> = gem("onyx", Element.GRAVITY, ULDER_ONYX_SHARD)
    val ULDER_JADE: RegistrySupplier<Item> = gem("jade", Element.GENESIS, ULDER_JADE_SHARD)
    val ULDER_AQUAMARINE: RegistrySupplier<Item> = gem("aquamarine", Element.WINTER, ULDER_AQUAMARINE_SHARD)
    val ULDER_MOONSTONE: RegistrySupplier<Item> = gem("moonstone", Element.QUINTESSENCE, ULDER_MOONSTONE_SHARD)
    val ULDER_SPINEL: RegistrySupplier<Item> = gem("spinel", Element.CHAOS, ULDER_SPINEL_SHARD)
    val ULDER_BLOODSTONE: RegistrySupplier<Item> = gem("bloodstone", Element.BLOOD, ULDER_BLOODSTONE_SHARD)

    val ULDER_SCROLL: RegistrySupplier<Item> =
        ITEMS.register("ulder_scroll") { UlderScrollItem(props().stacksTo(1)) }

    /** Realm robe sets — three families (End Cult / Scholar / Blue Witch) × four slots
     *  (helmet / chest / legs / boots). All share [ScalingRobeArmorItem] which derives
     *  ARMOR + ARMOR_TOUGHNESS from the stack's enchantments, walking from "just below
     *  leather" at zero enchantments up to 1.5× netherite at the cap. Each family has
     *  its own [RobeArmorMaterial] so the worn-armor renderer pulls the matching
     *  `{realm}_layer_{1,2}.png` from `assets/enderkinesis/textures/models/armor/`. */
    private fun robe(name: String, mat: RobeArmorMaterial, type: ArmorItem.Type): RegistrySupplier<Item> =
        ITEMS.register(name) { ScalingRobeArmorItem(mat, type, props().stacksTo(1)) }

    val END_CULT_HOOD: RegistrySupplier<Item> = robe("end_cult_hood", RobeArmorMaterial.END_CULT, ArmorItem.Type.HELMET)
    val END_CULT_ROBES: RegistrySupplier<Item> = robe("end_cult_robes", RobeArmorMaterial.END_CULT, ArmorItem.Type.CHESTPLATE)
    val END_CULT_ROBE_BOTTOMS: RegistrySupplier<Item> = robe("end_cult_robe_bottoms", RobeArmorMaterial.END_CULT, ArmorItem.Type.LEGGINGS)
    val END_CULT_SHOES: RegistrySupplier<Item> = robe("end_cult_shoes", RobeArmorMaterial.END_CULT, ArmorItem.Type.BOOTS)

    val SCHOLAR_HOOD: RegistrySupplier<Item> = robe("scholar_hood", RobeArmorMaterial.SCHOLAR, ArmorItem.Type.HELMET)
    val SCHOLAR_ROBES: RegistrySupplier<Item> = robe("scholar_robes", RobeArmorMaterial.SCHOLAR, ArmorItem.Type.CHESTPLATE)
    val SCHOLAR_ROBE_BOTTOMS: RegistrySupplier<Item> = robe("scholar_robe_bottoms", RobeArmorMaterial.SCHOLAR, ArmorItem.Type.LEGGINGS)
    val SCHOLAR_SHOES: RegistrySupplier<Item> = robe("scholar_shoes", RobeArmorMaterial.SCHOLAR, ArmorItem.Type.BOOTS)

    val BLUE_WITCH_HAT: RegistrySupplier<Item> = robe("blue_witch_hat", RobeArmorMaterial.BLUE_WITCH, ArmorItem.Type.HELMET)
    val BLUE_WITCH_ROBES: RegistrySupplier<Item> = robe("blue_witch_robes", RobeArmorMaterial.BLUE_WITCH, ArmorItem.Type.CHESTPLATE)
    val BLUE_WITCH_ROBE_BOTTOMS: RegistrySupplier<Item> = robe("blue_witch_robe_bottoms", RobeArmorMaterial.BLUE_WITCH, ArmorItem.Type.LEGGINGS)
    val BLUE_WITCH_SANDALS: RegistrySupplier<Item> = robe("blue_witch_sandals", RobeArmorMaterial.BLUE_WITCH, ArmorItem.Type.BOOTS)

    /** Scroll of Unravelling — single-use on a lectern bearing a Sselith book,
     *  reveals 34.5% of its remaining Sselith back to English at the cost of
     *  two levels of Sselith madness. Master-librarian trade only.
     *  See [ScrollOfUnravellingItem]. */
    val SCROLL_OF_UNRAVELLING: RegistrySupplier<Item> =
        ITEMS.register("scroll_of_unravelling") {
            ScrollOfUnravellingItem(props().rarity(Rarity.RARE))
        }

    /** Scroll of Sculk Catastrophe — single-use right-click consumable that
     *  sculk-converts the player's immediate surroundings. Generates on the
     *  lectern in the `sselith_specimen_sculk` structure; see
     *  [ScrollOfSculkCatastropheItem]. */
    val SCROLL_OF_SCULK_CATASTROPHE: RegistrySupplier<Item> =
        ITEMS.register("scroll_of_sculk_catastrophe") {
            ScrollOfSculkCatastropheItem(props().stacksTo(1).rarity(Rarity.RARE))
        }

    val OLD_PAPER: RegistrySupplier<Item> =
        ITEMS.register("old_paper") { OldPaperItem(props().stacksTo(16)) }

    val SSELITH_BOOK: RegistrySupplier<Item> =
        ITEMS.register("sselith_book") { SselithBookItem(props()) }

    val WAYBREAD: RegistrySupplier<Item> =
        ITEMS.register("waybread") { WaybreadItem(props()) }

    val BLESSED_RAZOR: RegistrySupplier<Item> =
        ITEMS.register("blessed_razor") { BlessedRazorItem(props().stacksTo(1)) }

    val SILVERPOINT: RegistrySupplier<Item> =
        ITEMS.register("silverpoint") { SilverpointItem(props().stacksTo(1)) }

    val STRIKE_READOUT: RegistrySupplier<Item> =
        ITEMS.register("strike_readout") { StrikeReadoutItem(props().stacksTo(1)) }

    val WEHELO_BLADE: RegistrySupplier<Item> = ITEMS.register("wehelo_blade") {
        org.shipwrights.enderkinesis.item.WeheloBladeItem(props())
    }

    val EIDETIC_BOOKMARK: RegistrySupplier<Item> =
        ITEMS.register("eidetic_bookmark") { EideticBookmarkItem(props().stacksTo(1)) }

    val SATCHEL_OF_SHELVING: RegistrySupplier<Item> =
        ITEMS.register("satchel_of_shelving") { SatchelOfShelvingItem(props().stacksTo(1)) }

    val PENFEATHER_OF_THE_ACCURSED_OWL: RegistrySupplier<Item> =
        ITEMS.register("penfeather_of_the_accursed_owl") { Item(props().durability(org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity.PENFEATHER_DURABILITY)) }

    val ICHAERIC_INK_BOTTLE: RegistrySupplier<Item> =
        ITEMS.register("ichaeric_ink_bottle") { Item(props().stacksTo(16)) }

    val ICHAERIC_INK_BUCKET: RegistrySupplier<Item> = ITEMS.register("ichaeric_ink_bucket") {
        ArchitecturyBucketItem({ EKFluids.ICHAERIC_INK.get() }, props().craftRemainder(Items.BUCKET).stacksTo(1))
    }

    val ICHAERIC_INK_JAR_ITEM = blockItem("ichaeric_ink_jar", EKBlocks.ICHAERIC_INK_JAR)

    val INEGRIC_RETORT_ITEM = blockItem("inegric_retort", EKBlocks.INEGRIC_RETORT)

    val SCRIBES_DESK_ITEM = blockItem("scribes_desk", EKBlocks.SCRIBES_DESK)
    val OARSCRIBE_ITEM = blockItem("oarscribe", EKBlocks.OARSCRIBE)
    val JAR_RECEPTACLE_ITEM = blockItem("jar_receptacle", EKBlocks.JAR_RECEPTACLE)

    val FORGOTTEN_ITEM_ITEM: RegistrySupplier<Item> = ITEMS.register("forgotten_item") {
        org.shipwrights.enderkinesis.item.ForgottenItemBlockItem(EKBlocks.FORGOTTEN_ITEM.get(), props())
    }

    /** Scroll of Animation — consumed on a purpur block to animate it into a
     *  Constriliar tamed to the user. See [ScrollOfAnimationItem]. */
    val SCROLL_OF_ANIMATION: RegistrySupplier<Item> =
        ITEMS.register("scroll_of_animation") {
            org.shipwrights.enderkinesis.item.ScrollOfAnimationItem(props().rarity(Rarity.RARE))
        }

    val CREPUSCULITE_ORE_ITEM = blockItem("crepusculite_ore", EKBlocks.CREPUSCULITE_ORE)
    val CREPUSCULITE_BLOCK_ITEM = blockItem("crepusculite_block", EKBlocks.CREPUSCULITE_BLOCK)
    val CREPUSCULITE_GLASS_ITEM = blockItem("crepusculite_glass", EKBlocks.CREPUSCULITE_GLASS)
    val CREPUSCULITE_LATTICE_ITEM = blockItem("crepusculite_lattice", EKBlocks.CREPUSCULITE_LATTICE)
    val CREPUSCULITE_LIGHTSTONE_ITEM = blockItem("crepusculite_lightstone", EKBlocks.CREPUSCULITE_LIGHTSTONE)
    val ENDER_ASTROLABE_ITEM = blockItem("ender_astrolabe", EKBlocks.ENDER_ASTROLABE)
    val LODESTAR_LAUNCHER_ITEM = blockItem("lodestar_launcher", EKBlocks.LODESTAR_LAUNCHER)
    val ECHO_CANNON_ITEM = blockItem("echo_cannon", EKBlocks.ECHO_CANNON)
    val EYEROSCOPE_ITEM = blockItem("eyeroscope", EKBlocks.EYEROSCOPE)
    val PURPUR_BALLAST_ITEM = blockItem("purpur_ballast", EKBlocks.PURPUR_BALLAST)
    val ENDER_LINKAGE_ITEM = ITEMS.register("ender_linkage") {
        org.shipwrights.enderkinesis.item.EnderLinkageBlockItem(EKBlocks.ENDER_LINKAGE.get(), props())
    }
    val PLANAR_ANCHOR_ITEM = blockItem("planar_anchor", EKBlocks.PLANAR_ANCHOR)
    val ANCRITE_CHAIN_ITEM = blockItem("ancrite_chain", EKBlocks.ANCRITE_CHAIN)
    val ANCRITE_EYE_ITEM = blockItem("analog_eye", EKBlocks.ANCRITE_EYE)
    val VOID_HARNESS_ITEM = blockItem("void_harness", EKBlocks.VOID_HARNESS)
    val VOID_HOOK_ITEM = blockItem("void_hook", EKBlocks.VOID_HOOK)
    val SHULKER_PUFFER_ITEM = blockItem("shulker_puffer", EKBlocks.SHULKER_PUFFER)
    val SHULKER_STRUT_ITEM = blockItem("shulker_strut", EKBlocks.SHULKER_STRUT)
    val AETHER_PAD_ITEM = blockItem("aether_pad", EKBlocks.AETHER_PAD)

    val ANCRITE_BLOCK_ITEM = blockItem("ancrite_block", EKBlocks.ANCRITE_BLOCK)
    val CRYSTAL_PEDESTAL_ITEM = blockItem("crystal_pedestal", EKBlocks.CRYSTAL_PEDESTAL)
    val ALTAR_OF_ILLUMINATION_ITEM = blockItem("altar_of_illumination", EKBlocks.ALTAR_OF_ILLUMINATION)
    val WOHLON_ROOT_ITEM = blockItem("wohlon_root", EKBlocks.WOHLON_ROOT)
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
    val SSELITH_LANTERN_ITEM = blockItem("sselith_lantern", EKBlocks.SSELITH_LANTERN)
    val SSELITH_LAMP_ITEM = blockItem("sselith_lamp", EKBlocks.SSELITH_LAMP)
    val OWL_DEEPSLATE_ITEM = blockItem("owl_deepslate", EKBlocks.OWL_DEEPSLATE)
    val SSELITH_LADDER_ITEM = blockItem("sselith_ladder", EKBlocks.SSELITH_LADDER)
    val SSELITH_TRAPDOOR_ITEM = blockItem("sselith_trapdoor", EKBlocks.SSELITH_TRAPDOOR)
    val SSELITH_LECTERN_ITEM = blockItem("sselith_lectern", EKBlocks.SSELITH_LECTERN)
    val ORB_OF_LINKING_ITEM = blockItem("orb_of_linking", EKBlocks.ORB_OF_LINKING)
    val ORB_OF_SCRYING_ITEM = blockItem("orb_of_scrying", EKBlocks.ORB_OF_SCRYING)
    val QUASAR_OF_PURITY_ITEM = ITEMS.register("quasar_of_purity") { org.shipwrights.enderkinesis.item.QuasarOfPurityBlockItem(EKBlocks.QUASAR_OF_PURITY.get(), props()) }
    val ULDER_BEAM_ANCHOR_ITEM = ITEMS.register("ulder_beam_anchor") {
        org.shipwrights.enderkinesis.item.UlderBeamAnchorItem(EKBlocks.ULDER_BEAM_ANCHOR.get(), props())
    }
    val HEART_OF_THE_WILD_ITEM = ITEMS.register("heart_of_the_wild") { org.shipwrights.enderkinesis.item.HeartOfTheWildBlockItem(EKBlocks.HEART_OF_THE_WILD.get(), props()) }
    val CRYSTAL_EXPLOSIVE_ITEM = blockItem("crystal_explosive", EKBlocks.CRYSTAL_EXPLOSIVE)
    val HEART_CANDLE_ITEM = blockItem("heart_candle", EKBlocks.HEART_CANDLE)
    val GUARDIAN_SPIKE_ITEM = ITEMS.register("guardian_spike") {
        org.shipwrights.enderkinesis.item.GuardianSpikeItem(EKBlocks.GUARDIAN_SPIKE.get(), props())
    }

    val WOGOR_LOG_ITEM = blockItem("wogor_log", EKBlocks.WOGOR_LOG)
    val WOGOR_WOOD_ITEM = blockItem("wogor_wood", EKBlocks.WOGOR_WOOD)
    val WOGOR_PLANKS_ITEM = blockItem("wogor_planks", EKBlocks.WOGOR_PLANKS)
    val WOGOR_LEAVES_ITEM = blockItem("wogor_leaves", EKBlocks.WOGOR_LEAVES)
    val WOGOR_STAIRS_ITEM = blockItem("wogor_stairs", EKBlocks.WOGOR_STAIRS)
    val WOGOR_SLAB_ITEM = blockItem("wogor_slab", EKBlocks.WOGOR_SLAB)
    val WOGOR_FENCE_ITEM = blockItem("wogor_fence", EKBlocks.WOGOR_FENCE)
    val WOGOR_FENCE_GATE_ITEM = blockItem("wogor_fence_gate", EKBlocks.WOGOR_FENCE_GATE)
    val WOGOR_PRESSURE_PLATE_ITEM = blockItem("wogor_pressure_plate", EKBlocks.WOGOR_PRESSURE_PLATE)
    val WOGOR_BUTTON_ITEM = blockItem("wogor_button", EKBlocks.WOGOR_BUTTON)
    val WOGOR_WOOD_STAIRS_ITEM = blockItem("wogor_wood_stairs", EKBlocks.WOGOR_WOOD_STAIRS)
    val WOGOR_WOOD_SLAB_ITEM = blockItem("wogor_wood_slab", EKBlocks.WOGOR_WOOD_SLAB)
    val WOGOR_WOOD_FENCE_ITEM = blockItem("wogor_wood_fence", EKBlocks.WOGOR_WOOD_FENCE)
    val WOGOR_WOOD_WALL_ITEM = blockItem("wogor_wood_wall", EKBlocks.WOGOR_WOOD_WALL)
    val WOGOR_WOOD_PANE_ITEM = blockItem("wogor_wood_pane", EKBlocks.WOGOR_WOOD_PANE)
    val WOGOR_WOOD_BUTTON_ITEM = blockItem("wogor_wood_button", EKBlocks.WOGOR_WOOD_BUTTON)
    val STRIPPED_WOGOR_LOG_ITEM = blockItem("stripped_wogor_log", EKBlocks.STRIPPED_WOGOR_LOG)
    val STRIPPED_WOGOR_WOOD_ITEM = blockItem("stripped_wogor_wood", EKBlocks.STRIPPED_WOGOR_WOOD)
    val WOGOR_DOOR_ITEM = ITEMS.register("wogor_door") { DoubleHighBlockItem(EKBlocks.WOGOR_DOOR.get(), props()) }
    val WOGOR_TRAPDOOR_ITEM = blockItem("wogor_trapdoor", EKBlocks.WOGOR_TRAPDOOR)
    val WOGOR_SIGN_ITEM = ITEMS.register("wogor_sign") {
        SignItem(props().stacksTo(16), EKBlocks.WOGOR_SIGN.get(), EKBlocks.WOGOR_WALL_SIGN.get())
    }
    val WOGOR_HANGING_SIGN_ITEM = ITEMS.register("wogor_hanging_sign") {
        HangingSignItem(EKBlocks.WOGOR_HANGING_SIGN.get(), EKBlocks.WOGOR_WALL_HANGING_SIGN.get(), props().stacksTo(16))
    }
    val WOGOR_BOAT = ITEMS.register("wogor_boat") { WogorBoatItem(false, props().stacksTo(1)) }
    val WOGOR_CHEST_BOAT = ITEMS.register("wogor_chest_boat") { WogorBoatItem(true, props().stacksTo(1)) }

    val WOHLON_MUD_ITEM = blockItem("wohlon_mud", EKBlocks.WOHLON_MUD)
    val WOHLON_MOSS_ITEM = blockItem("wohlon_moss", EKBlocks.WOHLON_MOSS)
    val WOHLON_MOSS_CARPET_ITEM = blockItem("wohlon_moss_carpet", EKBlocks.WOHLON_MOSS_CARPET)
    val WOHLON_FERN_ITEM = blockItem("wohlon_fern", EKBlocks.WOHLON_FERN)
    val WOHLON_GRASS_ITEM = blockItem("wohlon_grass", EKBlocks.WOHLON_GRASS)
    val WOHLON_LICHEN_ITEM = blockItem("wohlon_lichen", EKBlocks.WOHLON_LICHEN)
    val WOHLON_REED_ITEM =
        ITEMS.register("wohlon_reed") { DoubleHighBlockItem(EKBlocks.WOHLON_REED.get(), props()) }
    val WOHLON_LILY_PAD_ITEM =
        ITEMS.register("wohlon_lily_pad") { PlaceOnWaterBlockItem(EKBlocks.WOHLON_LILY_PAD.get(), props()) }
    val WOHLON_DUCKWEED_ITEM =
        ITEMS.register("wohlon_duckweed") { PlaceOnWaterBlockItem(EKBlocks.WOHLON_DUCKWEED.get(), props()) }
    val WOHLON_LEAF_LITTER_ITEM = blockItem("wohlon_leaf_litter", EKBlocks.WOHLON_LEAF_LITTER)
    val HEARTFIRE_ITEM = blockItem("heartfire", EKBlocks.HEARTFIRE)
    val WOHLON_HANGING_ROOTS_ITEM = blockItem("wohlon_hanging_roots", EKBlocks.WOHLON_HANGING_ROOTS)
    val WOHLON_LUMPY_MUSHROOM_ITEM = blockItem("wohlon_lumpy_mushroom", EKBlocks.WOHLON_LUMPY_MUSHROOM)
    val WOHLON_CUP_MUSHROOM_ITEM = blockItem("wohlon_cup_mushroom", EKBlocks.WOHLON_CUP_MUSHROOM)
    val WOHLON_LUMPY_MUSHROOM_BLOCK_ITEM = blockItem("wohlon_lumpy_mushroom_block", EKBlocks.WOHLON_LUMPY_MUSHROOM_BLOCK)
    val WOHLON_CUP_MUSHROOM_BLOCK_ITEM = blockItem("wohlon_cup_mushroom_block", EKBlocks.WOHLON_CUP_MUSHROOM_BLOCK)
    val WOHLON_MUSHROOM_STEM_ITEM = blockItem("wohlon_mushroom_stem", EKBlocks.WOHLON_MUSHROOM_STEM)
    val WOHLON_TALL_FERN_ITEM =
        ITEMS.register("wohlon_tall_fern") { DoubleHighBlockItem(EKBlocks.WOHLON_TALL_FERN.get(), props()) }
    val WILLFAER: RegistrySupplier<Item> = ITEMS.register("willfaer") { Item(props()) }
    val WILLFAER_LANTERN_ITEM = blockItem("willfaer_lantern", EKBlocks.WILLFAER_LANTERN)
    val WILLFAER_TORCH_ITEM = ITEMS.register("willfaer_torch") {
        net.minecraft.world.item.StandingAndWallBlockItem(
            EKBlocks.WILLFAER_TORCH.get(), EKBlocks.WILLFAER_WALL_TORCH.get(), props(), net.minecraft.core.Direction.DOWN,
        )
    }

    /** Binding Roots block item — places the pillar block, which on
     *  player-placement registers itself with `BindingRootsMerger`'s scanner.
     *  See [org.shipwrights.enderkinesis.block.BindingRootsBlock]. */
    val BINDING_ROOTS_ITEM = blockItem("binding_roots", EKBlocks.BINDING_ROOTS)

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

    /** Archive spawn egg — paper-cream primary with sselith warm-yellow trim,
     *  matching the dust spiral the tornado emits in-world. */
    val ARCHIVE_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("archive_spawn_egg") {
            ArchitecturySpawnEggItem(
                EKEntities.ARCHIVE,
                0xE8DEB8,
                0xD9C45B,
                props(),
            )
        }

    /** Sselith Moth spawn egg — dusty warm-yellow body with darker brown trim
     *  matching the moth's wing tones. */
    val SSELITH_MOTH_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("sselith_moth_spawn_egg") {
            ArchitecturySpawnEggItem(
                EKEntities.SSELITH_MOTH,
                0xCAAD53,
                0x5C4A29,
                props(),
            )
        }

    /** Constriliar spawn egg — plate purple with pale-straw legs, sampled from the
     *  entity texture. Custom subclass so the spawner becomes the owner immediately. */
    val CONSTRILIAR_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("constriliar_spawn_egg") {
            org.shipwrights.enderkinesis.item.ConstriliarSpawnEggItem(
                EKEntities.CONSTRILIAR,
                0x9A5CB5,
                0xE3DCA8,
                props(),
            )
        }

    /** Shulkonaut spawn egg — shulker-shell mauve over parchment. */
    val SHULKONAUT_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("shulkonaut_spawn_egg") {
            dev.architectury.core.item.ArchitecturySpawnEggItem(
                EKEntities.SHULKONAUT,
                0x8D6E9E,
                0xEDE6C8,
                props(),
            )
        }

    /** Olive coat over teal branch veins, sampled from the deer texture. */
    val SHILLEAGK_DEER_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("shilleagk_deer_spawn_egg") {
            dev.architectury.core.item.ArchitecturySpawnEggItem(
                EKEntities.SHILLEAGK_DEER,
                0x5A502C,
                0x35484C,
                props(),
            )
        }

    /** Dark teal body with blood-red spots, sampled from the Moskuo texture. */
    val MOSKUO_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("moskuo_spawn_egg") {
            ArchitecturySpawnEggItem(EKEntities.MOSKUO, 0x1B3239, 0x4A0000, props())
        }

    /** Dark olive scales over blue spots, sampled from the Eauhorrana texture. */
    val EAUHORRANA_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("eauhorrana_spawn_egg") {
            ArchitecturySpawnEggItem(EKEntities.EAUHORRANA, 0x343018, 0x327DB9, props())
        }

    /** Rust-brown scales over blue spots, sampled from the Kekhekh texture. */
    val KEKHEKH_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("kekhekh_spawn_egg") {
            ArchitecturySpawnEggItem(EKEntities.KEKHEKH, 0x56260F, 0x347096, props())
        }

    /** Yellow-green halo and white-yellow core of the flame. */
    val WILL_O_THE_WISP_SPAWN_EGG: RegistrySupplier<Item> =
        ITEMS.register("will_o_the_wisp_spawn_egg") {
            dev.architectury.core.item.ArchitecturySpawnEggItem(
                EKEntities.WILL_O_THE_WISP,
                0x9EE64D,
                0xFFFCE0,
                props(),
            )
        }

    /** Statue block-items — decorative statues rendered by the
     *  StatueBlockEntityRenderer. The in-world view is the Blockbench model;
     *  the inventory icon is a plain `item/generated` texture (the same atlas
     *  the model samples) for now. */
    val STATUE_STEVE_ITEM = ITEMS.register("statue_steve") { org.shipwrights.enderkinesis.item.StatueBlockItem(EKBlocks.STATUE_STEVE.get(), props()) }
    val STATUE_CATALOGER_ITEM = ITEMS.register("statue_cataloger") { org.shipwrights.enderkinesis.item.StatueBlockItem(EKBlocks.STATUE_CATALOGER.get(), props()) }
    val STATUE_TENTACLES_ITEM = ITEMS.register("statue_tentacles") { org.shipwrights.enderkinesis.item.StatueBlockItem(EKBlocks.STATUE_TENTACLES.get(), props()) }
    val STATUE_TENTACLED_BEAST_ITEM = ITEMS.register("statue_tentacled_beast") { org.shipwrights.enderkinesis.item.StatueBlockItem(EKBlocks.STATUE_TENTACLED_BEAST.get(), props()) }
    val STATUE_YELLOW_TOWER_ITEM = ITEMS.register("statue_yellow_tower") { org.shipwrights.enderkinesis.item.StatueBlockItem(EKBlocks.STATUE_YELLOW_TOWER.get(), props()) }
    val STATUE_BLACK_GOAT_ITEM = ITEMS.register("statue_black_goat") { org.shipwrights.enderkinesis.item.StatueBlockItem(EKBlocks.STATUE_BLACK_GOAT.get(), props()) }
    val STATUE_COUNTING_ITEM = ITEMS.register("statue_counting") { org.shipwrights.enderkinesis.item.StatueBlockItem(EKBlocks.STATUE_COUNTING.get(), props()) }
    val STATUE_OWL_ITEM = ITEMS.register("statue_owl") { org.shipwrights.enderkinesis.item.StatueBlockItem(EKBlocks.STATUE_OWL.get(), props()) }

    val STATUE_ITEMS: List<RegistrySupplier<out Item>> = listOf(
        STATUE_STEVE_ITEM, STATUE_CATALOGER_ITEM, STATUE_TENTACLES_ITEM,
        STATUE_TENTACLED_BEAST_ITEM, STATUE_YELLOW_TOWER_ITEM, STATUE_BLACK_GOAT_ITEM,
        STATUE_COUNTING_ITEM, STATUE_OWL_ITEM,
    )

    /** BlockItem for each Moth Cloth colour. Registered as a Map keyed by
     *  DyeColor so tab-population, lang lookup, and any downstream consumer
     *  can iterate cleanly. */
    val MOTH_CLOTH_ITEMS_BY_COLOR: Map<net.minecraft.world.item.DyeColor, RegistrySupplier<out Item>> =
        EKBlocks.MOTH_CLOTH_BY_COLOR.mapValues { (color, blockSup) ->
            blockItem("moth_cloth_${color.name.lowercase()}", blockSup)
        }

    fun register() = ITEMS.register()
}
