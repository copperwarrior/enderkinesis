package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
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

/**
 * What a self-cast looks like on its caster:
 *
 * - **The cloaks** (Heat, Cold, Chaos, Blood, Poison, Void): while one lasts, a wide flat ring
 *   whirls round your hips, reaching out about a body's length each side (Blood's stays close),
 *   with a thin layer clinging to you up to your shoulders ([SpellVfxRenderTypes.CLOAK], and
 *   [SpellVfxRenderTypes.CLOAK_DARK] for Blood's and Void's). Each is a volume: drawn on a
 *   cylinder round its wearer, its shader marching each view ray through the ring inside (see
 *   `rendertype_spell_cloak`). Motes whirl round in each, the way it turns ([CloakStyle]):
 *   flames, with sparks and a little smoke flying off; snow and glinting frost; sparks, with
 *   little arcs crackling across the ring; blood mist; spores; dark motes and violet glints.
 *   Their bites are drawn where they're dealt: Chaos's arcs, Blood's streams, Void's lashes.
 * - **Growth:** green glints and leaves spiral up round you from the ground.
 * - **Gravity:** Gravity's dark frame presses down over you, and dust puffs at your feet.
 * - **Sunfire:** while the Halo lasts, broad, soft veils of pale gold light sweep round you like
 *   comets, a glow on you and light pooled on the ground round you, and faint shafts reaching
 *   out as far as the Halo does, flaring and fading ([SpellVfxRenderTypes.AURA]).
 *
 * Purity's is Cleanse's drain ([EffectFlows]). Times are in ticks.
 */
object SelfCastVisuals {
    private class Cast(val element: Element, val id: Int, val at: Vec3, val start: Long, val life: Int)

    /** The cloaks, in the order `rendertype_spell_cloak` numbers them: each one's visual bit,
     *  whether it's dark (drawn over what's behind it), how strong, how far out its ring
     *  reaches past its wearer's sides, and the motes whirling round in it each tick. */
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

