package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellSounds
import org.shipwrights.enderkinesis.spell.SpellVfx
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** [size] is about the radius of what was hit. */
object SpellImpacts {
    /** Ticks. */
    const val LIFE = 28
    const val BLOOD_LIFE = 90
    /** The longest delay the server sends is for a wave. */
    private const val MAX_DELAY = SpellVfx.AREA_LIFE
    /** Far above normal use (about 50 for each burst); above it, the oldest go first. */
    private const val MAX_PENDING = 8192

    private class Pending(val due: Long, val spawn: () -> Unit)

    // Render thread only.
    private val pending = ArrayList<Pending>()

    fun reset() = pending.clear()

    fun tick(level: ClientLevel) {
        if (pending.isEmpty()) return
        val now = SpellClock.now(level)
        val due = pending.filter { it.due <= now }
        pending.removeAll(due.toSet())
        for (p in due) p.spawn()
    }

    private fun later(level: ClientLevel, ticks: Int, spawn: () -> Unit) {
        if (ticks <= 0) return spawn()
        if (pending.size >= MAX_PENDING) pending.removeAt(0)
        pending += Pending(SpellClock.now(level) + ticks.coerceAtMost(MAX_DELAY), spawn)
    }

    /** Start speed to go [distance] in [ticks] when speed is multiplied by [friction] each tick. */
    private fun fling(distance: Double, ticks: Int, friction: Float): Double {
        if (friction >= 0.9999f) return distance / ticks
        val f = friction.toDouble()
        return distance * (1 - f) / (1 - f.pow(ticks))
    }

    private fun add(level: ClientLevel, particle: ParticleOptions, p: Vec3, v: Vec3 = Vec3.ZERO) =
        level.addParticle(particle, p.x, p.y, p.z, v.x, v.y, v.z)

    private fun mote(level: ClientLevel, mote: SpellMote, p: Vec3, v: Vec3 = Vec3.ZERO) = add(level, EKParticles.mote(mote), p, v)

    fun spawn(level: ClientLevel, element: Element, at: Vec3, size: Double, seed: Int, delay: Int, magnitude: Float) =
        later(level, delay) { spawn(level, element, at, size, seed, magnitude) }

    fun spawn(level: ClientLevel, element: Element, at: Vec3, size: Double, seed: Int, magnitude: Float) {
        SpellSounds.of(element)?.let { SpellSoundsClient.playAt(level, at, it.impact, SpellSounds.magnitudeVolume(magnitude)) }
        val r = Random(seed)
        when (element) {
            Element.PYRE -> heat(level, at, size, r)
            Element.WINTER -> cold(level, at, size, seed, r)
            Element.GENESIS -> growth(level, at, size, r)
            Element.BLOOD -> blood(level, at, size, r)
            Element.CORRUPTION -> poison(level, at, size, seed, r)
            Element.SUNFIRE -> sunfire(level, at, size, seed, r)
            Element.QUINTESSENCE -> purity(level, at, size, seed, r)
            Element.GRAVITY -> {
                SpellVfxClient.addLocal(SpellVfx.Kind.CRUSH, at, at, (size * 1.6).toFloat(), element.color, LIFE, seed)
                inward(level, at, size * 1.4, SpellMote.GRAVITY_MOTE, 36, r, LIFE * 2 / 3)
            }
            Element.VOID -> voidRift(level, at, size, seed, r)
            Element.CHAOS -> chaos(level, at, size, r)
            Element.SUNDERING -> SpellVfxClient.addLocal(SpellVfx.Kind.SHELL, at, at, size.toFloat(), element.color, 14, seed)
        }
    }

    private fun cold(level: ClientLevel, at: Vec3, size: Double, seed: Int, r: Random) {
        SpellVfxClient.addLocal(SpellVfx.Kind.SHARDS, at, at, size.toFloat(), 0xFFFFFF, LIFE, seed)
        repeat(40) {
            val d = SpellGeom.spread(r, 0.6)
            mote(level, SpellMote.SNOW_PUFF, at, d.scale(0.06 + 0.08 * r.nextDouble()).add(0.0, -0.03, 0.0))
        }
    }

