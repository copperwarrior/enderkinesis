package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.world.phys.AABB
import kotlin.math.sin

/**
 * Sselith Bookmoth — a single warm yellow (#CAAD53) pixel that flickers and
 * flutters near a Sselith Lantern. Spawned by [SselithLanternBlock.animateTick],
 * so the moths only appear within the client's random animate-tick radius of a
 * placed lantern; no separate world scan is needed.
 *
 * Each particle:
 *  - **Tints** the 1×1 white sprite to #CAAD53 via `rCol/gCol/bCol`. The
 *    [quadSize] is held tiny enough to read as a single screen pixel at
 *    typical viewing distance.
 *  - **Flutters** by softly pulling its position toward a moving "drift
 *    target" that wanders inside a small box around its spawn anchor.
 *    Independent X/Y/Z drifts mean adjacent moths trace different paths.
 *  - **Flickers** via a sin² alpha pulse on a per-particle phase, with a
 *    fade-in / fade-out envelope at the ends of life so spawn and death
 *    don't pop.
 *  - **Full-bright** so the moth reads as glowing in any light.
 */
class SselithBookmothParticle(
    level: ClientLevel,
    private val anchorX: Double,
    private val anchorY: Double,
    private val anchorZ: Double,
    /** World-space AABBs (one per sub-box of the lantern's VoxelShape) the
     *  moth is forbidden from entering. Empty list if the source block isn't
     *  a lantern anymore (broken between spawn-queue and constructor). */
    private val avoidAABBs: List<AABB>,
    private val sprites: SpriteSet,
) : TextureSheetParticle(level, anchorX, anchorY, anchorZ, 0.0, 0.0, 0.0) {

    private val flickerPhase: Float = random.nextFloat() * (Math.PI.toFloat() * 2f)

    init {
        // #CAAD53 → (202, 173, 83) / 255.
        rCol = 0.792f
        gCol = 0.678f
        bCol = 0.325f
        alpha = 0.0f

        // Tiny — reads as one screen pixel at typical viewing distance. Particles
        // smaller than ~0.02 quad-units start sub-sampling out at long range, which
        // matches the "fading away" feel of distant moths.
        quadSize = 0.03f

        gravity = 0.0f
        hasPhysics = false

        xd = 0.0
        yd = 0.0
        zd = 0.0

        // ~10–20 s lifetime. With per-lantern spawn rate dialled in
        // [SselithLanternBlock.animateTick], yields a small, lively cluster.
        lifetime = 200 + random.nextInt(200)

        setSprite(sprites.get(0, 1))
    }

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) { remove(); return }

        val tA = age.toFloat()
        // Chaotic moth flight: small random kicks every tick + an occasional
        // larger "dart" (sudden direction change), a soft pull back toward
        // the anchor so the moth stays around its lantern instead of wandering
        // off forever, and velocity damping so kicks don't accumulate into a
        // runaway speed.
        xd += (random.nextDouble() - 0.5) * KICK_STRENGTH
        yd += (random.nextDouble() - 0.5) * KICK_STRENGTH * 0.7
        zd += (random.nextDouble() - 0.5) * KICK_STRENGTH
        if (random.nextInt(DART_DENOM) == 0) {
            val angle = random.nextDouble() * Math.PI * 2.0
            val pitch = (random.nextDouble() - 0.5) * Math.PI
            val mag = DART_STRENGTH * (0.5 + random.nextDouble())
            val cosP = Math.cos(pitch)
            xd += Math.cos(angle) * cosP * mag
            yd += Math.sin(pitch) * mag * 0.5
            zd += Math.sin(angle) * cosP * mag
        }
        // Anchor pull scales with distance — strong when the moth strays far,
        // negligible near the anchor, so the chaos still dominates the centre.
        val ax = anchorX - x
        val ay = anchorY - y
        val az = anchorZ - z
        val distSqr = ax * ax + ay * ay + az * az
        val pullScale = ANCHOR_PULL_BASE + Math.min(distSqr, 4.0) * ANCHOR_PULL_PER_DIST_SQR
        xd += ax * pullScale
        yd += ay * pullScale
        zd += az * pullScale
        // Damping
        xd *= DAMPING
        yd *= DAMPING
        zd *= DAMPING
        // Apply velocity
        x += xd
        y += yd
        z += zd

        // Anti-clip: if we landed inside any of the lantern's voxel sub-AABBs,
        // pop out along the shortest axis and reflect velocity on that axis
        // with damping. The next tick's kicks will scatter the trajectory
        // away from the rebound direction so the moth doesn't get stuck
        // bouncing rhythmically on the same face.
        avoidLanternShape()

        // Flicker:
        //  - envelope: fade in over the first 8% of life, fade out the last
        //    15%, so spawn/death don't pop.
        //  - pulse: sin² of (age × ~0.45 rad/tick + phase) — period ≈ 0.7 s,
        //    sharp peaks ("blip on / blip off"), random per-particle phase.
        val ageNorm = tA / lifetime.toFloat()
        val envelope = when {
            ageNorm < 0.08f -> ageNorm / 0.08f
            ageNorm > 0.85f -> (1f - ageNorm) / 0.15f
            else            -> 1f
        }
        val s = sin(tA * FLICKER_OMEGA + flickerPhase)
        val pulse = (s * 0.5f + 0.5f).let { it * it }
        alpha = envelope * (FLICKER_FLOOR + pulse * (1f - FLICKER_FLOOR))
    }

    /** Full-bright so the warm-yellow pixel reads as glowing in any light. */
    override fun getLightColor(partialTick: Float): Int = 0xF000F0

    /** Walk every avoidance box; the first one that contains the moth gets
     *  resolved (moth ejected along the shortest exit axis, velocity on that
     *  axis reflected and damped). Stops after one box per tick because
     *  resolving multiple overlapping boxes in a single step can chase the
     *  moth through a non-physical corner — the next tick handles whichever
     *  adjacent box it lands in. */
    private fun avoidLanternShape() {
        if (avoidAABBs.isEmpty()) return
        for (aabb in avoidAABBs) {
            if (x <= aabb.minX || x >= aabb.maxX) continue
            if (y <= aabb.minY || y >= aabb.maxY) continue
            if (z <= aabb.minZ || z >= aabb.maxZ) continue
            val dxMin = x - aabb.minX
            val dxMax = aabb.maxX - x
            val dyMin = y - aabb.minY
            val dyMax = aabb.maxY - y
            val dzMin = z - aabb.minZ
            val dzMax = aabb.maxZ - z
            val minDist = minOf(dxMin, dxMax, dyMin, dyMax, dzMin, dzMax)
            when (minDist) {
                dxMin -> { x = aabb.minX - EXIT_EPSILON; xd = -Math.abs(xd) * BOUNCE_DAMPING }
                dxMax -> { x = aabb.maxX + EXIT_EPSILON; xd =  Math.abs(xd) * BOUNCE_DAMPING }
                dyMin -> { y = aabb.minY - EXIT_EPSILON; yd = -Math.abs(yd) * BOUNCE_DAMPING }
                dyMax -> { y = aabb.maxY + EXIT_EPSILON; yd =  Math.abs(yd) * BOUNCE_DAMPING }
                dzMin -> { z = aabb.minZ - EXIT_EPSILON; zd = -Math.abs(zd) * BOUNCE_DAMPING }
                else  -> { z = aabb.maxZ + EXIT_EPSILON; zd =  Math.abs(zd) * BOUNCE_DAMPING }
            }
            return
        }
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    companion object {
        /** Per-tick uniform random velocity kick magnitude. Small enough that
         *  individual kicks are sub-pixel; the integrated random walk + decay
         *  produces the constant fidget. */
        private const val KICK_STRENGTH: Double = 0.012

        /** 1-in-N chance of a "dart" — a larger directional kick that reads as
         *  the moth making a sudden change of direction. ~5 % per tick gives
         *  roughly one dart per second per moth. */
        private const val DART_DENOM: Int = 22
        private const val DART_STRENGTH: Double = 0.045

        /** Anchor pull is `BASE + PER_DIST_SQR · d²`. Stays gentle in the
         *  inner box (so chaos dominates the look) but grows quadratically
         *  with distance so a moth that wanders far gets yanked back firmly. */
        private const val ANCHOR_PULL_BASE: Double = 0.006
        private const val ANCHOR_PULL_PER_DIST_SQR: Double = 0.012

        /** Velocity decay per tick. 0.86 ≈ kicks die out over ~10 ticks, fast
         *  enough that direction changes feel responsive but slow enough to
         *  keep the visible motion smooth. */
        private const val DAMPING: Double = 0.86

        /** Radians/tick on the flicker sine — 0.45 ≈ 0.7-s period. */
        private const val FLICKER_OMEGA: Float = 0.45f

        /** Minimum alpha between flicker peaks — keeps the moth faintly visible
         *  even at the dimmest part of the cycle. */
        private const val FLICKER_FLOOR: Float = 0.15f

        /** Tiny offset past the AABB face so the next tick's containment test
         *  doesn't immediately re-trigger. Smaller than KICK_STRENGTH so the
         *  moth doesn't visibly jump on a bounce. */
        private const val EXIT_EPSILON: Double = 0.001

        /** Fraction of the incoming velocity retained on the reflected axis.
         *  0.45 reads as a soft "deflection" — moths don't ping-pong, they
         *  just turn away. */
        private const val BOUNCE_DAMPING: Double = 0.45
    }

    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(
            type: SimpleParticleType, level: ClientLevel,
            x: Double, y: Double, z: Double,
            xd: Double, yd: Double, zd: Double,
        ): Particle {
            // xd/yd/zd carry the source lantern's block coords (see
            // [SselithLanternBlock.animateTick]). Pull the lantern's actual
            // VoxelShape and convert each sub-box to a world-space AABB the
            // moth uses as a no-fly volume. Empty list (and so no avoidance)
            // when the source block has been broken between spawn-queue and
            // construction, or the shape is empty for any other reason.
            val lanternPos = BlockPos(xd.toInt(), yd.toInt(), zd.toInt())
            val shape = level.getBlockState(lanternPos).getShape(level, lanternPos)
            val avoid = if (shape.isEmpty) emptyList<AABB>() else shape.toAabbs().map {
                it.move(lanternPos.x.toDouble(), lanternPos.y.toDouble(), lanternPos.z.toDouble())
            }
            return SselithBookmothParticle(level, x, y, z, avoid, sprites)
        }
    }
}
