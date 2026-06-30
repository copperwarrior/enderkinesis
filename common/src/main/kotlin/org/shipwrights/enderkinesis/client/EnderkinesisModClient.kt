package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.client.level.entity.EntityRendererRegistry
import dev.architectury.registry.client.particle.ParticleProviderRegistry
import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry
import dev.architectury.registry.client.rendering.ColorHandlerRegistry
import dev.architectury.registry.client.rendering.RenderTypeRegistry
import net.minecraft.client.color.block.BlockColor
import net.minecraft.client.color.item.ItemColor
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.level.FoliageColor
import org.shipwrights.enderkinesis.block.AncriteChainBlock
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * The common static client object that represents the mod.
 */
object EnderkinesisModClient {
    @JvmStatic
    fun initClient() {
        // Orb of Potential client-side packet receivers — keep the
        // server-bound classes out of the client environment.
        org.shipwrights.enderkinesis.body.OrbBodyNetworkClient.initClient()

        // Wik-Lak redirect flash — receive S2C trigger and stamp the
        // player UUID into [WikLakRedirectFlashTracker] for the player
        // render layer to read each frame.
        dev.architectury.networking.NetworkManager.registerReceiver(
            dev.architectury.networking.NetworkManager.Side.S2C,
            org.shipwrights.enderkinesis.entity.WikLakRedirectFlashNetwork.FLASH,
        ) { buf, _ ->
            val uuid = buf.readUUID()
            org.shipwrights.enderkinesis.client.WikLakRedirectFlashTracker.startFade(uuid)
        }

        // Orb of Potential client-side dynamic lighting: each client
        // tick the driver picks the N closest orbs to the camera and
        // tells the block-light engine to emit from their positions.
        // Cooperates with [BlockLightEngineOrbDynamicLightMixin].
        ClientTickEvent.CLIENT_POST.register { mc ->
            org.shipwrights.enderkinesis.client.OrbDynamicLightDriver.tick(mc)
        }

        // 1-bit-alpha lattice texture (opaque crystal pixels, fully-transparent gaps). Cutout
        // layer is correct: alpha-test discards transparent pixels and renders the rest opaque
        // — no smooth blending, no sort order issues, faster than `translucent()`.
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.CREPUSCULITE_LATTICE.get())

