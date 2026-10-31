package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellVfx
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Each flow uses the last known positions when its creatures go out of sight. Times are in ticks.
object EffectFlows {
    private class Drain(val id: Int, val at: Vec3, val height: Float, val rgb: Int, val start: Long)
    private class Sling(val from: Int, val to: Int, val a: Vec3, val b: Vec3, val rgb: Int, val start: Long)
    private class Tether(var core: Vec3, var at: Vec3, var until: Long, val start: Long)

    // Client thread only.
    private val drains = ArrayList<Drain>()
    private val slings = ArrayList<Sling>()
    private val tethers = HashMap<Int, Tether>()

    fun isEmpty(): Boolean = tethers.isEmpty()

    fun add(level: ClientLevel, kind: SpellVfx.Kind, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int, seed: Int) {
        val now = SpellClock.now(level)
        when (kind) {
            SpellVfx.Kind.CLEANSE_DRAIN -> drains += Drain(seed, a, size, rgb, now)
            SpellVfx.Kind.SHUFFLE_SLING -> slings += Sling(seed, size.toInt(), a, b, rgb, now)
            SpellVfx.Kind.VOID_TETHER -> {
                val held = tethers[seed]
                if (held != null) { held.core = a; held.at = b; held.until = now + life }
                else tethers[seed] = Tether(a, b, now + life, now)
            }
            else -> {}
        }
    }

