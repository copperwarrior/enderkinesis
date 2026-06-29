package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChainBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SandBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.PressurePlateBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.properties.BlockSetType
import net.minecraft.world.level.block.state.properties.WoodType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.MapColor
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.AetherPadBlock
import org.shipwrights.enderkinesis.block.AncriteBarsBlock
import org.shipwrights.enderkinesis.block.AncriteBeamBlock
import org.shipwrights.enderkinesis.block.CrepusculiteGlassBlock
import org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock
import org.shipwrights.enderkinesis.block.CrystalExplosiveBlock
import org.shipwrights.enderkinesis.block.EnderAstrolabeBlock
import org.shipwrights.enderkinesis.block.MagicMissileLauncherBlock
import org.shipwrights.enderkinesis.block.EyeroscopeBlock
import org.shipwrights.enderkinesis.block.HeartCandleBlock
import org.shipwrights.enderkinesis.block.HeartOfTheWildBlock
import org.shipwrights.enderkinesis.block.OrbOfLinkingBlock
import org.shipwrights.enderkinesis.block.PlanarAnchorBlock
import org.shipwrights.enderkinesis.block.ShulkerPufferBlock
import org.shipwrights.enderkinesis.block.ShulkerStrutBlock
import org.shipwrights.enderkinesis.block.ShulkerStrutTopBlock
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

    /** Magic Missile Launcher — 4×4 slot grid (16 magic missiles) fires one missile per
     *  second along the facing direction while powered. Front-face slot click to insert
     *  / remove manually; top hopper feeds in, bottom hopper pulls out. */
    val MAGIC_MISSILE_LAUNCHER: RegistrySupplier<Block> = BLOCKS.register("magic_missile_launcher") {
        MagicMissileLauncherBlock(crepusculiteProps().strength(3.0f, 6.0f))
    }

    /** Eyeroscope — right-click sets a world-frame target yaw; the BE slowly turns its host
     *  ship to that heading via a PD torque on the physics tick. Until the art pass, this
     *  block reuses the vanilla end-portal-frame look (see `models/block/eyeroscope.json`),
     *  with a levitating, bobbing ender-eye drawn by the BER. */
    val EYEROSCOPE: RegistrySupplier<Block> = BLOCKS.register("eyeroscope") {
        EyeroscopeBlock(
            crepusculiteProps().strength(3.5f, 7.0f)
                // End-portal-frame model isn't a full cube — its top is a partial-height slab,
                // so [noOcclusion] keeps neighbouring blocks from culling their adjacent faces
                // against it (same fix as on the astrolabe / planar anchor).
                .noOcclusion()
        )
    }

    /** Echo Cannon — sculk-faced redstone block. When powered it
     *  charges for 1 s with the warden sonic-charge sound, then fires a
     *  sonic-boom particle beam that reflects off Staff-of-Aegis boxes
     *  (same polyline as the Staff of Sundering), damages every entity
     *  along the beam, and seeds a 200-charge sculk catastrophe at the
     *  terminal hit. */
    val ECHO_CANNON: RegistrySupplier<Block> = BLOCKS.register("echo_cannon") {
        org.shipwrights.enderkinesis.block.EchoCannonBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(3.0f, 6.0f)
                .sound(net.minecraft.world.level.block.SoundType.SCULK_CATALYST)
        )
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

    /** Void Hook — directional, longer-effective-range cousin of the void harness. Pulls
     *  *other* ships within 32 blocks toward the hook's world position, restricted to the
     *  half-space the hook is facing. Works the same whether placed in the world or on a
     *  ship — host ship is excluded from the pull. */
    val VOID_HOOK: RegistrySupplier<Block> = BLOCKS.register("void_hook") {
        org.shipwrights.enderkinesis.block.VoidHookBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_MAGENTA)
                .strength(2.0f, 6.0f)
                .sound(net.minecraft.world.level.block.SoundType.GLASS)
                .noOcclusion()
                .lightLevel { state ->
                    state.getValue(org.shipwrights.enderkinesis.block.VoidHookBlock.POWER) * 7 / 15
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

    /** Ancrite Eye — 16-state analog redstone source. Right-click to step the power
     *  up (shift to step down), left-click empty-handed to jump to max, Staff of
     *  Command dispatch matches the left-click. */
    val ANCRITE_EYE: RegistrySupplier<Block> = BLOCKS.register("analog_eye") {
        org.shipwrights.enderkinesis.block.AncriteEyeBlock(
            BlockBehaviour.Properties.copy(Blocks.LEVER).noOcclusion()
        )
    }

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

    /** Shulker Strut — piston-like extender split across (a) the in-world base block here,
     *  and (b) a 1-block VS2 ship the BE assembles to carry [SHULKER_STRUT_TOP]. A
     *  prismatic joint pulls the ship along `FACING` by POWER × 5 / 15 blocks. The base
     *  block's collision is a half-cube on the side opposite FACING, the top block's
     *  collision is a half-cube on the FACING side — they tile flush at extension=0 so
     *  the closed strut reads as a single block of collision, with no overlap fight. See
     *  [org.shipwrights.enderkinesis.blockentity.ShulkerStrutBlockEntity]. */
    val SHULKER_STRUT: RegistrySupplier<Block> = BLOCKS.register("shulker_strut") {
        ShulkerStrutBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .requiresCorrectToolForDrops()
                .strength(2.0f, 6.0f)
                .sound(SoundType.STONE)
                // The block model is an empty placeholder — the BER draws both shulker
                // shells. `noOcclusion` keeps neighbour faces visible (no model = no
                // occluder anyway, but the property also disables vanilla's full-cube
                // shadow / AO assumptions).
                .noOcclusion()
        )
    }

    /** Lid half of [SHULKER_STRUT]. Hand-placement isn't allowed (no `BlockItem`); the
     *  strut's BE creates it inline + immediately assembles it into a 1-block VS2 ship. */
    val SHULKER_STRUT_TOP: RegistrySupplier<Block> = BLOCKS.register("shulker_strut_top") {
        ShulkerStrutTopBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(2.0f, 6.0f)
                .sound(SoundType.STONE)
                .noOcclusion()
        )
    }

    /** Ender Linkage — fence-shaped six-directional decorative connector. Each connection
     *  direction (NORTH / SOUTH / EAST / WEST / UP / DOWN) is a separate boolean property;
     *  the multipart blockstate assembles per-element sub-models (centre cube, face caps,
     *  half-pipes, and 12 L-bends) on demand. Connects to other linkages and to
     *  neighbours with a sturdy face. Implementation in
     *  [org.shipwrights.enderkinesis.block.EnderLinkageBlock]. */
    val ENDER_LINKAGE: RegistrySupplier<Block> = BLOCKS.register("ender_linkage") {
        org.shipwrights.enderkinesis.block.EnderLinkageBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_MAGENTA)
                // Fragile + impermanent by design: breaks instantly by hand, no tool
                // required, low blast resistance. The linkage is scaffolding the player
                // uses to bridge structurally-disconnected hull pieces during build —
                // it disintegrates on assembly anyway, so a stone-tier hardness would
                // just punish the player for adjusting their layout.
                .strength(0.3f, 0.3f)
                .sound(SoundType.GLASS)
                // Open-frame element — the model only fills the centre core + arms, so
                // neighbour faces would be culled without `noOcclusion`.
                .noOcclusion()
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

    /** Orb of Scrying — converted from an Orb of Linking by the Tome of Scrying. Visually
     *  identical to the unbound orb (same pedestal model); force-loads its own chunk; right-
     *  clickable to view the nearest other scrying orb in the player's look direction. */
    val ORB_OF_SCRYING: RegistrySupplier<Block> = BLOCKS.register("orb_of_scrying") {
        org.shipwrights.enderkinesis.block.OrbOfScryingBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(2.5f, 6.0f)
                .sound(SoundType.AMETHYST)
                .noOcclusion()
                // Dim steady glow so a scrying orb reads as "on" at a glance even without a
                // dynamic POWER property.
                .lightLevel { _ -> 5 }
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
                .lightLevel { state -> if (state.getValue(HeartCandleBlock.LIT)) 3 else 0 },
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
                // Hardness 0.6 = breakable in survival like sea
                // lantern; blast resistance 3_600_000 = bedrock-tier
                // (vanilla 1.20.1 has no per-state explosion hook,
                // so the value is block-wide — both normal AND
                // Mother hearts ignore explosions. The Mother
                // heart's additional indestructibility is the
                // [HeartOfTheWildBlock.getDestroyProgress] override.
                .strength(0.6f, 3_600_000f)
                .sound(SoundType.GLASS)
                .lightLevel { 12 }
        )
    }

    /** Crystal Explosive — crepusculite-glass shell (matching [VOID_HARNESS]'s style)
     *  with an end-crystal model floating inside. Detonates when its host ship takes a
     *  hard collision, with explosion strength scaled to the contact-point impulse so
     *  the bow of a ramming ship goes off harder than the stern. Listens on VS2's
     *  `collisionStartEvent`. World-placed instances never fire (no host ship to
     *  collide). */
    val CRYSTAL_EXPLOSIVE: RegistrySupplier<Block> = BLOCKS.register("crystal_explosive") {
        CrystalExplosiveBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .strength(2.0f, 6.0f)
                .sound(SoundType.GLASS)
                .noOcclusion()
                .lightLevel { 7 }
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

    /** Sselith Lectern — deepslate-tile-tinted lectern that replaces every
     *  vanilla [Blocks.LECTERN] placement the Sselith chunk generator and the
     *  Sselith Ruin templates used to make. Subclasses [net.minecraft.world.level.block.LecternBlock]
     *  so book holding, FACING/HAS_BOOK/POWERED and the BER come for free;
     *  the voxelshape is the only departure (chunkier base — see
     *  [org.shipwrights.enderkinesis.block.SselithLecternBlock]). Vanilla
     *  [net.minecraft.world.level.block.entity.LecternBlockEntity] is reused
     *  unchanged; [org.shipwrights.enderkinesis.EnderkinesisMod] adds this
     *  block to that BE type's `validBlocks` at init via the
     *  [org.shipwrights.enderkinesis.mixin.BlockEntityTypeValidBlocksAccessor]
     *  accessor mixin so saved chunks reload the book intact. */
    val SSELITH_LECTERN: RegistrySupplier<Block> = BLOCKS.register("sselith_lectern") {
        org.shipwrights.enderkinesis.block.SselithLecternBlock(
            BlockBehaviour.Properties.copy(Blocks.LECTERN)
                .mapColor(MapColor.DEEPSLATE)
                .sound(SoundType.DEEPSLATE_TILES)
                // The chunky base isn't a full opaque cube (the back ridge stops at
                // x=11..16 / z=11..16), so without `noOcclusion` adjacent block faces
                // get culled against a non-existent occluder.
                .noOcclusion()
        )
    }

    /** Sselith Ladder — deepslate-textured climbable that survives without a
     *  backing block. Replaces every vanilla `LADDER` placement the chunk
     *  generator emits (library archway shelves, stairwell-flanking columns),
     *  matching the dimension's deepslate palette and removing the support
     *  requirement that procedural placement can't satisfy in open gaps. */
    val SSELITH_LADDER: RegistrySupplier<Block> = BLOCKS.register("sselith_ladder") {
        org.shipwrights.enderkinesis.block.SselithLadderBlock(
            BlockBehaviour.Properties.copy(Blocks.LADDER)
                .mapColor(MapColor.DEEPSLATE)
                .sound(SoundType.DEEPSLATE_TILES)
        )
    }

    /** Sselith Trapdoor — deepslate-textured hinged hatch. Same behaviour as
     *  vanilla wooden trapdoors (right-click to open / close; doesn't need a
     *  backer), but registered as a [net.minecraft.world.level.block.state.properties.BlockSetType.STONE]-typed
     *  trapdoor for the deepslate sound profile and the stone open/close
     *  click. Replaces every [Blocks.BAMBOO_TRAPDOOR] placement the chunk
     *  generator emits (platform connection trapdoors). */
    val SSELITH_TRAPDOOR: RegistrySupplier<Block> = BLOCKS.register("sselith_trapdoor") {
        net.minecraft.world.level.block.TrapDoorBlock(
            BlockBehaviour.Properties.copy(Blocks.BAMBOO_TRAPDOOR)
                .mapColor(MapColor.DEEPSLATE)
                .sound(SoundType.DEEPSLATE_TILES),
            net.minecraft.world.level.block.state.properties.BlockSetType.STONE,
        )
    }

    /** Sselith Lamp — full opaque cube with distinct side / top-bottom
     *  textures, emitting at the same light level as a vanilla ochre
     *  froglight (15). Replaces every [Blocks.OCHRE_FROGLIGHT] placement
     *  the Sselith chunk generator used to make (the bottom-of-archway-
     *  chain hanging block) so the dimension's palette stays Sselith-
     *  themed while keeping the same brightness budget. */
    val SSELITH_LAMP: RegistrySupplier<Block> = BLOCKS.register("sselith_lamp") {
        Block(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.DEEPSLATE)
                .requiresCorrectToolForDrops()
                .strength(1.5f, 6.0f)
                .sound(SoundType.DEEPSLATE_TILES)
                .lightLevel { 15 }
        )
    }

    /** Sselith Lantern — sitting-only lantern variant (no hanging form). Emits
     *  light at the same level as a vanilla lantern and replaces every
     *  [Blocks.LANTERN] placement the Sselith chunk generator used to make. */
    val SSELITH_LANTERN: RegistrySupplier<Block> = BLOCKS.register("sselith_lantern") {
        org.shipwrights.enderkinesis.block.SselithLanternBlock(
            BlockBehaviour.Properties.copy(Blocks.LANTERN)
                .lightLevel { 15 }
                .noOcclusion()
        )
    }

    /** Fractal Projector — placeholder for an unobtainable Sselith Globe
     *  structural block. Bedrock-tier unbreakable, invisible (no render),
     *  pistons can't push it. No BlockItem, no creative entry, no loot — the
     *  block exists only because the globe NBT references it. */
    val FRACTAL_PROJECTOR: RegistrySupplier<Block> = BLOCKS.register("fractal_projector") {
        org.shipwrights.enderkinesis.block.FractalProjectorBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.NONE)
                .strength(-1f, 3600000f)
                .noLootTable()
                .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
                .sound(SoundType.STONE)
                .lightLevel { 15 }
        )
    }

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

    /** **Binding Roots** — cobweb-feeling pillar block, cross-rendered, used
     *  as the anchor for ship-merge into the host body (see
     *  [org.shipwrights.enderkinesis.block.BindingRootsMerger]). Behaves like
     *  vanilla cobweb to entities: no collision, sticky on entry, fall-damage
     *  cleared. Pillar `axis` blockstate so the cross model can rotate to
     *  match placement direction (Y = upright cross, X / Z = cross laid
     *  along the axis). `noOcclusion` keeps the cross visible without solid
     *  shading; `strength(4.0F)` matches vanilla cobweb (sword-fast, shears
     *  fastest); `noLootTable` for now — drops will get a loot-table once
     *  the recipe / drop design lands. */
    val BINDING_ROOTS: RegistrySupplier<Block> = BLOCKS.register("binding_roots") {
        org.shipwrights.enderkinesis.block.BindingRootsBlock(
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .noCollission()
                .noOcclusion()
                .strength(4.0f)
                .sound(SoundType.WET_GRASS)
                .randomTicks()
        )
    }


    /** Wogor wood — vanilla's 6-sided bark variant. Same `wogor_log` side
     *  texture on every face (no end-grain). This is the block the
     *  chunk-generator paints for thick trunks, branches and roots, where
     *  exposed log ends would otherwise show ring textures and break the
     *  bark continuity.
     *
     *  Random ticks drive the [WogorVineNoise]-gated vine spread on
     *  open horizontal side faces. */
    val WOGOR_WOOD: RegistrySupplier<Block> = BLOCKS.register("wogor_wood") {
        org.shipwrights.enderkinesis.block.WogorWoodBlock(wogorWoodProps().randomTicks())
    }

    /** Wogor planks — generic plank block. */
    val WOGOR_PLANKS: RegistrySupplier<Block> = BLOCKS.register("wogor_planks") {
        Block(wogorWoodProps())
    }

    /** Wogor leaves — currently a duplicate of vanilla mangrove leaves.
     *  Copies the mangrove block's `BlockBehaviour.Properties` wholesale
     *  so the decay distance, random-tick rate, suffocation/spawn rules,
     *  and lava-ignite behaviour all match exactly. */
    val WOGOR_LEAVES: RegistrySupplier<Block> = BLOCKS.register("wogor_leaves") {
        org.shipwrights.enderkinesis.block.WogorLeavesBlock(
            BlockBehaviour.Properties.copy(Blocks.MANGROVE_LEAVES)
        )
    }

    // Wogor plank variants. WoodType.OAK / BlockSetType.OAK chosen for the
    // standard wood sound profile and the fence-gate / button / pressure-
    // plate state machinery; registering a dedicated Wogor wood type would
    // only matter once we want distinct open/close clicks.
    val WOGOR_STAIRS: RegistrySupplier<Block> = BLOCKS.register("wogor_stairs") {
        StairBlock(WOGOR_PLANKS.get().defaultBlockState(), wogorWoodProps())
    }
    val WOGOR_SLAB: RegistrySupplier<Block> = BLOCKS.register("wogor_slab") {
        SlabBlock(wogorWoodProps())
    }
    val WOGOR_FENCE: RegistrySupplier<Block> = BLOCKS.register("wogor_fence") {
        FenceBlock(wogorWoodProps())
    }
    val WOGOR_FENCE_GATE: RegistrySupplier<Block> = BLOCKS.register("wogor_fence_gate") {
        FenceGateBlock(wogorWoodProps(), WoodType.OAK)
    }
    val WOGOR_PRESSURE_PLATE: RegistrySupplier<Block> = BLOCKS.register("wogor_pressure_plate") {
        PressurePlateBlock(
            PressurePlateBlock.Sensitivity.EVERYTHING,
            BlockBehaviour.Properties.copy(Blocks.OAK_PRESSURE_PLATE).mapColor(MapColor.COLOR_BROWN),
            BlockSetType.OAK,
        )
    }
    val WOGOR_BUTTON: RegistrySupplier<Block> = BLOCKS.register("wogor_button") {
        ButtonBlock(
            BlockBehaviour.Properties.copy(Blocks.OAK_BUTTON),
            BlockSetType.OAK,
            30,   // ticks pressed — matches vanilla wood buttons
            true, // arrows can press
        )
    }

    // Re-skinned obsidian / crying obsidian / sand used inside the
    // Sureibjin dream coast. Behaviour is identical to the vanilla
    // counterpart — same class, properties copied via Properties.copy
    // so hardness, blast resistance, mining tool, sound type, and map
    // colour all match. Only the texture (and the falling-sand dust
    // colour, sampled from the dream-sand palette) differs.

    /** Dream-coast obsidian — tendrils, rocks, debris. */
    val DREAM_OBSIDIAN: RegistrySupplier<Block> = BLOCKS.register("dream_obsidian") {
        Block(BlockBehaviour.Properties.copy(Blocks.OBSIDIAN))
    }

    /** Dream-coast crying obsidian — interspersed with [DREAM_OBSIDIAN] in
     *  Sureibjin tendrils / rocks for the same patina the vanilla pair has. */
    val DREAM_CRYING_OBSIDIAN: RegistrySupplier<Block> = BLOCKS.register("dream_crying_obsidian") {
        Block(BlockBehaviour.Properties.copy(Blocks.CRYING_OBSIDIAN))
    }

    /** Dream-coast sand — falling-block behaviour identical to vanilla
     *  sand. Dust colour 0xC4B695 sampled from the dream-sand palette so
     *  the puff matches the block. */
    val DREAM_SAND: RegistrySupplier<Block> = BLOCKS.register("dream_sand") {
        SandBlock(0xC4B695, BlockBehaviour.Properties.copy(Blocks.SAND))
    }

    fun register() = BLOCKS.register()
}