        // The Ancrite Chain block uses the same alpha-cutout layer as vanilla chain — without
        // this the transparent texture columns render opaque.
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.ANCRITE_CHAIN.get())

        // The model uses the brighter `ancrite_chain_bright` texture as its source; this
        // BlockColor scales it down (white tint, R=G=B) so POWER 0 looks like the original
        // `ancrite_chain.png`, and POWER 15 leaves it at full brightness. The block-light
        // emission stays constant at 4 — the visual brightness change is purely in the
        // rendered texture.
        ColorHandlerRegistry.registerBlockColors(
            BlockColor { state, _, _, _ ->
                AncriteChainBlock.getColorForPower(state.getValue(AncriteChainBlock.POWER))
            },
            EKBlocks.ANCRITE_CHAIN.get()
        )

        // Wogor leaves: tinted by the biome's foliage color, same as vanilla oak / birch /
        // dark-oak / jungle leaves. Vanilla mangrove leaves (which we cloned at the block level)
        // *don't* register a BlockColor — they're a fixed mangrove green — so cloning that block
        // inherited "no tint handler", which left our wogor leaves rendering at the texture's
        // raw colors regardless of biome. The model's parent `minecraft:block/leaves` already
        // sets `tintindex: 0` on every face, so the renderer is asking for a tint per pixel —
        // we just need to answer that ask. `BiomeColors.getAverageFoliageColor` does the 3x3
        // biome blend automatically.
        ColorHandlerRegistry.registerBlockColors(
            BlockColor { _, level, pos, _ ->
                if (level != null && pos != null) {
                    BiomeColors.getAverageFoliageColor(level, pos)
                } else {
                    FoliageColor.getDefaultColor()
                }
            },
            EKBlocks.WOGOR_LEAVES.get()
        )

        // Wogor leaves item: the inventory icon has no world context, so we can't look up a
        // biome. Vanilla leaves items fall back to `FoliageColor.getDefaultColor()` (the
        // default foliage green). Matching that here gives the item a sensible tint in the
        // creative tab and player inventory.
        ColorHandlerRegistry.registerItemColors(
            ItemColor { _, tintIndex ->
                if (tintIndex == 0) FoliageColor.getDefaultColor() else -1
            },
            EKItems.WOGOR_LEAVES_ITEM.get()
        )


        // Planar Anchor: the model's "chain" element relies on alpha-cut transparency. Without
        // cutoutMipped the transparent pixels in the chain UV region render solid.
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.PLANAR_ANCHOR.get())

        // Void Harness: crepusculite-glass shell — partial alpha, needs translucent blending.
        RenderTypeRegistry.register(RenderType.translucent(), EKBlocks.VOID_HARNESS.get())

        // Void Hook: same crepusculite-glass shell as the harness, plus an opaque dispenser
        // front-face texture. cutoutMipped lets the dispenser-front texture stay opaque while
        // the crepusculite-glass faces still alpha-cut through.
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.VOID_HOOK.get())

        // Crystal Explosive: same crepusculite-glass shell as Void Harness, same need.
        RenderTypeRegistry.register(RenderType.translucent(), EKBlocks.CRYSTAL_EXPLOSIVE.get())

        // Crepusculite Glass — partial-alpha glass block, same render layer as Void Harness.
        RenderTypeRegistry.register(RenderType.translucent(), EKBlocks.CREPUSCULITE_GLASS.get())

        // Ancrite grate (full block) and bars — both use the grate texture which is alpha-cut.
        // Without cutoutMipped the transparent holes render as solid pixels.
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.ANCRITE_GRATE.get())
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.ANCRITE_BARS.get())

        // Sselith Ladder — same single-quad ladder model as vanilla, with the
        // deepslate_ladder texture's transparent pixels needing alpha cutout
        // to read as climbable bars rather than a flat opaque rectangle.
        RenderTypeRegistry.register(RenderType.cutout(), EKBlocks.SSELITH_LADDER.get())

        // Sselith Trapdoor — vanilla template_trapdoor models, but the
        // deepslate_trapdoor texture has alpha-cut hinge gaps; without
        // cutout the gaps render as solid black.
        RenderTypeRegistry.register(RenderType.cutout(), EKBlocks.SSELITH_TRAPDOOR.get())

        // Binding Roots: custom hand-rolled model (we abandoned the `minecraft:block/cross`
        // parent to add the wogor-wood slab base), so we lose the implicit cutout layer the
        // cross parent gets from vanilla's block render-layer registration. Re-add cutout
        // here or the wogor-binding-roots texture's transparent pixels read as black.
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.BINDING_ROOTS.get())

        // The astrolabe is drawn by a BlockEntityRenderer (translucent + no backface culling).
        BlockEntityRendererRegistry.register(EKBlockEntities.ENDER_ASTROLABE.get()) { _ ->
            EnderAstrolabeRenderer()
        }

        // Per-slot missile overlays on the launcher come from a BER (drawing them via
        // blockstate would mean 2^16 × 4 ≈ 260k states and Minecraft hangs at startup
        // enumerating that). The blockstate stays at 8 entries (FACING × POWERED).
        BlockEntityRendererRegistry.register(EKBlockEntities.MAGIC_MISSILE_LAUNCHER.get()) { ctx ->
            MagicMissileLauncherRenderer(ctx)
        }

        // The eyeroscope's frame comes from the static chunk mesh (end-portal-frame look-alike);
        // the BER only adds the floating, bobbing ender-eye on top.
        BlockEntityRendererRegistry.register(EKBlockEntities.EYEROSCOPE.get()) { _ ->
            EyeroscopeRenderer()
        }

        // Statues — single BER picks the right Blockbench model by block kind.
        BlockEntityRendererRegistry.register(EKBlockEntities.STATUE.get()) { ctx ->
            StatueBlockEntityRenderer(ctx)
        }

        // The Planar Anchor draws the chain to its outboard cloud and seeds the particle swirl.
        BlockEntityRendererRegistry.register(EKBlockEntities.PLANAR_ANCHOR.get()) { ctx ->
            PlanarAnchorRenderer(ctx)
        }

        // The Void Harness renders a vanilla-style end crystal inside its stained-glass shell;
        // the crystal animates and brightens when powered.
        BlockEntityRendererRegistry.register(EKBlockEntities.VOID_HARNESS.get()) { ctx ->
            VoidHarnessRenderer(ctx)
        }

        // Void Hook reuses the harness crystal-pair visual as a placeholder; same per-tick
        // rotation + brightness response wired to the hook's own POWER blockstate.
        BlockEntityRendererRegistry.register(EKBlockEntities.VOID_HOOK.get()) { ctx ->
            VoidHookRenderer(ctx)
        }

        // Orb of Linking: pulsing "haze" outer layer drawn on top of the static block model.
        // Invisible when the orb is UNBOUND, visible + sine-pulsed when SEND or RECEIVE.
        BlockEntityRendererRegistry.register(EKBlockEntities.ORB_OF_LINKING.get()) { ctx ->
            OrbOfLinkingHazeRenderer(ctx)
        }

        // Orb of Scrying: same orb-shell + pulsing-haze BER as Orb of Linking, but always
        // active (no role gate) and textured with `block/scrying_orb`.
        BlockEntityRendererRegistry.register(EKBlockEntities.ORB_OF_SCRYING.get()) { ctx ->
            OrbOfScryingHazeRenderer(ctx)
        }

        // Hook the scrying-orb client-side view + network listener.
        ScryingClient.init()

        // Heart of the Wild: four-chamber Blockbench model with a
        // sinusoidal idle pulse on the chambers. The block's static
        // JSON model is a transparent placeholder; this BER draws
        // the visible heart.
        BlockEntityRendererRegistry.register(EKBlockEntities.HEART_OF_THE_WILD.get()) { ctx ->
            HeartOfTheWildRenderer(ctx)
        }

        // Crystal Explosive: vanilla end-crystal entity model rotating inside a
        // crepusculite-glass shell — same shell style as the Void Harness.
        BlockEntityRendererRegistry.register(EKBlockEntities.CRYSTAL_EXPLOSIVE.get()) { ctx ->
            CrystalExplosiveRenderer(ctx)
        }

        // Fractal Projector: NO BlockEntityRenderer here. The orb's
        // visual is owned by [OrbOfPotentialRenderer], which is driven
        // from `LevelRenderer.renderLevel` via [LevelRendererOrbRenderMixin]
        // and uses each marker BE's `bodyId` to look up the live VS Body
        // transform. The block itself is invisible (RenderShape.INVISIBLE)
        // so leaving no renderer registered is correct.

        // Shulker Strut base — vanilla shulker base shell at the BE's block, FACING-rotated.
        BlockEntityRendererRegistry.register(EKBlockEntities.SHULKER_STRUT.get()) { ctx ->
            ShulkerStrutRenderer(ctx)
        }

        // Shulker Strut lid — vanilla shulker lid shell, drawn at the lid block's ship-local
        // position. VS2's render hook applies the lid ship's transform around this BER, so
        // the lid follows the joint-driven motion (and any blocks the player added to the
        // ship).
        BlockEntityRendererRegistry.register(EKBlockEntities.SHULKER_STRUT_TOP.get()) { ctx ->
            ShulkerStrutTopRenderer(ctx)
        }

        // Virtual-ocean particles: wave-following surface, subsurface volume, and hull splash.
        ParticleProviderRegistry.register(EKParticles.ocean()) { sprites ->
            OceanParticle.Provider(sprites, OceanParticle.Mode.SURFACE)
        }
        ParticleProviderRegistry.register(EKParticles.oceanDeep()) { sprites ->
            OceanParticle.Provider(sprites, OceanParticle.Mode.DEEP)
        }
        ParticleProviderRegistry.register(EKParticles.splash()) { sprites ->
            OceanParticle.Provider(sprites, OceanParticle.Mode.SPLASH)
        }
        ParticleProviderRegistry.register(EKParticles.foamCrest()) { sprites ->
            OceanParticle.Provider(sprites, OceanParticle.Mode.FOAM_CREST)
        }

        // Planar Anchor portal disc: ender-green recolour of vanilla `PORTAL`, same motion math.
        ParticleProviderRegistry.register(EKParticles.planarSpiral()) { sprites ->
            PlanarSpiralParticle.Provider(sprites)
        }

        // Shulker Puffer thruster exhaust — distance-keyed grow/fade size curve, short
        // lifetime, dragon-breath sprite. See [ShulkerPufferParticle] for the curve.
        ParticleProviderRegistry.register(EKParticles.shulkerPuffer()) { sprites ->
            ShulkerPufferParticle.Provider(sprites)
        }

        // Sselith ambient motes — tiny warm-yellow particles that drift downward to give the
        // Repertory a "dust in afternoon sunlight" atmosphere. Spawned by the biome's
        // ambient `particle` setting in `sselith_repertory.json`.
        ParticleProviderRegistry.register(EKParticles.sselithMote()) { sprites ->
            SselithMoteParticle.Provider(sprites)
        }

        // Cataloger dust trail — visually a SselithMote, but with block collision and a
        // shorter lifetime. Kept as a separate registration so the biome-wide ambient
        // mote doesn't pay the per-particle terrain-collision cost. Spawned server-side
        // from Cataloger.aiStep.
        ParticleProviderRegistry.register(EKParticles.sselithDust()) { sprites ->
            SselithDustParticle.Provider(sprites)
        }

        ParticleProviderRegistry.register(EKParticles.archiveSpiralDust()) { sprites ->
            ArchiveSpiralDustParticle.Provider(sprites)
        }

        // Reusable enchanted-book bezier beam — used by the Wylland Tome (player → grab point)
        // and the Tome of Signal's orb network (send orb → receivers). One particle type, many
        // simultaneous beams: each particle carries a `pathId` referencing a `BeamPath` in the
        // global `BeamRegistry`, re-evaluating the current curve every tick so the beam follows
        // a moving target (turning view, drifting ship) without trailing.
        ParticleProviderRegistry.register(EKParticles.enchantedBookBeam()) { sprites ->
            EnchantedBookBeamParticle.Provider(sprites)
        }

        // Wylland Tome ship rain — emissive enchanted-book glyphs that
        // fall inside a targeted ship's local AABB. Low count, full-bright.
        ParticleProviderRegistry.register(EKParticles.wyllandTomeShipGlyph()) { sprites ->
            WyllandTomeShipParticle.Provider(sprites)
        }

        // Sselith glyph column — emissive sga runes rising from a lectern
        // when a Cataloger translates its book.
        ParticleProviderRegistry.register(EKParticles.sselithGlyph()) { sprites ->
            SselithGlyphParticle.Provider(sprites)
        }

        // Wohlonnogondonia ambient fireflies — light-teal flickering glitter
        // that orbits a spawn-anchor block. Client-side spawning is driven
        // by [WohlonnogondoniaFireflies] at low density while the player is
        // in Wohlon; behaviour and per-particle flicker math live in
        // [WohlonnogondoniaFireflyParticle].
        ParticleProviderRegistry.register(EKParticles.wohlonFirefly()) { sprites ->
            WohlonnogondoniaFireflyParticle.Provider(sprites)
        }

        // Wik-Lak bind thread — same glitter sprite + flicker as the ambient
        // firefly, but with a ~1-second lifetime so the bind line at
        // construction time vanishes instead of lingering as glitter.
        ParticleProviderRegistry.register(EKParticles.wikLakBind()) { sprites ->
            WikLakBindFireflyParticle.Provider(sprites)
        }

        // Sselith Bookmoths — a single warm-yellow pixel that flickers and
        // flutters near a Sselith Lantern. Spawned by the lantern block's
        // animateTick directly, so no separate client-side scanner is needed.
        ParticleProviderRegistry.register(EKParticles.sselithBookmoth()) { sprites ->
            SselithBookmothParticle.Provider(sprites)
        }

        // Staff-of-Aegis interior sparkle — tiny zero-gravity white glow that
        // stays exactly where AegisClient seeds it inside the shield box.
        ParticleProviderRegistry.register(EKParticles.aegisSparkle()) { sprites ->
            AegisSparkleParticle.Provider(sprites)
        }

        // Staff-of-Sundering stage-1 "ender wisp" — vanilla portal sprite,
        // warm pale-orange tint (no lavender purple), straight forward drift
        // along the beam axis.
        ParticleProviderRegistry.register(EKParticles.sunderingBeamParticle()) { sprites ->
            SunderingBeamParticle.Provider(sprites)
        }

        // Staff-of-Sundering fire — vanilla flame sprite, no motion, short
        // lifetime. Used by SunderingClient as the per-tick respawn source
        // for the rotating tip rings and the helical spiral.
        ParticleProviderRegistry.register(EKParticles.sunderingFireParticle()) { sprites ->
            SunderingFireParticle.Provider(sprites)
        }

        // Staff-of-Sundering SUNDER glyph ring — SGA sprite atlas, one
        // particle per angular slot orbiting the beam tip. Spawned only
        // for the local player; see SunderingClient.spawnGlyphRing.
        ParticleProviderRegistry.register(EKParticles.sunderingGlyphParticle()) { sprites ->
            SunderingGlyphParticle.Provider(sprites)
        }

        // Magic-missile detonation spark — same lifecycle as vanilla
        // FireworkParticles.SparkParticle (firework sprite, gravity, second-half
        // alpha + colour fade) with the OUTLINE → GLOW pink fade baked in.
        ParticleProviderRegistry.register(EKParticles.missileBurstSpark()) { sprites ->
            MissileBurstSparkParticle.Provider(sprites)
        }

        // Magic-missile detonation flash — vanilla
        // FireworkParticles.OverlayParticle's 4-tick sin-grow / linear-alpha-ramp
        // pop, tinted with OUTLINE pink instead of vanilla's per-spawn colour.
        ParticleProviderRegistry.register(EKParticles.missileBurstFlash()) { sprites ->
            MissileBurstFlashParticle.Provider(sprites)
        }


        // Ygann's Abyss: rare watching-eye apparitions in the upper sky. The tick listener
        // drives spawn/expire state; the render hook is wired via
        // [LevelRendererYgannAbyssEyesMixin] at the tail of LevelRenderer.renderSky.
        YgannAbyssWatchingEyes.init()

        // Ygann's Abyss: the writhing sea at the far bottom of the void. Drawn in the same
        // post-End-sky hook as the watching eyes (below the horizon, camera-relative); the
        // tick listener spawns/expires the surfacing eyes.
        YgannAbyssWrithingSea.init()

        // Ygann's Abyss: uncommon ambient drones / distant chanting, in the vein of cave sounds.
        YgannAbyssAmbience.init()

        // Sureibjin dream-coast ambience — single looping track played at the
        // coastline and ocean; stops west of the dune.
        SureibjinAmbience.init()

        // Sselith's Repertory: uncommon whispering murmurs, same cave-sound cadence.
        SselithAmbience.init()

        // Crepusculite Charm: hold jump while carrying the charm to levitate up to 5
        // blocks above the local ground. Lift is client-side (vanilla doesn't sync
        // `jumping` for non-vehicle movement); the server-side companion in
        // [CrepusculiteCharmManager] resets the airborne anti-cheat counter every tick.
        CrepusculiteCharmClient.init()

        // Wey'ye fruit's Ambrosial Craving — periodic hotbar drift to the
        // fruit + forced bite when it's held. Server-side scaling is
        // driven by mixins; this is purely the compulsive-craving UX.
        AmbrosialCravingClient.init()

        // Wohlonnogondonia: ambient firefly spawner. Picks ~0.5 spawns/s of
        // light-teal flickering glitter around solid blocks near the player.
        // Driven by [ClientTickEvent.CLIENT_LEVEL_POST]; particles handle
        // their own lifetime so leaving the dimension just stops new spawns.
        WohlonnogondoniaFireflies.init()

        // Ygann's Abyss: custom GUI portal shader used by [YgannAbyssVoidOverlay]'s
        // deep-blob pass. Must register before the overlay so the shader is loaded by
        // the time the overlay first draws.
        YgannAbyssRenderTypes.init()

        // Sureibjin: dream-sky noise shader sampled per-fragment from spherical
        // direction. Replaces the CPU per-vertex noise so the sky doesn't show
        // mesh facets.
        SureibjinSky.init()

        // Scrying-orb vignette shader: radial gradient with fBm-noise-perturbed edge,
        // POSITION-only full-screen quad. Same registration pattern as Ygann's.
        ScryingRenderTypes.init()

        // Tome-summon vignette shader: copy of the scrying vignette with an extra
        // Intensity uniform so the overlay can fade in / out around the local
        // player's summon. Kept as its own class so changes to one overlay don't
        // bleed into the other.
        TomeSummonRenderTypes.init()

        // Fractal Projector sphere shader: fresnel gradient (white face-on,
        // yellow at the silhouette) painted by the BER over a UV-sphere mesh.
        FractalProjectorRenderTypes.init()

        // Ygann's Abyss: void-death fade. Server class cancels vanilla void damage and
        // counts the death timer; this overlay draws the black screen in lockstep.
        YgannAbyssVoidOverlay.init()

        // The Cataloger uses a custom HumanoidModel-based geometry (robed humanoid;
        // see CatalogerModel). The model layer is registered platform-side (Forge
        // RegisterLayerDefinitions / Fabric EntityModelLayerRegistry). Decoupled-rate
        // walking animation is driven server-side in the entity's aiStep (forces a
        // constant virtual rate into walkAnimation, which HumanoidModel.setupAnim
        // reads to swing the limbs).
        EntityRendererRegistry.register(EKEntities.CATALOGER) { ctx ->
            CatalogerRenderer(ctx)
        }

        // Archive — paper / book whirlwind. Renderer draws orbiting item models
        // sampled from a fixed generic mix (paper + book); the actual loot list
        // is server-side only and only matters at collapse.
        EntityRendererRegistry.register(EKEntities.ARCHIVE) { ctx ->
            ArchiveRenderer(ctx)
        }

        // Prismatic Goat — plain quadruped renderer; the model owns
        // its walk + idle animation, the renderer just supplies the
        // texture and a small shadow.
        EntityRendererRegistry.register(EKEntities.PRISMATIC_GOAT) { ctx ->
            PrismaticGoatRenderer(ctx)
        }

        // Wik-Lak Host — player-skinned construct; vanilla PlayerModel via
        // ModelLayers.PLAYER (already registered by the vanilla player renderer)
        // means no per-loader EntityModelLayer wiring is needed.
        EntityRendererRegistry.register(EKEntities.WIK_LAK_HOST) { ctx ->
            WikLakHostRenderer(ctx)
        }

        // Player Corpse — visual stand-in for the player's body after a
        // Wik-Lak death-redirect. Resolves skin via tab-list PlayerInfo
        // so the corpse renders with the actual player's appearance.
        EntityRendererRegistry.register(EKEntities.PLAYER_CORPSE) { ctx ->
            PlayerCorpseRenderer(ctx)
        }

        // Magic Missile — spinning shulker-spark billboard + [BeamPath] trail behind it that
        // shares the orb-network particle plumbing.
        EntityRendererRegistry.register(EKEntities.MAGIC_MISSILE) { ctx ->
            MagicMissileRenderer(ctx)
        }

        // Wylland Tome — client-side input + enchant-particle beam render.
        WyllandTomeKeys.init()
        WyllandTomeClient.init()

        // Staff of Density — drag-to-set ship mass multiplier.
        DensityClient.init()
        // Staff of Scales — drag-to-set ship transform scale.
        ScalesClient.init()
        // Staff of Recital — bundle-of-tomes + shift-scroll cycle HUD.
        RecitalClient.init()
        // Staff of Aegis — per-tick particle spawn inside the shield box.
        // The wireframe render itself is hooked per-platform (Fabric:
        // WorldRenderEvents.AFTER_TRANSLUCENT; see fabric client init).
        AegisClient.init()
        // Staff of Sundering — beam render (particles, beacon cross, tip rings,
        // spiral) hooked at the same per-platform world-render stage as Aegis.
        SunderingClient.init()
        // Echo Cannon — S2C fire receiver + per-tick screech particle
        // spawn. The fading wireframe outline is drawn from the same
        // per-platform world-render hook the Sundering box uses (see
        // fabric client init).
        EchoCannonClient.init()
        ClientTickEvent.CLIENT_LEVEL_POST.register { _ -> EchoCannonClient.clientTick() }

        SselithEclipseClient.init()
        ClientTickEvent.CLIENT_LEVEL_POST.register { _ -> SselithEclipseClient.clientTick() }

        // Wylland Tome custom rendering (closed in inventory /
        // hotbar / ground / GUI, open with smooth page-flop in
        // first- and third-person hands) is owned by
        // [WyllandTomeBEWLR], registered per-platform via
        // BuiltinItemRendererRegistry on Fabric and
        // IClientItemExtensions on Forge.

        // Tome of Signal orb network — persistent beam between every loaded SEND orb and each
        // of its receivers. Spawn rate ramps with the orb's POWER blockstate, so an idle link
        // is a faint trickle and a live 15-power link visibly throbs.
        OrbOfLinkingClient.init()

        // Sselith Madness tome summon — receives BEGIN/END packets so
        // the level-renderer can draw the floating book for any
        // possessed player and the local input gate knows when to
        // suppress walking.
        PlayerTomeSummonClient.init()

        // Tome of Transportation — client-only ghost-item rendering for in-flight stacks.
        // Spawns a non-interacting [ItemEntity] for the duration of each dispatch and lets
        // it die on its own deadline. The server never spawns the entity itself.
        TransportationClient.init()

        // Free the pre-baked star VertexBuffers when the player leaves
        // Sselith so ~4.5 MB of GPU memory isn't held indefinitely in
        // other dimensions. Checked once per tick; the dimension key
        // comparison is negligible.
        ClientTickEvent.CLIENT_LEVEL_POST.register { level ->
            if (level.dimension() != SselithRepertory.LEVEL_KEY) {
                SselithRepertorySky.freeStarBuffers()
            }
        }
    }
}
