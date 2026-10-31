package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.server.level.ServerLevel

object SpellFx {

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

    /** Each particle is its own packet, because a batched [burst] cannot set motion per particle. */
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

    /** The band is approximately 3 m tall, so the swirl does not spread across the full sphere. */
    fun swirl(level: ServerLevel, delivery: Delivery, particle: ParticleOptions, count: Int, tangentSpeed: Double) {
        if (held(delivery)) return
        val count = thin(count)
        val sphere = delivery as? Delivery.Sphere ?: return burst(level, delivery, particle, fallbackCount(count))
        val c = sphere.anchor
        val radius = sphere.radius
        repeat(count) {
            val angle = level.random.nextDouble() * 2.0 * Math.PI
            // Keep r >= 1, because the tangent direction collapses at r = 0.
            val r = 1.0 + level.random.nextDouble() * (radius - 1.0)
            val px = c.x + r * Math.cos(angle)
            val py = c.y - 0.5 + level.random.nextDouble() * 3.0
            val pz = c.z + r * Math.sin(angle)
            level.sendParticles(particle, px, py, pz, 0, -Math.sin(angle) * tangentSpeed, 0.02, Math.cos(angle) * tangentSpeed, 1.0)
        }
    }

    /** Sparse on purpose, because this runs for each block. */
    fun blockMark(level: ServerLevel, particle: ParticleOptions, pos: BlockPos, count: Int = 2) {
        repeat(count) {
            val ox = (level.random.nextDouble() - 0.5) * 0.6
            val oz = (level.random.nextDouble() - 0.5) * 0.6
            val vy = 0.08 + level.random.nextDouble() * 0.10
            level.sendParticles(particle, pos.x + 0.5 + ox, pos.y + 1.0, pos.z + 0.5 + oz, 0, 0.0, vy, 0.0, 1.0)
        }
    }

    /** The beam renderer and the spray mist show held shapes, not a burst for each pulse. */
    private fun held(delivery: Delivery): Boolean = delivery is Delivery.Line || delivery is Delivery.Cone

    /** Particles are accents. The volume comes from [SpellVfx]. */
    private fun thin(count: Int): Int = (count * DENSITY).toInt().coerceAtLeast(1)
    private const val DENSITY = 0.5

    private fun fallbackCount(count: Int): Int = (count / 3).coerceAtLeast(4)

    private fun randomUnit(level: ServerLevel): Triple<Double, Double, Double> {
        val theta = level.random.nextDouble() * 2.0 * Math.PI
        val phi = Math.acos(2.0 * level.random.nextDouble() - 1.0)
        return Triple(Math.sin(phi) * Math.cos(theta), Math.cos(phi), Math.sin(phi) * Math.sin(theta))
    }
}
