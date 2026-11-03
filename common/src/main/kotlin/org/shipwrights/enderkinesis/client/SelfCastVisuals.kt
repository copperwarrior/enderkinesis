package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.spell.SpellVisuals
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Purity's self-cast visual is Cleanse's drain in [EffectFlows]. Times are in ticks. */
object SelfCastVisuals {
    private class Cast(val element: Element, val id: Int, val at: Vec3, val start: Long, val life: Int)

    /** The entry order must agree with the cloak indices in `spell_cloak_volume`. */
    private enum class CloakStyle(val bit: Int, val dark: Boolean, val alpha: Int, val reach: Double, val motes: List<SpellMote>) {
        FLAME(SpellVisuals.FLAME_CLOAK, false, 230, 0.75, List(4) { SpellMote.CLOAK_FLAME }),
        FROST(SpellVisuals.FROST_CLOAK, false, 170, 0.75, List(5) { SpellMote.CLOAK_SNOW } + SpellMote.CLOAK_GLINT),
        LIGHTNING(SpellVisuals.LIGHTNING_CLOAK, false, 230, 0.75, List(3) { SpellMote.CLOAK_SPARK }),
        BLOOD(SpellVisuals.ABSORPTION_CLOAK, true, 200, 0.4, List(3) { SpellMote.CLOAK_BLOOD }),
        POISON(SpellVisuals.POISON_CLOAK, false, 190, 0.7, List(3) { SpellMote.CLOAK_SPORE }),
        VOID(SpellVisuals.VOID_CLOAK, true, 220, 0.75, List(3) { SpellMote.CLOAK_VOID } + SpellMote.CLOAK_VOID_GLINT),
    }

    // Client thread only.
    private val casts = ArrayList<Cast>()

    fun isEmpty(): Boolean = casts.isEmpty() && !SpellVisuals.anyClient(SpellVisuals.HALO or SpellVisuals.CLOAKS)

    fun add(level: ClientLevel, kind: SpellVfx.Kind, a: Vec3, b: Vec3, size: Float, life: Int, seed: Int) {
        val now = SpellClock.now(level)
        when (kind) {
            SpellVfx.Kind.SELF_CAST -> Element.entries.getOrNull(size.toInt())?.let { casts += Cast(it, seed, a, now, life) }
            else -> {}
        }
    }

