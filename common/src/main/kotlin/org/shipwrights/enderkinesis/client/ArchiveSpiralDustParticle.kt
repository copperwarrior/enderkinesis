package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.particles.SimpleParticleType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sselith-yellow dust mote that physically *orbits* the live Archive entity
 * while rising. Tracking the live entity (rather than a fixed spawn-time
 * axis) means the tornado's dust stays bound to the entity as it drifts
 * through the corridor — no trailing column left behind.
 *
 * Re-uses the same `generic_*` + `glitter_*` sprite atlas as
 * [SselithDustParticle] (declared via `archive_spiral_dust.json`); the
 * separate registration is purely so the orbit/rise tick logic doesn't
 * interfere with the cataloger dust trail.
 *
 * Entity binding: spawned with `xd = entity.id.toDouble()`. The particle
 * stores that ID, derives its initial radius + angle + local-Y from the
 * spawn position relative to the entity's *current* position, then each
 * tick re-fetches the entity via `level.getEntity(id)` and orbits around
 * that fresh axis. Particle removes itself if the entity is gone (collapsed
 * or despawned), giving free cleanup on collapse.
 */
class ArchiveSpiralDustParticle(
    level: ClientLevel,
    x: Double, y: Double, z: Double,
    private val entityId: Int,
    private val sprites: SpriteSet,
) : TextureSheetParticle(level, x, y, z, 0.0, 0.0, 0.0) {

    /** Local-axis Y, relative to the bound entity's Y. Used to compute the
     *  taper (radius scales 0 → 1 as height climbs through [HEIGHT_SPAN]). */
    private var localY: Double
    private val radius: Double
    private var angle: Double

    /** Once the bound entity disappears (collapsed/despawned), the particle
     *  detaches from the orbit and falls under gravity while the existing
     *  age-based alpha curve fades it out — same dying-dust look as
     *  [SselithDustParticle]'s settling motes. */
    private var orphaned: Boolean = false
    private var fallYd: Double = 0.0

    /** Last-seen entity XZ position, plus a low-pass-filtered per-tick velocity.
     *  We sample entity position each tick rather than reading
     *  `entity.deltaMovement` because that field isn't reliably synced to
     *  remote-client mobs without `hasImpulse`/SetEntityMotionPacket; observed
     *  position-delta is ground truth. The smoothing pass removes single-tick
     *  noise so the lag visual reads as a clean curve, not a wobble. */
    private var lastEntityX: Double = 0.0
    private var lastEntityZ: Double = 0.0
    private var smoothedVx: Double = 0.0
    private var smoothedVz: Double = 0.0

    /** Half use the warm-smoke sprite ramp, half the brighter glitter ramp —
     *  same split SselithDustParticle uses so the tornado reads as the same
     *  visual family. */
    private val smokeMode: Boolean = random.nextFloat() < 0.5f
    private val baseAlpha: Float = if (smokeMode) 0.55f else 0.95f

    init {
        // Derive radial state from the LIVE entity position (if present), so
        // even a one-tick lag between entity-move and particle-spawn doesn't
        // bake an offset into the particle's initial radial vector.
        val entity = level.getEntity(entityId)
        val centerX = entity?.x ?: x
        val centerY = entity?.y ?: y
        val centerZ = entity?.z ?: z
        val rx = x - centerX
        val rz = z - centerZ
        radius = sqrt(rx * rx + rz * rz)
        angle = atan2(rz, rx)
        localY = y - centerY
        lastEntityX = centerX
        lastEntityZ = centerZ

        rCol = 1.00f
        gCol = 0.92f
        bCol = 0.55f
        alpha = 0.0f

        quadSize = if (smokeMode) {
            0.08f + random.nextFloat() * 0.05f
        } else {
            0.04f + random.nextFloat() * 0.03f
        }

        gravity = 0f
        hasPhysics = false

        // Lifetime sized to just reach the head (~2.5 b) at RISE_RATE = 0.035
        // b/tick before fade-out (70% of life). Smoke 70-85 t → ~50-60 t of
        // rise = 1.8-2.1 b lift — particles crown the column without lingering.
        lifetime = if (smokeMode) {
            70 + random.nextInt(15)
        } else {
            55 + random.nextInt(15)
        }

        setSpriteFromIndex(0)
    }

    override fun tick() {
        xo = x
        yo = y
        zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }

        // Re-fetch the live entity each tick so the orbit axis follows it as it
        // drifts. If the entity is gone (collapsed or despawned), detach and
        // fall under gravity — the existing age-based alpha curve fades it out.
        val entity = level.getEntity(entityId)
        if (entity == null) {
            orphaned = true
        }

        if (orphaned) {
            fallYd -= FALL_GRAVITY
            setPos(x, y + fallYd, z)
        } else {
            angle += SPIN_RATE
            localY += RISE_RATE

            // Observe per-tick entity velocity (xz) and low-pass-filter it; the
            // smoothed value drives the lag offset below.
            val instantVx = entity!!.x - lastEntityX
            val instantVz = entity.z - lastEntityZ
            lastEntityX = entity.x
            lastEntityZ = entity.z
            smoothedVx = smoothedVx * VEL_SMOOTHING + instantVx * (1.0 - VEL_SMOOTHING)
            smoothedVz = smoothedVz * VEL_SMOOTHING + instantVz * (1.0 - VEL_SMOOTHING)

            // Taper to a point at the bottom: scale orbit radius by localY/HEIGHT_SPAN
            // (clamped). Particles spawned near the bottom orbit on a tight inner
            // circle; particles near the top fan out to the full spawn radius.
            val taper = (localY / HEIGHT_SPAN).coerceIn(0.0, 1.0)
            val effectiveRadius = radius * taper

            // Velocity-lag: the orbit centre trails the entity's motion by
            // `velocity × lagFactor`, scaled QUADRATICALLY in (1 − taper) so the
            // trail curves rather than runs in a straight line — the upper half
            // hugs the entity (lag ≈ 25% of max at the midpoint) and the bottom
            // peels back hardest, giving the column an arched silhouette
            // instead of a slanted plank.
            val invTaper = 1.0 - taper
            val lagFactor = invTaper * invTaper * LAG_STRENGTH
            val lagX = -smoothedVx * lagFactor
            val lagZ = -smoothedVz * lagFactor

            val newX = entity.x + lagX + cos(angle) * effectiveRadius
            val newY = entity.y + localY
            val newZ = entity.z + lagZ + sin(angle) * effectiveRadius
            setPos(newX, newY, newZ)
        }

        val frame = ((age.toFloat() / lifetime.toFloat()) * 8f).toInt().coerceIn(0, 7)
        setSpriteFromIndex(frame)

        val ageF = age.toFloat() / lifetime.toFloat()
        alpha = when {
            ageF < 0.15f -> baseAlpha * (ageF / 0.15f)
            ageF > 0.70f -> baseAlpha * ((1.0f - ageF) / 0.30f)
            else         -> baseAlpha
        }
    }

    private fun setSpriteFromIndex(frame: Int) {
        val base = if (smokeMode) 0 else 8
        setSprite(sprites.get(base + frame, SPRITE_SET_SIZE))
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    companion object {
        /** Total sprite count in `archive_spiral_dust.json` — 8 generic + 8 glitter
         *  (same atlas as sselith_dust). */
        private const val SPRITE_SET_SIZE = 16

        /** Radians/tick advance around the axis. 0.18 ≈ ~2 s for a full revolution
         *  — slow enough that an individual mote's arc reads as a curve, fast enough
         *  that the tornado as a whole shows perceptible rotation. */
        private const val SPIN_RATE = 0.18

        /** Blocks/tick of rise. Combined with a ~50-tick lifetime, a particle climbs
         *  ~1.5 blocks over its lifetime — enough to vertically span the visible
         *  funnel without exiting it. */
        private const val RISE_RATE = 0.035

        /** Vertical span (blocks) the taper considers "full radius" — matches the
         *  renderer's HEIGHT_SPAN so dust and items share the same funnel envelope. */
        private const val HEIGHT_SPAN = 2.4

        /** Per-tick gravity applied once the particle is orphaned. Gentle — these
         *  are dust motes, not pebbles; the fall should read as a soft drift to
         *  the floor over the remaining lifetime, not a plummet. */
        private const val FALL_GRAVITY = 0.0015

        /** Velocity-lag IIR coefficient. 0.85 = ~7-tick effective window — long
         *  enough to wash out single-tick noise, short enough that lag responds
         *  promptly when the entity changes direction. */
        private const val VEL_SMOOTHING = 0.85

        /** Per-block-of-velocity lag at the bottom of the column. With Archive
         *  max speed ≈ 0.08 b/tick and LAG_STRENGTH = 10, the bottom lags by
         *  ~0.8 b — a clearly visible trailing curve at the corridor scale. */
        private const val LAG_STRENGTH = 10.0
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        /** `xd` carries the bound entity's network ID as a double — entity IDs
         *  are 32-bit ints, well inside double's exact-integer range. */
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle = ArchiveSpiralDustParticle(level, x, y, z, xd.toInt(), sprites)
    }
}
