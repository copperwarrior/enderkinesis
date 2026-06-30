package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.Registries
import org.shipwrights.enderkinesis.EnderkinesisMod

/** SimpleParticleType has a protected ctor; a tiny subclass exposes it. */
class EKOceanParticleType : SimpleParticleType(false)

object EKParticles {
    val PARTICLES: DeferredRegister<ParticleType<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.PARTICLE_TYPE)

    /** The virtual-ocean surface particle (Gerstner waves, collision, purple → foam). */
    val OCEAN: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("crepusculite_ocean") { EKOceanParticleType() }

    /** Sparse ender-green particle that fills the water volume below the surface. */
    val OCEAN_DEEP: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("crepusculite_ocean_deep") { EKOceanParticleType() }

    /** Ballistic foam droplet thrown when a hull block hits the water. */
    val SPLASH: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("crepusculite_splash") { EKOceanParticleType() }

    /** Short-lived foam-green dot spawned at a wave crest. The mesh renderer carries the
     *  wave geometry now, so the original SURFACE particle's purple→foam shift is gone;
     *  this is the standalone "white-cap" highlight that the mesh can't paint per-pixel.
     *  Visually based on the foam-green portion of the old SURFACE particle's palette. */
    val FOAM_CREST: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("crepusculite_foam_crest") { EKOceanParticleType() }

    /** Ender-green recolour of vanilla `PORTAL`, used by the Planar Anchor's portal disc. Same
     *  inward-pulling motion as `PortalParticle`; only the colour tint differs. */
    val PLANAR_SPIRAL: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("planar_spiral") { EKOceanParticleType() }

    /** Dragon's-breath exhaust puff for the [ShulkerPufferBlock]. Custom lifecycle (start
     *  small → grow over 1 block of travel → fade to nothing) and lower default count + higher
     *  initial velocity than vanilla `DRAGON_BREATH`, which is too lazy/cloudy for a thruster. */
    val SHULKER_PUFFER: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("shulker_puffer") { EKOceanParticleType() }

    /** Tiny yellow ambient mote that drifts slowly downward — the visible "dust in afternoon
     *  sunlight" effect for Sselith's Repertory. Spawned by the biome's `particle` setting
     *  (see `biome/sselith_repertory.json`); the particle class controls its own motion and
     *  lifetime. */
    val SSELITH_MOTE: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("sselith_mote") { EKOceanParticleType() }

    /** Cataloger-only dust trail variant of [SSELITH_MOTE]. Same look (yellow tint, gravity,
     *  fade-in/out, sprite cycle), but with block-collision enabled and a much shorter
     *  lifetime so the small, localised cataloger spawn doesn't pay the per-tick terrain-
     *  collision cost forever like the biome-wide ambient mote would. */
    val SSELITH_DUST: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("sselith_dust") { EKOceanParticleType() }

    /** Enchant-glyph particle for the reusable bezier beam (Wylland Tome, Tome of Signal orb
     *  network, future tomes). Identical to vanilla's enchanting-table glyph (same sga_a..sga_z
     *  sprite atlas) except each glyph stays bound to a [org.shipwrights.enderkinesis.client.BeamPath]
     *  by id so the beam follows a moving curve instead of trailing on a stale segment. */
    val ENCHANTED_BOOK_BEAM: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("enchanted_book_beam") { EKOceanParticleType() }


    /** Enchanted-book glyph that rains down inside a ship's local AABB
     *  while the Wylland Tome is targeting it. Uses the same sga glyph
     *  atlas as the beam, but falls under gravity and renders fully
     *  bright (emissive) so a handful of them still reads clearly from a
     *  distance — count is deliberately low for performance. */
    val WYLLAND_TOME_SHIP_GLYPH: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("wylland_tome_ship_glyph") { EKOceanParticleType() }

    /** Rising, emissive sga-glyph rune. Spawned as a cylindrical column
     *  from a lectern when a Cataloger rewrites its book into Sselith
     *  (see [org.shipwrights.enderkinesis.entity.CatalogerScribe]). Same
     *  glyph atlas as the Wylland beam, but it drifts upward like incense
     *  and renders full-bright. */
    val SSELITH_GLYPH: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("sselith_glyph") { EKOceanParticleType() }

    /** Wohlonnogondonia ambient firefly — light-teal flickering glitter
     *  that orbits a spawn-anchor block. Spawned client-side by
     *  [org.shipwrights.enderkinesis.client.WohlonnogondoniaFireflies]
     *  at low density while the player is in Wohlon; behaviour lives
     *  in [org.shipwrights.enderkinesis.client.WohlonnogondoniaFireflyParticle]. */
    val WOHLON_FIREFLY: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("wohlonnogondonia_firefly") { EKOceanParticleType() }

    /** One-shot Wik-Lak bind-thread firefly. Same sprite + flicker as
     *  [WOHLON_FIREFLY] but with a ~1-second lifetime — used by
     *  [org.shipwrights.enderkinesis.entity.WikLakConstruction] to draw a
     *  beaded line between the new host and its creator at the moment of
     *  summoning, then vanish. */
    val WIK_LAK_BIND: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("wik_lak_bind") { EKOceanParticleType() }

    /** Sselith Bookmoth — a single warm-yellow (#CAAD53) pixel that
     *  flickers and flutters around a Sselith Lantern. Spawned by
     *  [org.shipwrights.enderkinesis.block.SselithLanternBlock.animateTick]
     *  on the client; behaviour lives in
     *  [org.shipwrights.enderkinesis.client.SselithBookmothParticle]. */
    val SSELITH_BOOKMOTH: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("sselith_bookmoth") { EKOceanParticleType() }

    /** Tiny white sparkle inside the Staff-of-Aegis shield box. Zero
     *  gravity / zero friction so the cloud stays contained. See
     *  [org.shipwrights.enderkinesis.client.AegisSparkleParticle]. */
    val AEGIS_SPARKLE: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("aegis_sparkle") { EKOceanParticleType() }

    /** Staff-of-Sundering stage-1 particle. Reuses vanilla's portal sprite
     *  for the "ender wisp" silhouette, but tinted warm pale-orange (no
     *  vanilla purple) and given a clean constant per-tick forward velocity
     *  so the cloud streams down the beam. See
     *  [org.shipwrights.enderkinesis.client.SunderingBeamParticle]. */
    val SUNDERING_BEAM_PARTICLE: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("sundering_beam_particle") { EKOceanParticleType() }

    /** Staff-of-Sundering stage 2+/3+ ring + stage-4 spiral fire particle.
     *  Uses the vanilla flame sprite for the actual fire silhouette, has
     *  zero motion (the rings rotate by being respawned at advancing
     *  angular positions each tick), and lives ~5 ticks with a sin alpha
     *  envelope. See [org.shipwrights.enderkinesis.client.SunderingFireParticle]. */
    val SUNDERING_FIRE_PARTICLE: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("sundering_fire_particle") { EKOceanParticleType() }

    /** Staff-of-Sundering SUNDER glyph-ring particle. Uses the SGA glyph
     *  atlas (`minecraft:sga_a..sga_z`); the spawning caller picks the
     *  specific letter via the `vy` slot of `addParticle`, the angular slot
     *  via `vx`, and the particle re-derives its world position every
     *  tick from the local player's current beam tip so the ring stays
     *  locked to the staff as the player turns. See
     *  [org.shipwrights.enderkinesis.client.SunderingGlyphParticle]. */
    val SUNDERING_GLYPH_PARTICLE: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("sundering_glyph_particle") { EKOceanParticleType() }

    /** Archive tornado dust. Each particle physically orbits a vertical axis
     *  (passed in via the velocity slot as `(centerX, centerY, centerZ)`)
     *  while rising, so the spiraling column shape comes from per-particle
     *  motion rather than the spawn pattern. Reuses the sselith_dust sprite
     *  atlas via [archive_spiral_dust.json]; behaviour in
     *  [org.shipwrights.enderkinesis.client.ArchiveSpiralDustParticle]. */
    val ARCHIVE_SPIRAL_DUST: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("archive_spiral_dust") { EKOceanParticleType() }

    /** Magic-missile detonation spark. Identical lifecycle to vanilla
     *  `FireworkParticles.SparkParticle` (firework sprite atlas, gravity 0.004,
     *  ~48-60-tick life, alpha + colour fade in the second half) but with the
     *  fade direction hard-coded to MagicMissileTrailRenderer's OUTLINE → GLOW
     *  pink palette so the burst reads as the same beam that produced the
     *  streak. See [org.shipwrights.enderkinesis.client.MissileBurstSparkParticle]. */
    val MISSILE_BURST_SPARK: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("missile_burst_spark") { EKOceanParticleType() }

    /** Magic-missile detonation flash. Verbatim port of vanilla
     *  `FireworkParticles.OverlayParticle` (4-tick life, size grows then shrinks on a
     *  sin curve, alpha ramps down), tinted with the OUTLINE pink so the bright pop
     *  reads as the same beam. Vanilla `ParticleTypes.FLASH` can't carry a colour
     *  through a `SimpleParticleType`, so we register our own. See
     *  [org.shipwrights.enderkinesis.client.MissileBurstFlashParticle]. */
    val MISSILE_BURST_FLASH: RegistrySupplier<ParticleType<*>> =
        PARTICLES.register("missile_burst_flash") { EKOceanParticleType() }


    fun ocean(): EKOceanParticleType = OCEAN.get() as EKOceanParticleType

    fun oceanDeep(): EKOceanParticleType = OCEAN_DEEP.get() as EKOceanParticleType

    fun splash(): EKOceanParticleType = SPLASH.get() as EKOceanParticleType

    fun foamCrest(): EKOceanParticleType = FOAM_CREST.get() as EKOceanParticleType

    fun planarSpiral(): EKOceanParticleType = PLANAR_SPIRAL.get() as EKOceanParticleType

    fun shulkerPuffer(): EKOceanParticleType = SHULKER_PUFFER.get() as EKOceanParticleType

    fun sselithMote(): EKOceanParticleType = SSELITH_MOTE.get() as EKOceanParticleType

    fun sselithDust(): EKOceanParticleType = SSELITH_DUST.get() as EKOceanParticleType

    fun enchantedBookBeam(): EKOceanParticleType = ENCHANTED_BOOK_BEAM.get() as EKOceanParticleType

    fun wyllandTomeShipGlyph(): EKOceanParticleType = WYLLAND_TOME_SHIP_GLYPH.get() as EKOceanParticleType

    fun sselithGlyph(): EKOceanParticleType = SSELITH_GLYPH.get() as EKOceanParticleType

    fun wohlonFirefly(): EKOceanParticleType = WOHLON_FIREFLY.get() as EKOceanParticleType

    fun wikLakBind(): EKOceanParticleType = WIK_LAK_BIND.get() as EKOceanParticleType

    fun sselithBookmoth(): EKOceanParticleType = SSELITH_BOOKMOTH.get() as EKOceanParticleType

    fun aegisSparkle(): EKOceanParticleType = AEGIS_SPARKLE.get() as EKOceanParticleType

    fun sunderingBeamParticle(): EKOceanParticleType = SUNDERING_BEAM_PARTICLE.get() as EKOceanParticleType

    fun sunderingFireParticle(): EKOceanParticleType = SUNDERING_FIRE_PARTICLE.get() as EKOceanParticleType

    fun sunderingGlyphParticle(): EKOceanParticleType = SUNDERING_GLYPH_PARTICLE.get() as EKOceanParticleType

    fun missileBurstSpark(): EKOceanParticleType = MISSILE_BURST_SPARK.get() as EKOceanParticleType

    fun missileBurstFlash(): EKOceanParticleType = MISSILE_BURST_FLASH.get() as EKOceanParticleType

    fun archiveSpiralDust(): EKOceanParticleType = ARCHIVE_SPIRAL_DUST.get() as EKOceanParticleType


    fun register() = PARTICLES.register()
}
