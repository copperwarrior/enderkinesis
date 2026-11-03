package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.ParticleStatus
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.registry.EKParticles
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pressure-washer spray where the River hits: a flat fan of fast streaks, fine droplets, a foam spot, haze, and wet
 *  patches that grow on the hit face and drip. Streaks live in fixed arrays, not particles. Client thread only. */
object RiverWasher {
    private const val CAPACITY = 256
    private const val STREAKS_PER_TICK = 32
    private const val DROPLET_EVERY = 4
    private const val FOAM_PER_TICK = 2
    private const val DRIP_CHANCE = 0.35f
    private const val MAX_DISTANCE_SQR = 64.0 * 64.0

    private const val SPEED_MIN = 0.45
    private const val SPEED_VAR = 0.5
    private const val LIFT_MIN = 0.05
    private const val LIFT_VAR = 0.45
    private const val LIFE_MIN = 5
    private const val LIFE_VAR = 6
    private const val DRAG = 0.8f
    private const val GRAVITY = 0.02f
    private const val START_OFFSET = 0.03
    private const val DROPLET_SPEED_SHARE = 0.4

    /** Streak length in ticks of travel, and half-width in blocks. */
    private const val STREAK_LENGTH = 2.6f
    private const val STREAK_HALF_WIDTH = 0.008
    private const val STREAK_ALPHA = 0.85f
    private const val HEAD_R = 158; private const val HEAD_G = 214; private const val HEAD_B = 255
    private const val TAIL_R = 77; private const val TAIL_G = 148; private const val TAIL_B = 250

    private const val MAX_PATCHES = 16
    private const val PATCH_MERGE_SQR = 0.6 * 0.6
    private const val PATCH_START = 0.2
    private const val PATCH_GROWTH = 0.02
    private const val PATCH_MAX = 1.3
    private const val PATCH_ALPHA = 0.5f
    private const val PATCH_HOLD_TICKS = 20
    private const val PATCH_FADE_TICKS = 200
    private const val PATCH_SIDES = 24
    private const val PATCH_LIFT = 0.002

    /** One caster's streaks, in a ring: a new streak takes the oldest slot. */
    private class Spray {
        val x = DoubleArray(CAPACITY); val y = DoubleArray(CAPACITY); val z = DoubleArray(CAPACITY)
        val vx = FloatArray(CAPACITY); val vy = FloatArray(CAPACITY); val vz = FloatArray(CAPACITY)
        val age = IntArray(CAPACITY); val life = IntArray(CAPACITY)
        var next = 0
        var lastTick = 0L
    }

    private class Patch(val x: Double, val y: Double, val z: Double, val nx: Double, val ny: Double, val nz: Double, val seed: Float) {
        var radius = PATCH_START
        var lastHit = 0L
    }

    private val sprays = Int2ObjectOpenHashMap<Spray>()
    private val patches = ArrayList<Patch>()
    private val cell = BlockPos.MutableBlockPos()
    private var lastLevel: ClientLevel? = null
    private var gameTick = 0L