    // ---- Particles ----------------------------------------------------------------------------

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        casts.removeIf { c -> now - c.start > c.life }
        for (c in casts) {
            val entity = level.getEntity(c.id)
            val age = (now - c.start).toInt()
            when (c.element) {
                Element.GROWTH -> spiral(level, entity, c.at, age, c.life)
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
                    // As many motes as it's dense: each whole one a mote, the rest a chance of one.
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

    /** Heat's cloak: sparks thrown up off it, and a little smoke. */
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

    /** How dense entity [id]'s [visual] (a cloak's bit, or the Halo's) is drawn: 0.1 for each
     *  magnitude it was cast at, so faint at 1 and as made at 10. */
    fun castDensity(id: Int, visual: Int): Float =
        (SpellVisuals.clientCastMagnitude(id, visual) * CLOAK_DENSITY_PER_MAGNITUDE).coerceIn(CLOAK_DENSITY_PER_MAGNITUDE, CLOAK_DENSITY_MAX)

    /** Chaos's cloak: a little arc crackling across its ring, between two points in it. */
    private fun crackle(level: ClientLevel, entity: Entity, reach: Double) {
        val r = level.random
        fun point(an: Double): Vec3 {
            val d = entity.bbWidth * 0.5 + 0.15 + r.nextDouble() * reach
            return Vec3(entity.x + cos(an) * d, entity.y + entity.bbHeight * (0.25 + 0.4 * r.nextDouble()), entity.z + sin(an) * d)
        }
        val an = r.nextDouble() * 2 * PI
        SpellVfxClient.addLocal(SpellVfx.Kind.ARC, point(an), point(an + 0.6 + r.nextDouble() * 0.8), 1f, Element.CHAOS.color, CRACKLE_TICKS, r.nextInt())
    }

    /** A [mote] whirling round [entity] in its cloak's ring, up to [reach] past its sides, the way
     *  the cloak turns. */
    private fun whirl(level: ClientLevel, entity: Entity, mote: SpellMote, reach: Double) {
        val r = level.random
        val an = r.nextDouble() * 2 * PI
        val d = entity.bbWidth * 0.5 + 0.1 + r.nextDouble() * reach
        val v = SpellMote.ORBIT_SPIN * d
        mote(level, mote, entity.x + cos(an) * d, entity.y + entity.bbHeight * (0.2 + 0.45 * r.nextDouble()), entity.z + sin(an) * d,
            -sin(an) * v, 0.0, cos(an) * v)
    }

    /** Growth: glints and leaves winding up round you from the ground. */
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

    /** Gravity: dust thrown out round your feet as the frame lands. */
    private fun settle(level: ClientLevel, feet: Vec3, entity: Entity?) {
        val radius = (entity?.bbWidth ?: 0.6f) * 0.5 + 0.2
        for (k in 0 until 14) {
            val an = k / 14.0 * 2 * PI
            mote(level, SpellMote.GRAVITY_DUST, feet.x + cos(an) * radius, feet.y + 0.05, feet.z + sin(an) * radius, cos(an) * 0.05, 0.02, sin(an) * 0.05)
        }
    }

    private fun mote(level: ClientLevel, mote: SpellMote, x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double) =
        level.addParticle(EKParticles.mote(mote), x, y, z, dx, dy, dz)

    // ---- Shapes -------------------------------------------------------------------------------

    fun render(m: Matrix4f, buffers: MultiBufferSource, view: SpellView, level: ClientLevel, time: Float) {
        if (casts.isNotEmpty()) {
            val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
            for (c in casts) if (c.element == Element.GRAVITY) press(shade, m, level.getEntity(c.id), c.at, (time - c.start) / PRESS_TICKS, time)
        }
        if (SpellVisuals.anyClient(SpellVisuals.CLOAKS) && SpellVfxRenderTypes.isCloakReady() && buffers is MultiBufferSource.BufferSource) {
            for (entity in level.entitiesForRendering()) {
                val bits = SpellVisuals.clientBits(entity.id)
                if (bits and SpellVisuals.CLOAKS == 0) continue
                val feet = entity.getPosition(Minecraft.getInstance().frameTime)
                // Each wearer's cloaks are drawn on their own, round their feet.
                SpellVfxRenderTypes.cloakFeet = feet.subtract(view.cam)
                for (style in CloakStyle.entries) {
                    if (bits and style.bit == 0) continue
                    val type = if (style.dark) SpellVfxRenderTypes.CLOAK_DARK else SpellVfxRenderTypes.CLOAK
                    cloak(buffers.getBuffer(type), m, entity, feet, style, castDensity(entity.id, style.bit))
                    buffers.endBatch(type)
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

    /** Gravity's dark frame, the width of the caster, pressing down from above their head to
     *  their feet. */
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

    /** Sunfire's Halo round [centre]: the veiled shell, the glows on its wearer and the ground at
     *  their [feet], and rays out to [reach]; every part as [density] as the Halo is cast (each
     *  alpha scaled, up to full). The shader tells the parts apart by their UVs (see
     *  `rendertype_spell_aura`). */
    private fun aura(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, feet: Vec3, reach: Double, density: Float, time: Float) {
        fun dense(alpha: Int): Int = (alpha * density).toInt().coerceIn(0, 255)
        val shell = AURA_SHELL * (1 + 0.01 * sin(time * 0.15))
        for (i in 0 until AURA_RINGS) {
            val t0 = PI * i / AURA_RINGS; val t1 = PI * (i + 1) / AURA_RINGS
            for (j in 0 until AURA_SEGMENTS) {
                val p0 = 2 * PI * j / AURA_SEGMENTS; val p1 = 2 * PI * (j + 1) / AURA_SEGMENTS
                for ((th, ph) in arrayOf(t0 to p0, t1 to p0, t1 to p1, t0 to p1)) {
                    val n = Vec3(sin(th) * cos(ph), cos(th), sin(th) * sin(ph))
                    auraVertex(c, m, centre.add(n.scale(shell)), -1f, 0f, dense(AURA_ALPHA), n)
                }
            }
        }
        // The glow round the wearer, facing the camera, and in front of them so their body doesn't
        // hide it; the shader clears it over their body so they stay in sight.
        val l = view.left.scale(AURA_GLOW); val u = view.up.scale(AURA_GLOW)
        val toCam = view.cam.subtract(centre)
        val glow = if (toCam.lengthSqr() > 1.0) centre.add(toCam.normalize().scale(AURA_GLOW_FORWARD)) else centre
        auraVertex(c, m, glow.add(l).subtract(u), 4f, 4f, dense(AURA_GLOW_ALPHA), UP)
        auraVertex(c, m, glow.subtract(l).subtract(u), 5f, 4f, dense(AURA_GLOW_ALPHA), UP)
        auraVertex(c, m, glow.subtract(l).add(u), 5f, 5f, dense(AURA_GLOW_ALPHA), UP)
        auraVertex(c, m, glow.add(l).add(u), 4f, 5f, dense(AURA_GLOW_ALPHA), UP)
        // Light pooled on the ground round them.
        val y = feet.y + 0.02
        auraVertex(c, m, Vec3(feet.x - AURA_POOL, y, feet.z - AURA_POOL), 2f, 2f, dense(AURA_POOL_ALPHA), UP)
        auraVertex(c, m, Vec3(feet.x - AURA_POOL, y, feet.z + AURA_POOL), 2f, 3f, dense(AURA_POOL_ALPHA), UP)
        auraVertex(c, m, Vec3(feet.x + AURA_POOL, y, feet.z + AURA_POOL), 3f, 3f, dense(AURA_POOL_ALPHA), UP)
        auraVertex(c, m, Vec3(feet.x + AURA_POOL, y, feet.z - AURA_POOL), 3f, 2f, dense(AURA_POOL_ALPHA), UP)
        // Rays, each flaring up and fading on its own beat, the lot turning slowly.
        for (k in 0 until AURA_RAYS) {
            val beat = sin(time * (0.05 + 0.04 * hash(k, 1)) + 6.283 * hash(k, 2))
            val alpha = dense((AURA_RAY_ALPHA * beat * beat).toInt())
            if (alpha < 4) continue
            // Spread evenly over the sphere (a golden spiral), turned about the vertical.
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

    /**
     * The cloak of [style] round [entity] standing at [feet]: the cylinder its shader marches
     * through, [CLOAK_HEIGHT] of the entity's height, [CLOAK_REACH] out from its sides, with both
     * ends closed. Each vertex carries how high the cylinder reaches and how wide the entity is
     * as its UV, the style in its colour's red and its [density] in the green; the shader finds where on the cylinder each
     * fragment is from the feet it's given ([SpellVfxRenderTypes.cloakFeet]).
     */
    private fun cloak(c: VertexConsumer, m: Matrix4f, entity: Entity, feet: Vec3, style: CloakStyle, density: Float) {
        val green = (CLOAK_DENSITY_ONE * density).toInt().coerceIn(0, 255)
        val body = entity.bbWidth * 0.5
        val top = entity.bbHeight * CLOAK_HEIGHT
        val radius = body + CLOAK_REACH
        fun rim(i: Int, y: Double): Vec3 {
            val an = i * 2 * PI / CLOAK_SEGMENTS
            return Vec3(cos(an) * radius, y, sin(an) * radius)
        }
        fun vertex(local: Vec3) {
            c.vertex(m, (feet.x + local.x).toFloat(), (feet.y + local.y).toFloat(), (feet.z + local.z).toFloat())
                .uv(top.toFloat(), body.toFloat()).color(style.ordinal, green, 255, style.alpha).endVertex()
        }
        for (i in 0 until CLOAK_SEGMENTS) {
            vertex(rim(i, 0.0)); vertex(rim(i + 1, 0.0)); vertex(rim(i + 1, top)); vertex(rim(i, top))
            // The ends, as quads closing on their middles.
            for (y in doubleArrayOf(0.0, top)) {
                val middle = Vec3(0.0, y, 0.0)
                vertex(middle); vertex(rim(i, y)); vertex(rim(i + 1, y)); vertex(middle)
            }
        }
    }

    private fun auraVertex(c: VertexConsumer, m: Matrix4f, p: Vec3, u: Float, v: Float, alpha: Int, n: Vec3) {
        c.vertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).uv(u, v).color(AURA_R, AURA_G, AURA_B, alpha)
            .normal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat()).endVertex()
    }

    /** A fixed 0..1 number for [k] and [salt]. */
    private fun hash(k: Int, salt: Int): Double {
        val x = sin(k * 127.1 + salt * 311.7) * 43758.5453
        return x - Math.floor(x)
    }

    private fun easeIn(t: Float): Double = (t * t).toDouble()

    private const val CLOAK_SEGMENTS = 24
    /** How far out from the hitbox a cloak's cylinder reaches (the shaders' REACH). */
    private const val CLOAK_REACH = 0.95
    /** How dense a cloak or the Halo is drawn for each magnitude it's cast at, up to the most:
     *  thicker with magnitude, not bigger. As made at 10. */
    private const val CLOAK_DENSITY_PER_MAGNITUDE = 0.1f
    private const val CLOAK_DENSITY_MAX = 1.9f
    /** A density of 1, in a cloak's vertex colour's green (see `rendertype_spell_cloak`). */
    private const val CLOAK_DENSITY_ONE = 128f
    /** How far up the wearer a cloak's cylinder reaches: over their eyes, so in first person the
     *  eye is plainly inside it (the shader's TOP). */
    private const val CLOAK_HEIGHT = 0.95
    /** How often an arc crackles across a Lightning Cloak, and how long it lasts. */
    private const val CRACKLE_EVERY = 3L
    private const val CRACKLE_TICKS = 4
    private const val PRESS_TICKS = 8
    private const val SPORE_EVERY = 3L
    private const val AURA_RINGS = 16
    private const val AURA_SEGMENTS = 28
    private const val AURA_ALPHA = 220
    /** The shell's radius, whatever the Halo reaches: it sits round its wearer. */
    private const val AURA_SHELL = 1.6
    private const val AURA_GLOW = 1.5
    private const val AURA_GLOW_FORWARD = 0.9
    private const val AURA_GLOW_ALPHA = 230
    /** Half the width of the light pooled on the ground. */
    private const val AURA_POOL = 2.6
    private const val AURA_POOL_ALPHA = 110
    private const val AURA_RAYS = 12
    private const val AURA_RAY_WIDTH = 0.4
    private const val AURA_RAY_ALPHA = 70
    /** Pale gold, going white where it's brightest (in the shader). */
    private const val AURA_R = 0xFF
    private const val AURA_G = 0xE2
    private const val AURA_B = 0xA0
    private val UP = Vec3(0.0, 1.0, 0.0)
}