    private fun poison(level: ClientLevel, at: Vec3, size: Double, seed: Int, r: Random) {
        SpellVfxClient.addLocal(SpellVfx.Kind.PUFFS, at, at, size.toFloat(), Element.CORRUPTION.color, LIFE, seed)
        repeat(30) {
            val angle = r.nextDouble() * Math.PI * 2
            val rad = size * (0.2 + 0.7 * r.nextDouble())
            val p = at.add(cos(angle) * rad, -0.5 + r.nextDouble() * 0.5, sin(angle) * rad)
            val turn = rad * 2.0 / LIFE
            mote(level, SpellMote.POISON_SWIRL, p, Vec3(-sin(angle) * turn, (1 + r.nextDouble()) / LIFE, cos(angle) * turn))
        }
    }

    private fun sunfire(level: ClientLevel, at: Vec3, size: Double, seed: Int, r: Random) {
        SpellVfxClient.addLocal(SpellVfx.Kind.SUN_FLASH, at, at, size.toFloat(), Element.SUNFIRE.color, LIFE, seed)
        repeat(48) {
            val start = at.add(SpellGeom.spread(r, 0.6).scale(size * 0.55 * r.nextDouble()))
            val rise = 1.2 + 1.4 * r.nextDouble()
            later(level, (r.nextDouble() * 0.3 * LIFE).toInt()) { mote(level, SpellMote.SUNFIRE_EMBER, start, Vec3(0.0, rise * 0.1, 0.0)) }
        }
    }

    private fun purity(level: ClientLevel, at: Vec3, size: Double, seed: Int, r: Random) {
        SpellVfxClient.addLocal(SpellVfx.Kind.SHATTER, at, at, size.toFloat(), 0xFFFFFF, LIFE, seed)
        repeat(24) {
            val d = SpellGeom.spread(r)
            val speed = fling(size * (0.4 + 0.8 * r.nextDouble()), 16, 0.96f)
            mote(level, SpellMote.PURITY_GLITTER, at, d.scale(speed).add(0.0, 0.4 / LIFE, 0.0))
        }
    }

    private fun voidRift(level: ClientLevel, at: Vec3, size: Double, seed: Int, r: Random) {
        SpellVfxClient.addLocal(SpellVfx.Kind.RIFT, at, at, size.toFloat(), Element.VOID.color, LIFE, seed)
        repeat(5) {
            val p = at.add(SpellGeom.spread(r, 0.6, 0.2).scale(size * (0.75 + 0.45 * r.nextDouble())))
            later(level, ((0.1 + r.nextDouble() * 0.35) * LIFE).toInt()) { mote(level, SpellMote.YGANN_EYE, p) }
        }
        inward(level, at, size * 1.2, SpellMote.VOID_INWARD, 24, r, LIFE / 2)
    }

    private fun chaos(level: ClientLevel, at: Vec3, size: Double, r: Random) {
        repeat(5) {
            val end = at.add(SpellGeom.spread(r, 0.5).scale(size * (0.6 + 0.5 * r.nextDouble())))
            SpellVfxClient.addLocal(SpellVfx.Kind.ARC, at, end, 1f, Element.CHAOS.color, LIFE, r.nextInt())
        }
        repeat(22) {
            val d = SpellGeom.spread(r, 0.7)
            mote(level, SpellMote.CHAOS_GLYPH, at, d.scale(fling(size * (0.3 + 0.8 * r.nextDouble()), 14, 0.96f)))
        }
        repeat(16) {
            mote(level, SpellMote.CHAOS_CRIT, at, SpellGeom.spread(r).scale(fling(size * 0.9, 8, 0.96f)))
        }
    }

    fun blast(level: ClientLevel, kind: SpellVfx.Kind, at: Vec3, radius: Double, seed: Int) {
        val r = Random(seed)
        if (kind == SpellVfx.Kind.BEAM_EXPLOSION) {
            repeat(8) {
                val p = at.add(SpellGeom.spread(r, 0.6).scale(radius * 0.35 * r.nextDouble()))
                val across = radius * (0.5 + 0.3 * r.nextDouble())
                later(level, (r.nextDouble() * 4).toInt()) { add(level, ParticleTypes.EXPLOSION, p, Vec3(2 * (1 - across / 4), 0.0, 0.0)) }
            }
        } else {
            repeat(40) { mote(level, SpellMote.PURITY_GLITTER, at, SpellGeom.spread(r).scale(fling(radius, 16, 0.96f))) }
        }
    }