    /** Call each tick of a River cast, with the impact point and the world normal of the hit surface. */
    fun spray(player: Player, impact: Vec3, normal: Vec3, onBlock: Boolean) {
        val mc = Minecraft.getInstance()
        if (mc.gameRenderer.mainCamera.position.distanceToSqr(impact) > MAX_DISTANCE_SQR) return
        val share = when (mc.options.particles().get()) {
            ParticleStatus.ALL -> 1.0
            ParticleStatus.DECREASED -> 0.5
            else -> 0.25
        }
        val level = player.level()
        val rnd = level.random
        val s = sprays.computeIfAbsent(player.id) { Spray() }
        s.lastTick = gameTick
        val ref = if (kotlin.math.abs(normal.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val u = normal.cross(ref).normalize()
        val w = normal.cross(u)
        val full = share >= 1.0
        repeat((STREAKS_PER_TICK * share).toInt()) { i ->
            val th = rnd.nextDouble() * Math.PI * 2
            val r = rnd.nextDouble()
            val lift = LIFT_MIN + r * r * LIFT_VAR
            val across = cos(lift)
            val dx = (u.x * cos(th) + w.x * sin(th)) * across + normal.x * sin(lift)
            val dy = (u.y * cos(th) + w.y * sin(th)) * across + normal.y * sin(lift)
            val dz = (u.z * cos(th) + w.z * sin(th)) * across + normal.z * sin(lift)
            val sp = SPEED_MIN + rnd.nextDouble() * SPEED_VAR
            val px = impact.x + normal.x * START_OFFSET
            val py = impact.y + normal.y * START_OFFSET
            val pz = impact.z + normal.z * START_OFFSET
            add(s, px, py, pz, (dx * sp).toFloat(), (dy * sp).toFloat(), (dz * sp).toFloat(), LIFE_MIN + rnd.nextInt(LIFE_VAR + 1))
            if (i % DROPLET_EVERY == 0) {
                val k = sp * DROPLET_SPEED_SHARE
                level.addParticle(EKParticles.riverDroplet(), px, py, pz, dx * k, dy * k, dz * k)
            }
        }
        if (!full) return
        repeat(FOAM_PER_TICK) {
            level.addParticle(EKParticles.riverFoam(), impact.x + normal.x * 0.04, impact.y + normal.y * 0.04, impact.z + normal.z * 0.04, 0.0, 0.0, 0.0)
        }
        val th = rnd.nextDouble() * Math.PI * 2
        val ring = u.scale(cos(th) * HAZE_RING).add(w.scale(sin(th) * HAZE_RING))
        level.addParticle(
            EKParticles.riverMist(),
            impact.x + normal.x * 0.15 + ring.x, impact.y + normal.y * 0.15 + ring.y, impact.z + normal.z * 0.15 + ring.z,
            normal.x * HAZE_SPEED + ring.x * HAZE_SPREAD, normal.y * HAZE_SPEED + ring.y * HAZE_SPREAD + HAZE_RISE,
            normal.z * HAZE_SPEED + ring.z * HAZE_SPREAD,
        )
        if (onBlock) wet(level, impact, normal, rnd.nextFloat())
    }

    private const val HAZE_RING = 0.6
    private const val HAZE_SPEED = 0.02
    private const val HAZE_SPREAD = 0.05
    private const val HAZE_RISE = 0.01

    private fun add(s: Spray, px: Double, py: Double, pz: Double, vx: Float, vy: Float, vz: Float, life: Int) {
        val i = s.next
        s.next = (i + 1) % CAPACITY
        s.x[i] = px; s.y[i] = py; s.z[i] = pz
        s.vx[i] = vx; s.vy[i] = vy; s.vz[i] = vz
        s.age[i] = 0; s.life[i] = life
    }

    private fun wet(level: Level, impact: Vec3, normal: Vec3, roll: Float) {
        var patch: Patch? = null
        for (p in patches) {
            if (p.nx != normal.x || p.ny != normal.y || p.nz != normal.z) continue
            val dx = p.x - impact.x; val dy = p.y - impact.y; val dz = p.z - impact.z
            if (dx * dx + dy * dy + dz * dz < PATCH_MERGE_SQR) { patch = p; break }
        }
        if (patch == null) {
            if (patches.size >= MAX_PATCHES) patches.removeAt(0)
            patch = Patch(impact.x, impact.y, impact.z, normal.x, normal.y, normal.z, roll * 10f)
            patches += patch
        }
        patch.radius = minOf(PATCH_MAX, patch.radius + PATCH_GROWTH)
        patch.lastHit = gameTick
        // A wall patch drips from its lower edge; a floor patch does not drip.
        if (kotlin.math.abs(normal.y) < 0.9 && level.random.nextFloat() < DRIP_CHANCE) {
            val down = Vec3(0.0, -1.0, 0.0).subtract(normal.scale(-normal.y)).normalize()
            val side = normal.cross(down)
            val off = (level.random.nextDouble() - 0.5) * patch.radius * 0.6
            level.addParticle(
                ParticleTypes.FALLING_WATER,
                patch.x + down.x * patch.radius * 0.45 + side.x * off + normal.x * 0.03,
                patch.y + down.y * patch.radius * 0.45 + side.y * off,
                patch.z + down.z * patch.radius * 0.45 + side.z * off + normal.z * 0.03,
                0.0, 0.0, 0.0,
            )
        }
    }

    /** Call once each client tick. */
    fun tick(level: ClientLevel) {
        if (level !== lastLevel) {
            sprays.clear()
            patches.clear()
            lastLevel = level
        }
        gameTick++
        val it = sprays.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            var alive = false
            for (i in 0 until CAPACITY) {
                if (s.age[i] >= s.life[i]) continue
                s.age[i]++
                s.vy[i] -= GRAVITY
                s.x[i] += s.vx[i]; s.y[i] += s.vy[i]; s.z[i] += s.vz[i]
                s.vx[i] *= DRAG; s.vy[i] *= DRAG; s.vz[i] *= DRAG
                cell.set(s.x[i], s.y[i], s.z[i])
                @Suppress("DEPRECATION") // blocksMotion is the cheap solid test in 1.20.1.
                if (level.getBlockState(cell).blocksMotion()) s.age[i] = s.life[i]
                if (s.age[i] < s.life[i]) alive = true
            }
            if (!alive && gameTick - s.lastTick > PATCH_HOLD_TICKS) it.remove()
        }
        patches.removeIf { gameTick - it.lastHit > PATCH_HOLD_TICKS + PATCH_FADE_TICKS }
    }

    /** Call in the spell pass: the pose is camera-relative world. */
    @Suppress("LongParameterList") // Same hook signature as the other spell-pass renderers.
    fun render(pose: PoseStack, buffers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, partialTick: Float) {
        if (sprays.isEmpty() && patches.isEmpty()) return
        val c = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
        val m = pose.last().pose()
        for (p in patches) drawPatch(c, m, p, camX, camY, camZ, partialTick)
        for (s in sprays.values) drawStreaks(c, m, s, camX, camY, camZ, partialTick)
        (buffers as? MultiBufferSource.BufferSource)?.endBatch(SpellVfxRenderTypes.SHADE)
    }

    @Suppress("LongParameterList") // Raw camera values: no vector for each streak.
    private fun drawStreaks(c: VertexConsumer, m: Matrix4f, s: Spray, camX: Double, camY: Double, camZ: Double, pt: Float) {
        for (i in 0 until CAPACITY) {
            val life = s.life[i]
            if (s.age[i] >= life) continue
            val hx = s.x[i] + s.vx[i] * pt - camX
            val hy = s.y[i] + s.vy[i] * pt - camY
            val hz = s.z[i] + s.vz[i] * pt - camZ
            val ax = -s.vx[i] * STREAK_LENGTH; val ay = -s.vy[i] * STREAK_LENGTH; val az = -s.vz[i] * STREAK_LENGTH
            // Width across the streak, facing the camera at the origin.
            var wx = ay * hz - az * hy; var wy = az * hx - ax * hz; var wz = ax * hy - ay * hx
            val wl = sqrt(wx * wx + wy * wy + wz * wz)
            if (wl < 1.0e-9) continue
            wx *= STREAK_HALF_WIDTH / wl; wy *= STREAK_HALF_WIDTH / wl; wz *= STREAK_HALF_WIDTH / wl
            val a = STREAK_ALPHA * (1f - (s.age[i] + pt) / life).coerceIn(0f, 1f)
            SpellShapes.vertex(c, m, hx - wx, hy - wy, hz - wz, HEAD_R, HEAD_G, HEAD_B, a)
            SpellShapes.vertex(c, m, hx + wx, hy + wy, hz + wz, HEAD_R, HEAD_G, HEAD_B, a)
            SpellShapes.vertex(c, m, hx + ax + wx, hy + ay + wy, hz + az + wz, TAIL_R, TAIL_G, TAIL_B, 0f)
            SpellShapes.vertex(c, m, hx + ax - wx, hy + ay - wy, hz + az - wz, TAIL_R, TAIL_G, TAIL_B, 0f)
        }
    }

    /** A dark fan with a ragged rim, flat on the hit face; quads with a repeated centre vertex. */
    @Suppress("LongParameterList") // Raw camera values, as the streaks.
    private fun drawPatch(c: VertexConsumer, m: Matrix4f, p: Patch, camX: Double, camY: Double, camZ: Double, pt: Float) {
        val since = gameTick - p.lastHit + pt
        val fade = (1f - ((since - PATCH_HOLD_TICKS) / PATCH_FADE_TICKS).coerceIn(0f, 1f))
        if (fade <= 0f) return
        val n = Vec3(p.nx, p.ny, p.nz)
        val ref = if (kotlin.math.abs(p.ny) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val u = n.cross(ref).normalize()
        val w = n.cross(u)
        val cx = p.x + p.nx * PATCH_LIFT - camX
        val cy = p.y + p.ny * PATCH_LIFT - camY
        val cz = p.z + p.nz * PATCH_LIFT - camZ
        val a = PATCH_ALPHA * fade
        var prevX = 0.0; var prevY = 0.0; var prevZ = 0.0
        for (k in 0..PATCH_SIDES) {
            val ang = k * Math.PI * 2 / PATCH_SIDES
            val r = p.radius * (0.82 + 0.1 * sin(ang * 5 + p.seed) + 0.06 * sin(ang * 11 + p.seed * 1.7))
            val ox = (u.x * cos(ang) + w.x * sin(ang)) * r
            val oy = (u.y * cos(ang) + w.y * sin(ang)) * r
            val oz = (u.z * cos(ang) + w.z * sin(ang)) * r
            if (k > 0) {
                SpellShapes.vertex(c, m, cx, cy, cz, WET_R, WET_G, WET_B, a)
                SpellShapes.vertex(c, m, cx + prevX, cy + prevY, cz + prevZ, WET_R, WET_G, WET_B, 0f)
                SpellShapes.vertex(c, m, cx + ox, cy + oy, cz + oz, WET_R, WET_G, WET_B, 0f)
                SpellShapes.vertex(c, m, cx, cy, cz, WET_R, WET_G, WET_B, a)
            }
            prevX = ox; prevY = oy; prevZ = oz
        }
    }

    private const val WET_R = 8
    private const val WET_G = 10
    private const val WET_B = 16
}
