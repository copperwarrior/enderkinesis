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

    fun ocean(): EKOceanParticleType = OCEAN.get() as EKOceanParticleType

    fun oceanDeep(): EKOceanParticleType = OCEAN_DEEP.get() as EKOceanParticleType

    fun splash(): EKOceanParticleType = SPLASH.get() as EKOceanParticleType

    fun planarSpiral(): EKOceanParticleType = PLANAR_SPIRAL.get() as EKOceanParticleType

    fun shulkerPuffer(): EKOceanParticleType = SHULKER_PUFFER.get() as EKOceanParticleType

    fun sselithMote(): EKOceanParticleType = SSELITH_MOTE.get() as EKOceanParticleType

    fun sselithDust(): EKOceanParticleType = SSELITH_DUST.get() as EKOceanParticleType

    fun enchantedBookBeam(): EKOceanParticleType = ENCHANTED_BOOK_BEAM.get() as EKOceanParticleType

    fun wyllandTomeShipGlyph(): EKOceanParticleType = WYLLAND_TOME_SHIP_GLYPH.get() as EKOceanParticleType

    fun sselithGlyph(): EKOceanParticleType = SSELITH_GLYPH.get() as EKOceanParticleType

    fun wohlonFirefly(): EKOceanParticleType = WOHLON_FIREFLY.get() as EKOceanParticleType

    fun register() = PARTICLES.register()
}