    fun reset() {
        drains.clear(); slings.clear(); tethers.clear()
    }

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        drains.removeIf { d -> drain(level, d, (now - d.start).toInt()) }
        slings.removeIf { s -> sling(level, s, (now - s.start).toInt()) }
        tethers.entries.removeIf { now > it.value.until }
    }

    /** Returns true when done. */
    private fun drain(level: ClientLevel, d: Drain, age: Int): Boolean {
        val entity = level.getEntity(d.id)
        val feet = entity?.position() ?: d.at
        val height = (entity?.bbHeight ?: d.height).toDouble()
        val half = (entity?.bbWidth ?: 0.6f) * 0.5
        val random = level.random
        if (age < DRAIN_DOWN) {
            val y = height * 0.95 * (1.0 - age.toDouble() / DRAIN_DOWN)
            repeat(2) {
                swirl(level, feet.x + (random.nextDouble() * 2 - 1) * half, feet.y + y + random.nextDouble() * 0.15, feet.z + (random.nextDouble() * 2 - 1) * half,
                    0.0, -DRAIN_SPEED, 0.0, d.rgb)
            }
        } else if (age == DRAIN_DOWN) {
            repeat(6) {
                level.addParticle(EKParticles.mote(SpellMote.PURITY_GLITTER), feet.x + (random.nextDouble() * 2 - 1) * (half + 0.2), feet.y + 0.05,
                    feet.z + (random.nextDouble() * 2 - 1) * (half + 0.2), (random.nextDouble() - 0.5) * 0.02, 0.04 + random.nextDouble() * 0.03, (random.nextDouble() - 0.5) * 0.02)
            }
        }
        return age >= DRAIN_DOWN
    }

    /** Returns true when done. */
    private fun sling(level: ClientLevel, s: Sling, age: Int): Boolean {
        if (age >= SpellVfx.SLING_TICKS) return true
        val from = level.getEntity(s.from)
        val to = level.getEntity(s.to)
        val a = chest(from, s.a)
        val b = chest(to, s.b)
        val ra = ring(from); val rb = ring(to)
        val toward = atan2(b.z - a.z, b.x - a.x)
        val random = level.random
        repeat(2) {
            val q = (age - random.nextDouble() * 1.5).coerceAtLeast(0.0)
            val p = slingPoint(q, a, b, ra, rb, toward)
            swirl(level, p.x + (random.nextDouble() - 0.5) * 0.06, p.y + (random.nextDouble() - 0.5) * 0.06, p.z + (random.nextDouble() - 0.5) * 0.06, 0.0, 0.005, 0.0, s.rgb)
        }
        return false
    }

    private fun slingPoint(q: Double, a: Vec3, b: Vec3, ra: Double, rb: Double, toward: Double): Vec3 = when {
        q < SLING_LOOP -> {
            val an = toward + q / SLING_LOOP * 2 * PI
            Vec3(a.x + cos(an) * ra, a.y + sin(q / SLING_LOOP * 2 * PI) * 0.15, a.z + sin(an) * ra)
        }
        q < SLING_LOOP + SLING_FLY -> {
            val u = (q - SLING_LOOP) / SLING_FLY
            val start = Vec3(a.x + cos(toward) * ra, a.y, a.z + sin(toward) * ra)
            val end = Vec3(b.x - cos(toward) * rb, b.y, b.z - sin(toward) * rb)
            start.lerp(end, u).add(0.0, sin(u * PI) * (0.6 + 0.12 * start.distanceTo(end)), 0.0)
        }
        else -> {
            val v = ((q - SLING_LOOP - SLING_FLY) / (SpellVfx.SLING_TICKS - SLING_LOOP - SLING_FLY)).coerceIn(0.0, 1.0)
            val an = toward + PI + v * 2 * PI
            val r = rb * (1 - v)
            Vec3(b.x + cos(an) * r, b.y - v * 0.3, b.z + sin(an) * r)
        }
    }

    private fun chest(entity: Entity?, fallback: Vec3): Vec3 =
        entity?.let { it.position().add(0.0, it.bbHeight * 0.6, 0.0) } ?: fallback.add(0.0, 1.1, 0.0)

    private fun ring(entity: Entity?): Double = (entity?.bbWidth ?: 0.6f) * 0.5 + 0.4

    fun render(m: Matrix4f, buffers: MultiBufferSource, view: SpellView, level: ClientLevel, time: Float) {
        if (tethers.isEmpty()) return
        val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for ((id, t) in tethers) {
            val entity = level.getEntity(id)
            val at = entity?.position() ?: t.at
            val height = (entity?.bbHeight ?: 1.8f).toDouble()
            val fade = (minOf(time - t.start, t.until - time) / TETHER_FADE).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            for (k in 0 until 2) {
                val from = at.add(0.0, height * (0.4 + 0.35 * k), 0.0)
                val along = t.core.subtract(from)
                if (along.lengthSqr() < 1.0e-4) continue
                val (u, v) = SpellGeom.basis(along.normalize())
                val pts = ArrayList<Vec3>(TETHER_POINTS + 1)
                for (i in 0..TETHER_POINTS) {
                    val f = i.toDouble() / TETHER_POINTS
                    val wave = (1 - f) * TETHER_WAVER
                    val ph = f * 9 + time * 0.5 + k * 2.1
                    pts += from.add(along.scale(f)).add(u.scale(sin(ph) * wave)).add(v.scale(cos(ph * 0.7) * wave * 0.6))
                }
                val flicker = 0.75f + 0.25f * sin(time * 0.9f + k * 2f)
                SpellShapes.strip(shade, m, view, pts, { 0.06 }, VOID_DARK, 0.55f * fade * flicker, true)
                SpellShapes.strip(glow, m, view, pts, { 0.022 }, VOID_SHEEN, 0.45f * fade * flicker, false)
            }
        }
    }

    private fun swirl(level: ClientLevel, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double, rgb: Int) {
        val p = Minecraft.getInstance().particleEngine.createParticle(EKParticles.mote(SpellMote.EFFECT_SWIRL), x, y, z, dx, dy, dz) ?: return
        p.setColor(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f)
    }

    private const val DRAIN_DOWN = 9
    private const val DRAIN_SPEED = 0.07
    private const val SLING_LOOP = 10.0
    private const val SLING_FLY = 8.0
    private const val TETHER_POINTS = 14
    private const val TETHER_WAVER = 0.12
    private const val TETHER_FADE = 3f
    private const val VOID_DARK = 0x2A1238
    private const val VOID_SHEEN = 0x8A4AC0
}
