package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource

/**
 * Server-sent spell particles and sounds, using vanilla particle types tinted per element.
 * [ServerLevel.sendParticles] broadcasts to watching clients, so no custom providers needed.
 *
 * The shaped helpers ([outwardBlast], [inwardCollapse], [swirl]) are sphere visuals; on any
 * other delivery they fall back to a lighter [burst] along it.
 */
object SpellFx {

    /** Particles scattered over the delivery. One batched packet for a sphere (MC samples
     *  [count] positions inside the radius for us); spread along a line; clustered on a
     *  single entity or block face. */
    fun burst(level: ServerLevel, delivery: Delivery, particle: ParticleOptions, count: Int) {
        if (held(delivery)) return
        val count = thin(count)
        when (delivery) {
            is Delivery.Sphere -> {
                val c = delivery.anchor
                val r = delivery.radius
                level.sendParticles(particle, c.x, c.y, c.z, count, r, r, r, 0.05)
            }
            is Delivery.Line -> {
                val points = count.coerceAtLeast(1)
                for (i in 0 until points) {
                    val p = delivery.from.lerp(delivery.to, (i + level.random.nextDouble()) / points)
                    level.sendParticles(particle, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.02)
                }
            }
            is Delivery.Cone -> {
                val points = count.coerceAtLeast(1)
                repeat(points) {
                    val t = Math.sqrt(level.random.nextDouble())
                    val spread = delivery.length * Math.tan(delivery.halfAngle) * t
                    val p = delivery.origin.add(delivery.direction.scale(delivery.length * t))
                    level.sendParticles(particle, p.x, p.y, p.z, 1, spread * 0.4, spread * 0.4, spread * 0.4, 0.02)
                }
            }
            is Delivery.Group -> for (target in delivery.targets) {
                val c = target.position().add(0.0, target.bbHeight * 0.5, 0.0)
                level.sendParticles(particle, c.x, c.y, c.z, (count / delivery.targets.size).coerceAtLeast(3),
                    target.bbWidth * 0.5, target.bbHeight * 0.5, target.bbWidth * 0.5, 0.05)
            }
            is Delivery.SingleEntity -> {
                val c = delivery.anchor
                val e = delivery.entity
                level.sendParticles(particle, c.x, c.y, c.z, count, e.bbWidth * 0.5, e.bbHeight * 0.5, e.bbWidth * 0.5, 0.05)
            }
            is Delivery.SingleBlock -> {
                val c = delivery.anchor
                level.sendParticles(particle, c.x, c.y, c.z, count, 0.3, 0.3, 0.3, 0.05)
            }
        }
    }

    /** Particles from the centre with outward-radial motion. Each is its own packet (the
     *  batched [burst] can't direct motion per particle), so keep [count] modest. */
    fun outwardBlast(level: ServerLevel, delivery: Delivery, particle: ParticleOptions, count: Int, speed: Double) {
        if (held(delivery)) return
        val count = thin(count)
        val sphere = delivery as? Delivery.Sphere ?: return burst(level, delivery, particle, fallbackCount(count))
        val c = sphere.anchor
        repeat(count) {
            val (dx, dy, dz) = randomUnit(level)
            level.sendParticles(particle, c.x, c.y, c.z, 0, dx * speed, dy * speed, dz * speed, 1.0)
        }
    }

    /** Particles on the sphere's surface moving inward — "pulled toward the centre". */
    fun inwardCollapse(level: ServerLevel, delivery: Delivery, particle: ParticleOptions, count: Int, speed: Double) {
        if (held(delivery)) return
        val count = thin(count)
        val sphere = delivery as? Delivery.Sphere ?: return burst(level, delivery, particle, fallbackCount(count))
        val c = sphere.anchor
        val r = sphere.radius
        repeat(count) {
            val (dx, dy, dz) = randomUnit(level)
            level.sendParticles(particle, c.x + dx * r, c.y + dy * r, c.z + dz * r, 0, -dx * speed, -dy * speed, -dz * speed, 1.0)
        }
    }

    /** Particles in a thin horizontal band around the centre with tangential motion. Each
     *  moves in a straight line, but the initial tangent reads as a swirl. The band is ~3 m
     *  tall so the swirl isn't diluted across the whole sphere. */
    fun swirl(level: ServerLevel, delivery: Delivery, particle: ParticleOptions, count: Int, tangentSpeed: Double) {
        if (held(delivery)) return
        val count = thin(count)
        val sphere = delivery as? Delivery.Sphere ?: return burst(level, delivery, particle, fallbackCount(count))
        val c = sphere.anchor
        val radius = sphere.radius
        repeat(count) {
            val angle = level.random.nextDouble() * 2.0 * Math.PI
            // r ∈ [1, radius] — avoid r=0 where the tangent direction collapses.
            val r = 1.0 + level.random.nextDouble() * (radius - 1.0)
            val px = c.x + r * Math.cos(angle)
            val py = c.y - 0.5 + level.random.nextDouble() * 3.0
            val pz = c.z + r * Math.sin(angle)
            level.sendParticles(particle, px, py, pz, 0, -Math.sin(angle) * tangentSpeed, 0.02, Math.cos(angle) * tangentSpeed, 1.0)
        }
    }

    /** A few upward-drifting particles on one affected block. Sparse on purpose; per block. */
    fun blockMark(level: ServerLevel, particle: ParticleOptions, pos: BlockPos, count: Int = 2) {
        repeat(count) {
            val ox = (level.random.nextDouble() - 0.5) * 0.6
            val oz = (level.random.nextDouble() - 0.5) * 0.6
            val vy = 0.08 + level.random.nextDouble() * 0.10
            level.sendParticles(particle, pos.x + 0.5 + ox, pos.y + 1.0, pos.z + 0.5 + oz, 0, 0.0, vy, 0.0, 1.0)
        }
    }

    /** A held Beam or Spray re-applies every 0.5 s; its look comes from the beam renderer
     *  and the spray's mist, not a burst per pulse. */
    private fun held(delivery: Delivery): Boolean = delivery is Delivery.Line || delivery is Delivery.Cone

    /** Particles are accents now; the volume comes from [SpellVfx]. */
    private fun thin(count: Int): Int = (count * DENSITY).toInt().coerceAtLeast(1)
    private const val DENSITY = 0.5

    fun sound(level: ServerLevel, delivery: Delivery, sound: SoundEvent, volume: Float = 1f, pitch: Float = 1f) {
        if (held(delivery)) return
        level.playSound(null, BlockPos.containing(delivery.anchor), sound, SoundSource.PLAYERS, volume, pitch)
    }

    private fun fallbackCount(count: Int): Int = (count / 3).coerceAtLeast(4)

    private fun randomUnit(level: ServerLevel): Triple<Double, Double, Double> {
        val theta = level.random.nextDouble() * 2.0 * Math.PI
        val phi = Math.acos(2.0 * level.random.nextDouble() - 1.0)
        return Triple(Math.sin(phi) * Math.cos(theta), Math.cos(phi), Math.sin(phi) * Math.sin(theta))
    }
}