    private fun heat(level: ClientLevel, at: Vec3, size: Double, r: Random) {
        repeat(7) {
            val p = at.add(SpellGeom.spread(r, 0.6).scale(size * 0.45 * r.nextDouble()))
            // Vanilla quirk: the explosion particle is 4 * (1 - x / 2) across, where x is its x speed.
            val across = size * (0.9 + 0.5 * r.nextDouble())
            later(level, (r.nextDouble() * 0.25 * LIFE).toInt()) { add(level, ParticleTypes.EXPLOSION, p, Vec3(2 * (1 - across / 4), 0.0, 0.0)) }
        }
        repeat(26) {
            val d = SpellGeom.spread(r, 0.7, 0.2)
            mote(level, SpellMote.FLAME, at, d.scale(fling(size * (0.5 + 0.9 * r.nextDouble()), 12, 0.96f)))
        }
        repeat(9) {
            val d = Vec3(SpellGeom.gauss(r), 0.4 + Math.abs(SpellGeom.gauss(r)) * 0.4, SpellGeom.gauss(r)).normalize()
            val p = at.add(d.scale(size * 0.3))
            later(level, ((0.15 + 0.2 * r.nextDouble()) * LIFE).toInt()) {
                mote(level, SpellMote.HEAT_SMOKE, p, d.scale(size * 0.06).add(0.0, 0.14, 0.0))
            }
        }
        repeat(30) {
            val d = Vec3(SpellGeom.gauss(r), Math.abs(SpellGeom.gauss(r)) * 0.8 + 0.3, SpellGeom.gauss(r)).normalize()
            mote(level, SpellMote.HEAT_EMBER, at, d.scale(0.08 + 0.1 * r.nextDouble()))
        }
    }

    private fun growth(level: ClientLevel, at: Vec3, size: Double, r: Random) {
        repeat(34) {
            val angle = r.nextDouble() * Math.PI * 2
            val rad = size * (0.2 + 1.1 * r.nextDouble()) * (0.5 + 0.5 * r.nextDouble())
            val out = fling(rad, 18, 0.9f)
            val p = at.add(0.0, (r.nextDouble() - 0.4) * size, 0.0)
            add(level, EKParticles.leafChip(), p, Vec3(cos(angle) * out - sin(angle) * out * 0.5, 0.05, sin(angle) * out + cos(angle) * out * 0.5))
        }
        repeat(14) {
            val d = Vec3(SpellGeom.gauss(r), Math.abs(SpellGeom.gauss(r)) + 0.3, SpellGeom.gauss(r)).normalize()
            mote(level, SpellMote.GROWTH_GLINT, at, d.scale(fling(size * 0.9 * r.nextDouble(), 14, 0.96f)).add(0.0, 0.4 / LIFE, 0.0))
        }
    }

    private fun blood(level: ClientLevel, at: Vec3, size: Double, r: Random) {
        repeat(36) {
            val d = Vec3(SpellGeom.gauss(r), Math.abs(SpellGeom.gauss(r)) * 0.8 + 0.3, SpellGeom.gauss(r)).normalize()
            mote(level, SpellMote.BLOOD_DROP, at, d.scale(0.12 + 0.12 * r.nextDouble()))
        }
        repeat(10) {
            val d = SpellGeom.spread(r, 0.5)
            mote(level, SpellMote.BLOOD_HAZE, at, d.scale(size * 0.45 / 60).add(0.0, -0.35 / 60, 0.0))
        }
    }

    private fun inward(level: ClientLevel, at: Vec3, radius: Double, kind: SpellMote, count: Int, r: Random, ticks: Int) {
        repeat(count) { i ->
            val d = SpellGeom.spread(r)
            later(level, i * ticks / count) { mote(level, kind, at.add(d.scale(radius)), d.scale(-radius / kind.life)) }
        }
    }
}
