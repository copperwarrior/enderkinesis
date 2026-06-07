package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.client.level.entity.EntityRendererRegistry
import dev.architectury.registry.client.particle.ParticleProviderRegistry
import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry
import dev.architectury.registry.client.rendering.ColorHandlerRegistry
import dev.architectury.registry.client.rendering.RenderTypeRegistry
import net.minecraft.client.color.block.BlockColor
import net.minecraft.client.renderer.RenderType
import org.shipwrights.enderkinesis.block.AncriteChainBlock
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * The common static client object that represents the mod.
 */
object EnderkinesisModClient {
    @JvmStatic
    fun initClient() {
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

        // Planar Anchor: the model's "chain" element relies on alpha-cut transparency. Without
        // cutoutMipped the transparent pixels in the chain UV region render solid.
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.PLANAR_ANCHOR.get())

        // Void Harness: crepusculite-glass shell — partial alpha, needs translucent blending.
        RenderTypeRegistry.register(RenderType.translucent(), EKBlocks.VOID_HARNESS.get())

        // Crepusculite Glass — partial-alpha glass block, same render layer as Void Harness.
        RenderTypeRegistry.register(RenderType.translucent(), EKBlocks.CREPUSCULITE_GLASS.get())

        // Ancrite grate (full block) and bars — both use the grate texture which is alpha-cut.
        // Without cutoutMipped the transparent holes render as solid pixels.
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.ANCRITE_GRATE.get())
        RenderTypeRegistry.register(RenderType.cutoutMipped(), EKBlocks.ANCRITE_BARS.get())

        // The astrolabe is drawn by a BlockEntityRenderer (translucent + no backface culling).
        BlockEntityRendererRegistry.register(EKBlockEntities.ENDER_ASTROLABE.get()) { _ ->
            EnderAstrolabeRenderer()
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

        // Orb of Linking: pulsing "haze" outer layer drawn on top of the static block model.
        // Invisible when the orb is UNBOUND, visible + sine-pulsed when SEND or RECEIVE.
        BlockEntityRendererRegistry.register(EKBlockEntities.ORB_OF_LINKING.get()) { ctx ->
            OrbOfLinkingHazeRenderer(ctx)
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

        // Sselith's Repertory: uncommon whispering murmurs, same cave-sound cadence.
        SselithAmbience.init()

        // Wohlonnogondonia: ambient firefly spawner. Picks ~0.5 spawns/s of
        // light-teal flickering glitter around solid blocks near the player.
        // Driven by [ClientTickEvent.CLIENT_LEVEL_POST]; particles handle
        // their own lifetime so leaving the dimension just stops new spawns.
        WohlonnogondoniaFireflies.init()

        // Wohlonnogondonia biome sky fade — tracks player's local
        // biome and smoothly lerps the rendered time-of-day toward
        // Wohlon's fixed dusk pose when in/near a Wohlon-biome
        // patch in any non-Wohlon dimension. Sky color and fog
        // already biome-blend via vanilla's BiomeColors pipeline.
        WohlonBiomeSkyState.init()

        // Ygann's Abyss: custom GUI portal shader used by [YgannAbyssVoidOverlay]'s
        // deep-blob pass. Must register before the overlay so the shader is loaded by
        // the time the overlay first draws.
        YgannAbyssRenderTypes.init()

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

        // Wylland Tome — client-side input + enchant-particle beam render.
        WyllandTomeKeys.init()
        WyllandTomeClient.init()

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
