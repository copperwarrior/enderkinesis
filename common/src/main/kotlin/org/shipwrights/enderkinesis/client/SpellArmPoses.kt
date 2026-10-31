package org.shipwrights.enderkinesis.client

import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.spell.Shape
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Runes, imbues and Area casts are not item uses, so [started] signals them. A use ending does not
 * tell a release from a switch away, so [released] signals a real release.
 */
object SpellArmPoses {
    private const val FULL_CHARGE_TICKS = 30.0
    private const val RELEASE_TICKS = 15.0
    private const val UNCHANNEL_TICKS = 10.0
    /** The release signal comes before the use ends, by up to this many ticks. */
    private const val RELEASE_SIGNAL_TICKS = 10.0
    private const val CROUCH_LEAN = 0.4

    private class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        fun lerp(o: V, t: Double) = V(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
        fun mirror() = V(x, -y, -z)
        companion object {
            fun of(part: ModelPart) = V(part.xRot.toDouble(), part.yRot.toDouble(), part.zRot.toDouble())
        }
    }

    /** A pose for a cast from the right arm; a null [body] keeps the vanilla body turn. */
    private class Pose(val cast: V, val other: V, val body: Double?)

    private fun sm(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
    private fun trap(u: Double, a: Double, b: Double, c: Double, d: Double) = sm((u - a) / (b - a)) * (1 - sm((u - c) / (d - c)))
    private fun bump(x: Double, w: Double) = exp(-(x * x) / (2 * w * w))
    private fun ease(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return 1 - (1 - t) * (1 - t) * (1 - t) }
    private fun tremor(t: Double, a: Double) = V(a * sin(t * 61), a * sin(t * 47 + 1), a * sin(t * 53 + 2))

    private fun curve(u: Double, vararg keys: Pair<Double, Double>): Double {
        val x0 = u.coerceIn(0.0, 1.0)
        var i = 0
        while (i < keys.size - 2 && x0 > keys[i + 1].first) i++
        fun v(j: Int) = keys[j.coerceIn(0, keys.size - 1)].second
        val x = (x0 - keys[i].first) / (keys[i + 1].first - keys[i].first)
        val p0 = v(i - 1); val p1 = v(i); val p2 = v(i + 1); val p3 = v(i + 2)
        return 0.5 * (2 * p1 + (p2 - p0) * x + (2 * p0 - 5 * p1 + 4 * p2 - p3) * x * x + (3 * p1 - p0 - 3 * p2 + p3) * x * x * x)
    }

    /** Unlike [curve], speed stays smooth across unevenly spaced keys and is zero at the two ends. */
    private fun glide(u: Double, vararg keys: Pair<Double, Double>): Double {
        val x0 = u.coerceIn(0.0, 1.0); val n = keys.size
        var i = 0
        while (i < n - 2 && x0 > keys[i + 1].first) i++
        fun slope(j: Int) = if (j <= 0 || j >= n - 1) 0.0 else (keys[j + 1].second - keys[j - 1].second) / (keys[j + 1].first - keys[j - 1].first)
        val (t0, p0) = keys[i]; val (t1, p1) = keys[i + 1]
        val h = t1 - t0; val x = (x0 - t0) / h; val x2 = x * x; val x3 = x2 * x
        return (2 * x3 - 3 * x2 + 1) * p0 + (x3 - 2 * x2 + x) * h * slope(i) + (-2 * x3 + 3 * x2) * p1 + (x3 - x2) * h * slope(i + 1)
    }

    private fun glide(u: Double, from: V, vararg keys: Pair<Double, V>): V {
        val all = listOf(0.0 to from) + keys
        fun axis(f: (V) -> Double) = glide(u, *all.map { it.first to f(it.second) }.toTypedArray())
        return V(axis { it.x }, axis { it.y }, axis { it.z })
    }

    private const val BOLT_TWIST = 0.35
    private val BOLT_DRAWN = V(0.55, 0.3, 1.2)
    private val BOLT_PULL = V(0.35, 0.15, -0.1)
    private val BOLT_THRUST = V(-1.65, -0.15, 0.1)
    private val BOLT_ACROSS = V(-1.05, -0.75, 0.0)
    private val BALANCE = V(-0.5, 0.35, -0.1)
    /** Solved for vanilla arms and the `handheld` third-person transform: the blade middle touches the scroll fist. */
    private val IMBUE_SCROLL = V(-1.15, 0.3, 0.0)
    private val IMBUE_MEET = V(-0.748, -0.762, 0.0)
    private val BEAM_BRACE = V(-1.42, 0.62, 0.0)

    private fun carried(rc: V, ro: V, t: Double): Pose {
        val g = bump((t % 6) - 3, 0.5)
        return Pose(rc + V(-0.12 * g, -0.1 * g, -0.02 * g), ro, null)
    }

    private fun charging(rc: V, ro: V, t: Double, held: Double): Pose {
        val s = held / 20
        val k = sm(s / 0.35); val c = (held / FULL_CHARGE_TICKS).coerceIn(0.0, 1.0)
        val full = if (held >= FULL_CHARGE_TICKS) 1.0 else 0.0
        val p = sm(c)
        val drawn = BOLT_DRAWN + V(BOLT_PULL.x * p, BOLT_PULL.y * p, BOLT_PULL.z * p)
        return Pose(rc.lerp(drawn, k) + tremor(t, 0.025 * full + 0.006 * c), ro.lerp(BALANCE, k), BOLT_TWIST * k * (0.7 + 0.3 * p))
    }

    private fun throwing(rc: V, ro: V, look: V, from: Pose, u: Double): Pose {
        val thrust = BOLT_THRUST + V(look.x, look.y, 0.0)
        val cast = glide(u, from.cast, 0.24 to thrust, 0.48 to BOLT_ACROSS, 0.82 to rc, 1.0 to rc)
        val body = glide(u, 0.0 to (from.body ?: 0.0), 0.24 to -0.1, 0.48 to -BOLT_TWIST - 0.05, 0.82 to 0.0, 1.0 to 0.0)
        return Pose(cast, from.other.lerp(ro, sm(u / 0.4)), body)
    }

    private fun holding(shape: Shape, rc: V, ro: V, look: V, t: Double, held: Double): Pose {
        val s = held / 20
        val k = sm(s / 0.3)
        return when (shape) {
            // The shoulders turn to the look, so the bracing hand stays under the wrist.
            Shape.BEAM -> Pose(rc.lerp(V(-PI / 2 + look.x, 0.05, 0.0), k) + tremor(t, 0.012 * k), ro.lerp(BEAM_BRACE + V(look.x, 0.0, 0.0), k), look.y * k)
            Shape.SPRAY -> {
                val w = s * 1.6
                Pose(rc.lerp(V(-1.45 + 0.14 * sin(2 * w) + look.x, -0.32 * sin(w) + look.y, 0.08), k), ro, 0.12 * sin(w) * k)
            }
            else -> {
                // A strike every pulse (half a second), from the first tick held.
                val j = bump((s % 0.5) - 0.05, 0.03) * k
                Pose(rc.lerp(V(-2.15, 0.1, 0.35), k) + V(0.18 * j, 0.0, 0.08 * j), ro, 0.0)
            }
        }
    }

    /** For an imbue, the cast arm is the weapon arm. */
    private fun using(use: SpellHand.Use, rc: V, ro: V, u: Double): Pose = when (use) {
        SpellHand.Use.RUNE -> {
            val x = curve(u, 0.0 to -PI / 10, 0.34 to 0.25, 0.62 to -2.35, 1.0 to -PI / 10)
            val y = curve(u, 0.0 to 0.0, 0.34 to 0.0, 0.62 to -0.2, 1.0 to 0.0)
            Pose(rc.lerp(V(x, y, 0.05), sm(u / 0.12) * (1 - sm((u - 0.88) / 0.12))), ro, 0.1 * trap(u, 0.2, 0.4, 0.6, 0.9))
        }
        SpellHand.Use.IMBUE -> {
            val k = trap(u, 0.05, 0.25, 0.65, 0.9); val j = bump(u - 0.33, 0.04)
            val lift = bump(u - 0.18, 0.08); val meet = trap(u, 0.2, 0.32, 0.6, 0.85)
            val raised = IMBUE_MEET + V(-0.45, 0.1, 0.05)
            val cast = rc.lerp(raised, sm(lift * 1.6) * (1 - meet)).lerp(IMBUE_MEET, meet) + V(0.015 * j, 0.0, 0.0)
            Pose(cast, ro.lerp(IMBUE_SCROLL, k) + V(-0.02 * j, 0.0, 0.0), -0.05 * k)
        }
        SpellHand.Use.AREA -> {
            val x = curve(u, 0.0 to -PI / 10, 0.08 to -1.9, 0.15 to 0.15, 0.5 to 0.05, 0.9 to -PI / 10, 1.0 to -PI / 10)
            val k = trap(u, 0.05, 0.15, 0.5, 0.9)
            Pose(rc.lerp(V(x, -0.1 * k, 0.05), sm(u / 0.04) * (1 - sm((u - 0.9) / 0.1))), ro, 0.08 * trap(u, 0.08, 0.15, 0.5, 0.9))
        }
    }

    private class Using(val use: SpellHand.Use, val at: Double, val arm: HumanoidArm)
    private class Release(val at: Double, val thrown: Boolean, val arm: HumanoidArm, val from: Pose)

    private class State {
        var using: Using? = null
        var casting: Pair<InteractionHand, Shape>? = null
        var lastCast: Pose? = null
        var release: Release? = null
        var released = Double.NEGATIVE_INFINITY
    }

    // Render thread only.
    private val states = WeakHashMap<Player, State>()

    private fun armOf(player: Player, hand: InteractionHand) = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite

    @JvmStatic
    fun started(player: Player, use: SpellHand.Use, hand: InteractionHand) {
        val st = states.getOrPut(player) { State() }
        val now = player.tickCount.toDouble()
        // Creative repeat clicks must not restart a use that is in progress.
        st.using?.let { if (it.use == use && now - it.at < use.ticks) return }
        st.using = Using(use, now, armOf(player, hand))
    }

    @JvmStatic
    fun released(player: Player) {
        states.getOrPut(player) { State() }.released = player.tickCount.toDouble()
    }

    fun momentDelay(entity: Entity, use: SpellHand.Use): Float {
        val u = (entity as? Player)?.let { states[it] }?.using?.takeIf { it.use == use } ?: return 0f
        val since = entity.tickCount - u.at
        return (use.ticks * use.at - since).coerceIn(0.0, use.ticks.toDouble()).toFloat()
    }

    /** Call after vanilla `setupAnim`. */
    @JvmStatic
    fun pose(model: HumanoidModel<*>, player: Player, age: Float) {
        val st = states.getOrPut(player) { State() }
        val time = age.toDouble()
        val casting = player.isUsingItem && player.useItem.item is MysticScrollItem
        endStoppedCast(st, player, time, casting)
        val (arm, poseFor) = pick(st, player, time, casting) ?: return
        apply(model, arm, poseFor)
    }

    /** A stopped cast was let go (a Bolt is thrown) or switched away from (nothing was cast). */
    private fun endStoppedCast(st: State, player: Player, time: Double, casting: Boolean) {
        st.casting?.let { (hand, shape) ->
            if (casting) return@let
            st.casting = null
            val from = st.lastCast ?: return@let
            st.lastCast = null
            val letGo = time - st.released <= RELEASE_SIGNAL_TICKS
            st.release = Release(time, letGo && shape == Shape.BOLT, armOf(player, hand), from)
        }
    }

    private fun pick(st: State, player: Player, time: Double, casting: Boolean): Pair<HumanoidArm, (V, V, V) -> Pose>? {
        val partial = time - player.tickCount
        val castHand = player.usedItemHand
        st.using?.let { u ->
            val since = time - u.at
            if (since in 0.0..u.use.ticks.toDouble()) {
                // The weapon arm leads an imbue, and the scroll arm comes to meet it.
                val arm = if (u.use == SpellHand.Use.IMBUE) u.arm.opposite else u.arm
                return arm to { rc: V, ro: V, _: V -> using(u.use, rc, ro, since / u.use.ticks) }
            }
            if (since > u.use.ticks) st.using = null
        }
        if (casting) {
            val shape = MysticScrollItem.spellOf(player.useItem)?.castShape ?: return null
            if (st.casting == null) st.released = Double.NEGATIVE_INFINITY
            st.casting = castHand to shape
            st.release = null
            val held = player.ticksUsingItem + partial
            return armOf(player, castHand) to { rc: V, ro: V, look: V ->
                (if (shape == Shape.BOLT) charging(rc, ro, time / 20, held) else holding(shape, rc, ro, look, time / 20, held)).also { st.lastCast = it }
            }
        }
        st.release?.let { r ->
            val since = time - r.at
            if (r.thrown && since < RELEASE_TICKS) return r.arm to { rc: V, ro: V, look: V -> throwing(rc, ro, look, r.from, since / RELEASE_TICKS) }
            if (!r.thrown && since < UNCHANNEL_TICKS) return r.arm to { rc: V, ro: V, _: V ->
                val x = ease(since / UNCHANNEL_TICKS)
                Pose(r.from.cast.lerp(rc, x), r.from.other.lerp(ro, x), (r.from.body ?: 0.0) * (1 - x))
            }
            st.release = null
        }
        val hand = when {
            player.mainHandItem.item is MysticScrollItem -> InteractionHand.MAIN_HAND
            player.offhandItem.item is MysticScrollItem -> InteractionHand.OFF_HAND
            else -> return null
        }
        return armOf(player, hand) to { rc: V, ro: V, _: V -> carried(rc, ro, time / 20) }
    }

    private fun apply(model: HumanoidModel<*>, arm: HumanoidArm, poseFor: (V, V, V) -> Pose) {
        // Poses are for a standing body, so remove the vanilla crouch lean first and add it back after.
        val lean = if (model.crouching) CROUCH_LEAN else 0.0
        val right = V.of(model.rightArm) + V(-lean, 0.0, 0.0); val left = V.of(model.leftArm) + V(-lean, 0.0, 0.0)
        val look = V(model.head.xRot - lean, model.head.yRot.toDouble(), 0.0)
        val pose: Pose
        val r: V; val l: V
        if (arm == HumanoidArm.RIGHT) {
            pose = poseFor(right, left, look)
            r = pose.cast; l = pose.other
        } else {
            val p = poseFor(left.mirror(), right.mirror(), look.mirror())
            pose = Pose(p.cast, p.other, p.body?.let { -it })
            r = p.other.mirror(); l = p.cast.mirror()
        }
        val body = pose.body
        if (body != null) {
            // Move the shoulders with the body turn, as vanilla does in a swing.
            model.body.yRot = body.toFloat()
            model.rightArm.z = (sin(body) * 5).toFloat(); model.rightArm.x = (-cos(body) * 5).toFloat()
            model.leftArm.z = (-sin(body) * 5).toFloat(); model.leftArm.x = (cos(body) * 5).toFloat()
        }
        set(model.rightArm, turned(body ?: 0.0, lean, r)); set(model.leftArm, turned(body ?: 0.0, lean, l))
    }

    private fun set(part: ModelPart, v: V) {
        part.xRot = v.x.toFloat(); part.yRot = v.y.toFloat(); part.zRot = v.z.toFloat()
    }

    private fun turned(body: Double, lean: Double, v: V): V {
        if (body == 0.0 && lean == 0.0) return v
        val m = Matrix4f().rotateY(body.toFloat()).rotateX(lean.toFloat()).rotateZYX(v.z.toFloat(), v.y.toFloat(), v.x.toFloat())
        val e = m.getEulerAnglesZYX(Vector3f())
        return V(e.x.toDouble(), e.y.toDouble(), e.z.toDouble())
    }

    fun reset() = states.clear()
}