    fun reset() {
        casts.clear()
    }

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        casts.removeIf { c -> now - c.start > c.life }
        for (c in casts) {
            val entity = level.getEntity(c.id)
            val age = (now - c.start).toInt()
            when (c.element) {
                Element.GENESIS -> spiral(level, entity, c.at, age, c.life)
                Element.GRAVITY -> if (age == PRESS_TICKS) settle(level, entity?.position() ?: c.at, entity)
                else -> {}
            }
        }
        if (SpellVisuals.anyClient(SpellVisuals.CLOAKS)) {
            for (entity in level.entitiesForRendering()) {
                val bits = SpellVisuals.clientBits(entity.id)
                if (bits and SpellVisuals.CLOAKS == 0) continue
                for (style in CloakStyle.entries) {
                    if (bits and style.bit == 0) continue
                    val density = castDensity(entity.id, style.bit)
                    for (mote in style.motes) {
                        var n = density
                        while (n > 0f) {
                            if (n >= 1f || level.random.nextFloat() < n) whirl(level, entity, mote, style.reach)
                            n -= 1f
                        }
                    }
                    if (level.random.nextFloat() >= density) continue
                    when (style) {
                        CloakStyle.FLAME -> flameCloak(level, entity, now)
                        CloakStyle.LIGHTNING -> if (now % CRACKLE_EVERY == 0L) crackle(level, entity, style.reach)
                        CloakStyle.POISON -> if (now % SPORE_EVERY == 0L) whirl(level, entity, SpellMote.ROT_SPORE, style.reach)
                        else -> {}
                    }
                }
            }
        }
    }

    private fun flameCloak(level: ClientLevel, entity: Entity, now: Long) {
        val r = level.random
        val an = r.nextDouble() * 2 * PI
        val d = entity.bbWidth * 0.5 + r.nextDouble() * 0.8
        mote(level, SpellMote.EMBER_FLAME, entity.x + cos(an) * d, entity.y + entity.bbHeight * (0.25 + 0.4 * r.nextDouble()), entity.z + sin(an) * d,
            -sin(an) * 0.04, 0.06 + r.nextDouble() * 0.04, cos(an) * 0.04)
        if (now % 4 == 0L) {
            mote(level, SpellMote.SPRAY_SMOKE, entity.x + cos(an) * d * 0.6, entity.y + entity.bbHeight * 0.8, entity.z + sin(an) * d * 0.6,
                0.0, 0.06, 0.0)
        }
    }

    /** [visual] is a cloak bit or the Halo bit. */
    fun castDensity(id: Int, visual: Int): Float =
        (SpellVisuals.clientCastMagnitude(id, visual) * CLOAK_DENSITY_PER_MAGNITUDE).coerceIn(CLOAK_DENSITY_PER_MAGNITUDE, CLOAK_DENSITY_MAX)

    private fun crackle(level: ClientLevel, entity: Entity, reach: Double) {
        val r = level.random
        fun point(an: Double): Vec3 {
            val d = entity.bbWidth * 0.5 + 0.15 + r.nextDouble() * reach
            return Vec3(entity.x + cos(an) * d, entity.y + entity.bbHeight * (0.25 + 0.4 * r.nextDouble()), entity.z + sin(an) * d)
        }
        val an = r.nextDouble() * 2 * PI
        SpellVfxClient.addLocal(SpellVfx.Kind.ARC, point(an), point(an + 0.6 + r.nextDouble() * 0.8), 1f, Element.CHAOS.color, CRACKLE_TICKS, r.nextInt())
    }

    private fun whirl(level: ClientLevel, entity: Entity, mote: SpellMote, reach: Double) {
        val r = level.random
        val an = r.nextDouble() * 2 * PI
        val d = entity.bbWidth * 0.5 + 0.1 + r.nextDouble() * reach
        val v = SpellMote.ORBIT_SPIN * d
        mote(level, mote, entity.x + cos(an) * d, entity.y + entity.bbHeight * (0.2 + 0.45 * r.nextDouble()), entity.z + sin(an) * d,
            -sin(an) * v, 0.0, cos(an) * v)
    }

    private fun spiral(level: ClientLevel, entity: Entity?, at: Vec3, age: Int, life: Int) {
        val feet = entity?.position() ?: at
        val height = (entity?.bbHeight ?: 1.8f).toDouble()
        val radius = (entity?.bbWidth ?: 0.6f) * 0.5 + 0.45
        val f = age.toDouble() / life
        for (k in 0 until 2) {
            val an = age * 0.55 + k * PI
            val x = feet.x + cos(an) * radius; val z = feet.z + sin(an) * radius
            val y = feet.y + f * (height + 0.3)
            mote(level, SpellMote.GROWTH_GLINT, x, y, z, 0.0, 0.02, 0.0)
            if ((age + k) % 3 == 0) level.addParticle(EKParticles.leafChip(), x, y, z, 0.0, 0.01, 0.0)
        }
    }

    private fun settle(level: ClientLevel, feet: Vec3, entity: Entity?) {
        val radius = (entity?.bbWidth ?: 0.6f) * 0.5 + 0.2
        for (k in 0 until 14) {
            val an = k / 14.0 * 2 * PI
            mote(level, SpellMote.GRAVITY_DUST, feet.x + cos(an) * radius, feet.y + 0.05, feet.z + sin(an) * radius, cos(an) * 0.05, 0.02, sin(an) * 0.05)
        }
    }

    private fun mote(level: ClientLevel, mote: SpellMote, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double) =
        level.addParticle(EKParticles.mote(mote), x, y, z, dx, dy, dz)

    fun render(m: Matrix4f, buffers: MultiBufferSource, view: SpellView, level: ClientLevel, time: Float) {
        if (casts.isNotEmpty()) {
            val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
            for (c in casts) if (c.element == Element.GRAVITY) press(shade, m, level.getEntity(c.id), c.at, (time - c.start) / PRESS_TICKS, time)
        }
        if (SpellVisuals.anyClient(SpellVisuals.CLOAKS)) {
            for (entity in level.entitiesForRendering()) {
                val bits = SpellVisuals.clientBits(entity.id)
                if (bits and SpellVisuals.CLOAKS == 0) continue
                for (style in CloakStyle.entries) {
                    if (bits and style.bit != 0) queueCloak(entity, style)
                }
            }
        }
        if (SpellVisuals.anyClient(SpellVisuals.HALO) && SpellVfxRenderTypes.isAuraReady()) {
            val aura = buffers.getBuffer(SpellVfxRenderTypes.AURA)
            for (entity in level.entitiesForRendering()) {
                val bits = SpellVisuals.clientBits(entity.id)
                if (bits and SpellVisuals.HALO == 0) continue
                val feet = entity.getPosition(Minecraft.getInstance().frameTime)
                aura(aura, m, view, feet.add(0.0, entity.bbHeight * 0.5, 0.0), feet, SpellVisuals.haloRadius(bits), castDensity(entity.id, SpellVisuals.HALO), time)
            }
        }
    }

    private fun press(c: VertexConsumer, m: Matrix4f, entity: Entity?, at: Vec3, t: Float, time: Float) {
        if (t < 0f || t > 1f) return
        val feet = entity?.getPosition(Minecraft.getInstance().frameTime) ?: at
        val height = (entity?.bbHeight ?: 1.8f).toDouble()
        val half = (entity?.bbWidth ?: 0.6f) * 0.5 + 0.25 + 0.03 * sin(time * 1.0)
        val y = feet.y + (height + 0.4) * (1 - easeIn(t)) + 0.02
        val a = if (t < 0.85f) 1f else (1f - t) / 0.15f
        val corners = arrayOf(1.0 to 1.0, -1.0 to 1.0, -1.0 to -1.0, 1.0 to -1.0)
        fun p(x: Double, z: Double) = Vec3(feet.x + x, y, feet.z + z)
        for ((width, rgb, alpha) in arrayOf(Triple(0.14, 0x14101C, a * 0.5f), Triple(0.05, SpellGeom.darken(Element.GRAVITY.color, 0.25f), a * 0.86f))) {
            for (k in 0 until 4) {
                val (x0, z0) = corners[k]; val (x1, z1) = corners[(k + 1) % 4]
                SpellShapes.quad(c, m, p(x0 * (half - width), z0 * (half - width)), p(x1 * (half - width), z1 * (half - width)), p(x1 * half, z1 * half), p(x0 * half, z0 * half), rgb, 0f, 0f, alpha, alpha)
                SpellShapes.quad(c, m, p(x0 * half, z0 * half), p(x1 * half, z1 * half), p(x1 * (half + width), z1 * (half + width)), p(x0 * (half + width), z0 * (half + width)), rgb, alpha, alpha, 0f, 0f)
            }
        }
    }

    /** `rendertype_spell_aura` tells the shell, glow, pool and rays apart by their UVs. */
    private fun aura(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, feet: Vec3, reach: Double, density: Float, time: Float) {
        fun dense(alpha: Int): Int = (alpha * density).toInt().coerceIn(0, 255)
        val shell = AURA_SHELL * (1 + 0.01 * sin(time * 0.15))
        val shellAlpha = dense(AURA_ALPHA)
        for (i in 0 until AURA_RINGS) for (j in 0 until AURA_SEGMENTS) {
            shellVertex(c, m, centre, shell, auraIndex(i, j), shellAlpha)
            shellVertex(c, m, centre, shell, auraIndex(i + 1, j), shellAlpha)
            shellVertex(c, m, centre, shell, auraIndex(i + 1, j + 1), shellAlpha)
            shellVertex(c, m, centre, shell, auraIndex(i, j + 1), shellAlpha)
        }
        // Move the glow toward the camera so the body does not hide it; the shader clears it over the body.
        val l = view.left.scale(AURA_GLOW); val u = view.up.scale(AURA_GLOW)
        val toCam = view.cam.subtract(centre)
        val glow = if (toCam.lengthSqr() > 1.0) centre.add(toCam.normalize().scale(AURA_GLOW_FORWARD)) else centre
        auraVertex(c, m, glow.add(l).subtract(u), 4f, 4f, dense(AURA_GLOW_ALPHA), UP)
        auraVertex(c, m, glow.subtract(l).subtract(u), 5f, 4f, dense(AURA_GLOW_ALPHA), UP)
        auraVertex(c, m, glow.subtract(l).add(u), 5f, 5f, dense(AURA_GLOW_ALPHA), UP)
        auraVertex(c, m, glow.add(l).add(u), 4f, 5f, dense(AURA_GLOW_ALPHA), UP)
        val y = feet.y + 0.02
        auraVertex(c, m, Vec3(feet.x - AURA_POOL, y, feet.z - AURA_POOL), 2f, 2f, dense(AURA_POOL_ALPHA), UP)
        auraVertex(c, m, Vec3(feet.x - AURA_POOL, y, feet.z + AURA_POOL), 2f, 3f, dense(AURA_POOL_ALPHA), UP)
        auraVertex(c, m, Vec3(feet.x + AURA_POOL, y, feet.z + AURA_POOL), 3f, 3f, dense(AURA_POOL_ALPHA), UP)
        auraVertex(c, m, Vec3(feet.x + AURA_POOL, y, feet.z - AURA_POOL), 3f, 2f, dense(AURA_POOL_ALPHA), UP)
        for (k in 0 until AURA_RAYS) {
            val beat = sin(time * (0.05 + 0.04 * hash(k, 1)) + 6.283 * hash(k, 2))
            val alpha = dense((AURA_RAY_ALPHA * beat * beat).toInt())
            if (alpha < 4) continue
            // Golden spiral.
            val y = 1 - 2 * (k + 0.5) / AURA_RAYS
            val ring = Math.sqrt(1 - y * y)
            val turn = k * 2.39996 + time * 0.003
            val dir = Vec3(ring * cos(turn), y, ring * sin(turn))
            val base = centre.add(dir.scale(shell * 0.8))
            val tip = centre.add(dir.scale(reach * (0.85 + 0.25 * hash(k, 3))))
            val side = dir.cross(base.subtract(view.cam)).normalize()
            if (side.lengthSqr() < 1.0e-6) continue
            val wide = AURA_RAY_WIDTH * (0.7 + 0.6 * hash(k, 4))
            val b = side.scale(wide * 0.5); val t = side.scale(wide * 1.4)
            auraVertex(c, m, base.subtract(b), 0f, 0f, alpha, dir)
            auraVertex(c, m, base.add(b), 1f, 0f, alpha, dir)
            auraVertex(c, m, tip.add(t), 1f, 1f, alpha, dir)
            auraVertex(c, m, tip.subtract(t), 0f, 1f, alpha, dir)
        }
    }

    private fun queueCloak(entity: Entity, style: CloakStyle) {
        val partialTick = Minecraft.getInstance().frameTime.toDouble()
        SpellCloakVolume.queue(
            Mth.lerp(partialTick, entity.xo, entity.x), Mth.lerp(partialTick, entity.yo, entity.y), Mth.lerp(partialTick, entity.zo, entity.z),
            (entity.bbHeight * CLOAK_HEIGHT).toFloat(), entity.bbWidth * 0.5f,
            style.ordinal, style.dark, castDensity(entity.id, style.bit), style.alpha / 255f,
        )
    }

    private fun auraIndex(i: Int, j: Int) = i * (AURA_SEGMENTS + 1) + j

    private fun shellVertex(c: VertexConsumer, m: Matrix4f, centre: Vec3, shell: Double, k: Int, alpha: Int) {
        val nx = AURA_NX[k]; val ny = AURA_NY[k]; val nz = AURA_NZ[k]
        c.vertex(m, (centre.x + nx * shell).toFloat(), (centre.y + ny * shell).toFloat(), (centre.z + nz * shell).toFloat()).uv(-1f, 0f)
            .color(AURA_R, AURA_G, AURA_B, alpha).normal(nx.toFloat(), ny.toFloat(), nz.toFloat()).endVertex()
    }

    private fun auraVertex(c: VertexConsumer, m: Matrix4f, p: Vec3, u: Float, v: Float, alpha: Int, n: Vec3) {
        c.vertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).uv(u, v).color(AURA_R, AURA_G, AURA_B, alpha)
            .normal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat()).endVertex()
    }

    private fun hash(k: Int, salt: Int): Double {
        val x = sin(k * 127.1 + salt * 311.7) * 43758.5453
        return x - Math.floor(x)
    }

    private fun easeIn(t: Float): Double = (t * t).toDouble()

    /** Magnitude makes a cloak denser, not larger. */
    private const val CLOAK_DENSITY_PER_MAGNITUDE = 0.1f
    private const val CLOAK_DENSITY_MAX = 1.9f
    /** Above the eyes, so in first person the eye is inside the cylinder (the shader's TOP). */
    private const val CLOAK_HEIGHT = 0.95
    private const val CRACKLE_EVERY = 3L
    private const val CRACKLE_TICKS = 4
    private const val PRESS_TICKS = 8
    private const val SPORE_EVERY = 3L
    private const val AURA_RINGS = 16
    private const val AURA_SEGMENTS = 28
    private const val AURA_ALPHA = 220
    /** Not scaled by the Halo reach. */
    private const val AURA_SHELL = 1.6
    private const val AURA_GLOW = 1.5
    private const val AURA_GLOW_FORWARD = 0.9
    private const val AURA_GLOW_ALPHA = 230
    /** Half width. */
    private const val AURA_POOL = 2.6
    private const val AURA_POOL_ALPHA = 110
    private const val AURA_RAYS = 12
    private const val AURA_RAY_WIDTH = 0.4
    private const val AURA_RAY_ALPHA = 70
    private const val AURA_R = 0xFF
    private const val AURA_G = 0xE2
    private const val AURA_B = 0xA0
    private val UP = Vec3(0.0, 1.0, 0.0)
    private val AURA_NX = DoubleArray((AURA_RINGS + 1) * (AURA_SEGMENTS + 1)) { auraNormal(it, 0) }
    private val AURA_NY = DoubleArray((AURA_RINGS + 1) * (AURA_SEGMENTS + 1)) { auraNormal(it, 1) }
    private val AURA_NZ = DoubleArray((AURA_RINGS + 1) * (AURA_SEGMENTS + 1)) { auraNormal(it, 2) }

    private fun auraNormal(k: Int, axis: Int): Double {
        val th = PI * (k / (AURA_SEGMENTS + 1)) / AURA_RINGS
        val ph = 2 * PI * (k % (AURA_SEGMENTS + 1)) / AURA_SEGMENTS
        return when (axis) {
            0 -> sin(th) * cos(ph)
            1 -> cos(th)
            else -> sin(th) * sin(ph)
        }
    }
}
