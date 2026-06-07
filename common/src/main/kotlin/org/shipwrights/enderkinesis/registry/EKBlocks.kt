package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChainBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.MapColor
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.AetherPadBlock
import org.shipwrights.enderkinesis.block.AncriteBarsBlock
import org.shipwrights.enderkinesis.block.AncriteBeamBlock
import org.shipwrights.enderkinesis.block.CrepusculiteGlassBlock
import org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock
import org.shipwrights.enderkinesis.block.EnderAstrolabeBlock
import org.shipwrights.enderkinesis.block.HeartCandleBlock
import org.shipwrights.enderkinesis.block.HeartOfTheWildBlock
import org.shipwrights.enderkinesis.block.OrbOfLinkingBlock
import org.shipwrights.enderkinesis.block.PlanarAnchorBlock
import org.shipwrights.enderkinesis.block.ShulkerPufferBlock
import org.shipwrights.enderkinesis.block.WogorBudBlock

/**
 * All blocks added by Enderkinesis. Registered through Architectury so a single definition
 * works on both Fabric and Forge.
 */
object EKBlocks {
    val BLOCKS: DeferredRegister<Block> = DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.BLOCK)

    private fun crepusculiteProps() = BlockBehaviour.Properties.of()
        .mapColor(MapColor.COLOR_PURPLE)
        .requiresCorrectToolForDrops()
        .strength(4.5f, 9.0f)
        .sound(SoundType.AMETHYST)

    /** Crepusculite ore — only spawns inside end meteorite geodes. */
    val CREPUSCULITE_ORE: RegistrySupplier<Block> = BLOCKS.register("crepusculite_ore") {
        Block(crepusculiteProps().strength(5.0f, 9.0f))
    }

    /** Crepusculite block — ore-storage form, 9 raw crepusculite compressed. */
    val CREPUSCULITE_BLOCK: RegistrySupplier<Block> = BLOCKS.register("crepusculite_block") {
        Block(crepusculiteProps())
    }

    /** Crepusculite glass — translucent crepusculite-tinted glass. Forms the hexagonal crystal
     *  shells of geodes (replacing what used to be magenta stained glass) and the outer cube of
     *  the Void Harness. Extends [net.minecraft.world.level.block.HalfTransparentBlock] (vanilla
     *  glass / stained-glass base class) so its `skipRendering` override culls the internal
     *  faces between adjacent crepusculite-glass blocks.
     *
     *  Properties mirror vanilla glass exactly — `Properties.copy` carries scalar fields like
     *  `canOcclude`, but the four state-predicate fields (`isValidSpawn`, `isRedstoneConductor`,
     *  `isSuffocating`, `isViewBlocking`) are reset to defaults assuming an opaque full cube on
     *  every `Properties.copy` call, so they have to be re-applied explicitly to actually get
     *  glass behaviour for spawning, redstone, suffocation, and light propagation. */
    val CREPUSCULITE_GLASS: RegistrySupplier<Block> = BLOCKS.register("crepusculite_glass") {
        CrepusculiteGlassBlock(
            BlockBehaviour.Properties.copy(Blocks.GLASS)
                .mapColor(MapColor.COLOR_PURPLE)
                .sound(SoundType.GLASS)
                .noOcclusion()
                .randomTicks()
                .lightLevel { state -> if (state.getValue(CrepusculiteGlassBlock.LIT)) 4 else 1 }
                .isValidSpawn { _, _, _, _ -> false }
                .isRedstoneConductor { _, _, _ -> false }
                .isSuffocating { _, _, _ -> false }
                .isViewBlocking { _, _, _ -> false }
        )
    }

    /** Crepusculite lattice — sits at the centre of the geode and projects a virtual ocean. */
    val CREPUSCULITE_LATTICE: RegistrySupplier<Block> = BLOCKS.register("crepusculite_lattice") {
        CrepusculiteLatticeBlock(
            crepusculiteProps().strength(3.0f, 6.0f).noOcclusion().lightLevel { state ->
                CrepusculiteLatticeBlock.lightFor(state.getValue(BlockStateProperties.POWER))
            }
        )
    }

    /** Ender Astrolabe — moves a ship (and crew) between dimensions. */
    val ENDER_ASTROLABE: RegistrySupplier<Block> = BLOCKS.register("ender_astrolabe") {
        EnderAstrolabeBlock(crepusculiteProps().strength(3.5f, 7.0f).noOcclusion())
    }

    /** Void Harness — when powered, applies an XZ-plane pull force toward the harness's
     *  position (on its own ship if ship-resident, or on every ship within 32 blocks if
     *  world-placed). Stained-glass shell with an animated end crystal inside. */
    val VOID_HARNESS: RegistrySupplier<Block> = BLOCKS.register("void_harness") {
        org.shipwrights.enderkinesis.block.VoidHarnessBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_MAGENTA)
                .strength(2.0f, 6.0f)
                .sound(net.minecraft.world.level.block.SoundType.GLASS)
                // Stained-glass cube with an inner crystal — not a full opaque cube; without
                // `noOcclusion` adjacent blocks' facing sides get culled. Same fix as on the
                // anchor.
                .noOcclusion()
                // Light emission scales with redstone power: 0 → unlit, 15 → light level 7.
                // Integer-divided to step in pairs of POWER (0–2 → 0, 3–4 → 1, … 14–15 → 7).
                .lightLevel { state ->
                    state.getValue(org.shipwrights.enderkinesis.block.VoidHarnessBlock.POWER) * 7 / 15
                }
        )
    }

    /** Planar Anchor — when powered on a ship, pins this block to its world position via a VS2
     *  spherical joint; the ship freely rotates around it. */
    val PLANAR_ANCHOR: RegistrySupplier<Block> = BLOCKS.register("planar_anchor") {
        PlanarAnchorBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_MAGENTA)
                .requiresCorrectToolForDrops()
                .strength(3.5f, 8.0f)
                .sound(SoundType.STONE)
                // Pillar + thin top/bottom slabs — not a full cube. Without [noOcclusion] the
                // adjacent block's facing face is culled (MC treats the anchor as a full
                // occluder) and you see straight through that neighbour via the gaps between
                // the anchor's slab faces.
                .noOcclusion()
        )
    }

    /** Ancrite Chain — vanilla [ChainBlock] subclass with redstone-wire-style power transmission
     *  along its axis (one-block decay per segment, no wire visual connection). Brightness,
     *  emission and particles all scale with [org.shipwrights.enderkinesis.block.AncriteChainBlock.POWER],
     *  mirroring vanilla redstone dust but with an ender-green palette. */
    val ANCRITE_CHAIN: RegistrySupplier<Block> = BLOCKS.register("ancrite_chain") {
        org.shipwrights.enderkinesis.block.AncriteChainBlock(
            BlockBehaviour.Properties.copy(Blocks.CHAIN)
                // Constant block-light emission of 4 — matches vanilla redstone wire's "actually
                // emits nothing" reality while keeping the chain's always-on faint glow. Visual
                // emissivity per POWER is handled in the texture/model, not in block-light.
                .lightLevel { 4 }
        )
    }

    // --- Ancrite (ender metal) ---------------------------------------------------------------
    // Family: storage block, raw block, refined block, full-block grate + bars (cutout), plus
    // stair/slab/wall cuts from the storage block. Iron-pickaxe tier, copper sound.
    private fun ancriteProps() = BlockBehaviour.Properties.of()
        .mapColor(MapColor.WARPED_NYLIUM)
        .requiresCorrectToolForDrops()
        .strength(5.0f, 6.0f)
        .sound(SoundType.COPPER)

    val ANCRITE_BLOCK: RegistrySupplier<Block> = BLOCKS.register("ancrite_block") {
        Block(ancriteProps())
    }
    val RAW_ANCRITE: RegistrySupplier<Block> = BLOCKS.register("raw_ancrite") {
        Block(ancriteProps())
    }
    val REFINED_ANCRITE: RegistrySupplier<Block> = BLOCKS.register("refined_ancrite") {
        Block(ancriteProps())
    }
    val ANCRITE_STAIRS: RegistrySupplier<Block> = BLOCKS.register("ancrite_stairs") {
        StairBlock(ANCRITE_BLOCK.get().defaultBlockState(), ancriteProps())
    }
    val ANCRITE_SLAB: RegistrySupplier<Block> = BLOCKS.register("ancrite_slab") {
        SlabBlock(ancriteProps())
    }
    val ANCRITE_WALL: RegistrySupplier<Block> = BLOCKS.register("ancrite_wall") {
        WallBlock(ancriteProps())
    }
    val REFINED_ANCRITE_STAIRS: RegistrySupplier<Block> = BLOCKS.register("refined_ancrite_stairs") {
        StairBlock(REFINED_ANCRITE.get().defaultBlockState(), ancriteProps())
    }
    val REFINED_ANCRITE_SLAB: RegistrySupplier<Block> = BLOCKS.register("refined_ancrite_slab") {
        SlabBlock(ancriteProps())
    }
    val REFINED_ANCRITE_WALL: RegistrySupplier<Block> = BLOCKS.register("refined_ancrite_wall") {
        WallBlock(ancriteProps())
    }
    /** Full-block grate. Cutout texture (the holes are 1-bit alpha) — needs `noOcclusion` so
     *  neighbours don't cull their own faces against it, and a cutout render type registered
     *  client-side. */
    val ANCRITE_GRATE: RegistrySupplier<Block> = BLOCKS.register("ancrite_grate") {
        Block(ancriteProps().noOcclusion())
    }
    /** Iron-bars-style pane block using the ancrite-grate texture. Also connects to ancrite
     *  chain endcaps along the chain's axis — see [AncriteBarsBlock]. */
    val ANCRITE_BARS: RegistrySupplier<Block> = BLOCKS.register("ancrite_bars") {
        AncriteBarsBlock(ancriteProps().noOcclusion())
    }
    /** Ancrite Beam — pillar-style block, same 8×16×8 footprint as the wall post. Placed by
     *  the geode generator beyond the crystal's width to extend the spike to the geode shell. */
    val ANCRITE_BEAM: RegistrySupplier<Block> = BLOCKS.register("ancrite_beam") {
        AncriteBeamBlock(ancriteProps().noOcclusion())
    }

    /** Aether Pad — directional hover thruster. Front face is crepusculite; other five faces
     *  wear purpur pillar. Pushes entities forward up to 8 blocks; when its `FACING` ray
     *  finds ground within 2 blocks, applies an opposite-direction lift to the host ship
     *  (ground-effect hovercraft). Tuned for 4-pad / ~48 t hover vehicles by default. */
    val AETHER_PAD: RegistrySupplier<Block> = BLOCKS.register("aether_pad") {
        AetherPadBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_MAGENTA)
                .requiresCorrectToolForDrops()
                .strength(3.0f, 6.0f)
                .sound(SoundType.AMETHYST),
        )
    }

    /** Shulker Puffer — directional thruster. Redstone signal scales thrust (0–15); dragon's
     *  breath in its single fuel slot quadruples force and densifies the exhaust visual.
     *  Implementation lives in [ShulkerPufferBlockEntity]; pattern follows
     *  [VoidHarnessBlockEntity] (self-registering [BlockEntityPhysicsListener]). */
    val SHULKER_PUFFER: RegistrySupplier<Block> = BLOCKS.register("shulker_puffer") {
        ShulkerPufferBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_MAGENTA)
                .requiresCorrectToolForDrops()
                .strength(3.0f, 6.0f)
                .sound(SoundType.STONE),
        )
    }

    /** Purpur Ballast — a dense trim block; its mass is overridden to 2500 kg via the
     *  `data/valkyrienskies/vs_mass` datapack. */
    val PURPUR_BALLAST: RegistrySupplier<Block> = BLOCKS.register("purpur_ballast") {
        Block(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_MAGENTA)
                .requiresCorrectToolForDrops()
                .strength(3.0f, 8.0f)
                .sound(SoundType.STONE)
        )
    }

    /** Orb of Linking — the generic substrate the whole tome suite uses. Holds zero or more
     *  links per tome in either direction (see [org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity]);
     *  any tome (Signal, Binding, Chaining, future) can claim it without conflicting with
     *  the others. Pickaxe-mineable, modest blast resistance. Glow scales with
     *  [OrbOfLinkingBlock.POWER] (set by the Signal tome's incoming-aggregate) so a Signal
     *  receiver visibly brightens with the level it's mirroring; other tomes leave it at 0. */
    val ORB_OF_LINKING: RegistrySupplier<Block> = BLOCKS.register("orb_of_linking") {
        OrbOfLinkingBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_MAGENTA)
                .strength(2.5f, 6.0f)
                .sound(SoundType.AMETHYST)
                .noOcclusion()
                // Cap at 7 so the orb is visibly emissive at full power without acting as a
                // free torch.
                .lightLevel { state -> state.getValue(OrbOfLinkingBlock.POWER) * 7 / 15 }
        )
    }

    /** Wogor Bud — the seedling stage the Heart of the Wild plants
     *  on a face. Advances small → medium → almost-full via random
     *  ticks, then matures into a Wogor Wood log whose axis
     *  matches the bud's growth direction. See [WogorBudBlock]. */
    val WOGOR_BUD: RegistrySupplier<Block> = BLOCKS.register("wogor_bud") {
        WogorBudBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BROWN)
                .strength(0.2f, 0.5f)
                .sound(SoundType.AZALEA)
                .noOcclusion()
                .noCollission()
                .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
        )
    }

    /** Heart Candle — single-only candle. Behaves like a vanilla
     *  candle (lights with flint+steel, extinguishes with empty-hand
     *  right-click while LIT, waterloggable, projectile-extinguish)
     *  but rejects all stacking attempts so only one candle ever
     *  occupies the block. See [HeartCandleBlock] for the
     *  one-line override that achieves that, and
     *  `data/minecraft/tags/blocks/candles.json` /
     *  `tags/items/candles.json` for the tag entries that let
     *  vanilla's flint+steel and `AbstractCandleBlock.canLight`
     *  recognise this block. */
    val HEART_CANDLE: RegistrySupplier<Block> = BLOCKS.register("heart_candle") {
        HeartCandleBlock(
            BlockBehaviour.Properties.copy(Blocks.CANDLE)
                .lightLevel { state ->
                    if (state.getValue(net.minecraft.world.level.block.CandleBlock.LIT)) 3 else 0
                },
        )
    }

    /** Heart of the Wild — placed block whose presence drives the
     *  [org.shipwrights.enderkinesis.block.HeartOfTheWildManager]
     *  growth queue for its scope. On a ship: the ship gets a Wild
     *  effect. In the [org.shipwrights.enderkinesis.dimension.Wohlonnogondonia]
     *  dimension: extra Hearts add to the always-on baseline rate.
     *  Visually a sea-lantern stand-in until the artist returns a
     *  unique model. */
    val HEART_OF_THE_WILD: RegistrySupplier<Block> = BLOCKS.register("heart_of_the_wild") {
        HeartOfTheWildBlock(
            BlockBehaviour.Properties.copy(Blocks.SEA_LANTERN)
                .mapColor(MapColor.PLANT)
                .strength(0.6f, 1.5f)
                .sound(SoundType.GLASS)
                .lightLevel { 12 }
        )
    }

    /** Sselith Bookshelf — full block whose side faces show the Sselith-tinted
     *  bookshelf texture, with deepslate-tile top + bottom. This is the block
     *  the chunk generator places for every library wall in Sselith's
     *  Repertory (replacing the previous use of [Blocks.BOOKSHELF]). */
    val SSELITH_BOOKSHELF: RegistrySupplier<Block> = BLOCKS.register("sselith_bookshelf") {
        Block(
            BlockBehaviour.Properties.copy(Blocks.BOOKSHELF)
                .mapColor(MapColor.DEEPSLATE)
                .sound(SoundType.DEEPSLATE_TILES)
        )
    }

    // --- Wohlonnogondonia (dismal swamp) -----------------------------------------------------
    // Wogor wood, the trees of the swamp dimension. Mangrove-styled trees use these as the
    // trunk/branches; leaves currently fall back to vanilla mangrove leaves until a Wogor
    // leaves texture is authored.
    private fun wogorWoodProps() = BlockBehaviour.Properties.of()
        .mapColor(MapColor.COLOR_BROWN)
        .strength(2.0f, 3.0f)
        .sound(SoundType.WOOD)

    /** Wogor log — pillar (axis-aware) block of the swamp's Wogor trees. */
    val WOGOR_LOG: RegistrySupplier<Block> = BLOCKS.register("wogor_log") {
        RotatedPillarBlock(wogorWoodProps())
    }

    /** Wogor wood — vanilla's 6-sided bark variant. Same `wogor_log` side
     *  texture on every face (no end-grain). This is the block the
     *  chunk-generator paints for thick trunks, branches and roots, where
     *  exposed log ends would otherwise show ring textures and break the
     *  bark continuity. */
    val WOGOR_WOOD: RegistrySupplier<Block> = BLOCKS.register("wogor_wood") {
        RotatedPillarBlock(wogorWoodProps())
    }

    /** Wogor planks — generic plank block. */
    val WOGOR_PLANKS: RegistrySupplier<Block> = BLOCKS.register("wogor_planks") {
        Block(wogorWoodProps())
    }

    fun register() = BLOCKS.register()
}
