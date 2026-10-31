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
 * A player with a Mystic Scroll, seen in third person (yours in F5, or anyone else's): the arms
 * move as your first-person arm does ([SpellHand]), turned into vanilla `HumanoidModel` arm and
 * body rotations, nothing added to the model.
 *
 * - **Carried:** vanilla's item-holding arm; every six seconds the hand lifts slightly and the
 *   wrist turns in a touch.
 * - **Charging a Bolt:** the arm swings out and well back behind the shoulder, creeping farther
 *   back as the charge fills, the shoulders turned away and the off hand forward for balance;
 *   trembling at full charge. **Letting go:** one smooth whip forward to point where they look as
 *   the bolt leaves, on across the body, and back to rest. Switching away mid-charge casts
 *   nothing, so the arm just settles back.
 * - **Holding a Beam:** straight out where they look, the off hand up under it as a brace, the
 *   shoulders turned to face the look; **a Spray:** a slow figure eight across the cone; **a
 *   Chain:** raised up and out, jerking back with each strike.
 * - **Writing a rune:** a deep scoop, down and back then up past the shoulder.
 * - **Imbuing:** the scroll's hand comes in front of the chest; the weapon arm tips back a little,
 *   then brings the blade down onto it.
 * - **An Area cast:** a short lift, then a slam to point at the ground ahead.
 *
 * Each pose is worked out for a right-handed, standing cast, mirrored for the left and leaned
 * forward with the body when crouching. They blend in from and back out to whatever vanilla
 * posed (walking, the idle bob), so nothing snaps.
 *
 * Charging and holding come from the use vanilla already shares; runes, imbues and Area casts
 * aren't uses, so the server sends word of them ([started], from `SpellNetwork.USED`), and your
 * own client starts them from your click. A use ending doesn't say whether it was let go or
 * switched away from, so a real release is signalled too ([released], from your own release or
 * `SpellNetwork.RELEASED`). Everything is timed by the player's own tick count.
 */
object SpellArmPoses {
    private const val FULL_CHARGE_TICKS = 30.0
    private const val RELEASE_TICKS = 15.0
    private const val UNCHANNEL_TICKS = 10.0
    /** How long before a use ends its release signal may come (it comes first). */
    private const val RELEASE_SIGNAL_TICKS = 10.0
    /** Vanilla's lean of the arms when crouching, added to their `xRot`. */
    private const val CROUCH_LEAN = 0.4

    /** An arm's rotations, in `ModelPart` terms (radians). */
    private class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        fun lerp(o: V, t: Double) = V(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
        /** The same turn on the other side of the body. */
        fun mirror() = V(x, -y, -z)
        companion object {
            fun of(part: ModelPart) = V(part.xRot.toDouble(), part.yRot.toDouble(), part.zRot.toDouble())
        }
    }

    /** Both arms and the body's turn, for a cast from the right arm ([cast]); [body] null leaves
     *  vanilla's. */
    private class Pose(val cast: V, val other: V, val body: Double?)

    // ---- Curves (as on the pose bench) ------------------------------------------------------------

    private fun sm(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
    private fun trap(u: Double, a: Double, b: Double, c: Double, d: Double) = sm((u - a) / (b - a)) * (1 - sm((u - c) / (d - c)))
    private fun bump(x: Double, w: Double) = exp(-(x * x) / (2 * w * w))
    private fun ease(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return 1 - (1 - t) * (1 - t) * (1 - t) }
    private fun tremor(t: Double, a: Double) = V(a * sin(t * 61), a * sin(t * 47 + 1), a * sin(t * 53 + 2))

    /** [u] through [keys] ([t, value], t rising from 0 to 1) on a Catmull-Rom curve. */
    private fun curve(u: Double, vararg keys: Pair<Double, Double>): Double {
        val x0 = u.coerceIn(0.0, 1.0)
        var i = 0
        while (i < keys.size - 2 && x0 > keys[i + 1].first) i++
        fun v(j: Int) = keys[j.coerceIn(0, keys.size - 1)].second
        val x = (x0 - keys[i].first) / (keys[i + 1].first - keys[i].first)
        val p0 = v(i - 1); val p1 = v(i); val p2 = v(i + 1); val p3 = v(i + 2)
        return 0.5 * (2 * p1 + (p2 - p0) * x + (2 * p0 - 5 * p1 + 4 * p2 - p3) * x * x + (3 * p1 - p0 - 3 * p2 + p3) * x * x * x)
    }

    /** [u] through [keys] with speed carried smoothly across unevenly spaced keys, starting and
     *  ending at rest: one motion that never stops between them. */
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

    // ---- The poses (right-handed; [rc] and [ro] the cast and other arms at rest, [look] the
    //      head's turn, [t] in seconds) -----------------------------------------------------------

    private const val BOLT_TWIST = 0.35
    private val BOLT_DRAWN = V(0.55, 0.3, 1.2)
    private val BOLT_PULL = V(0.35, 0.15, -0.1)
    private val BOLT_THRUST = V(-1.65, -0.15, 0.1)
    private val BOLT_ACROSS = V(-1.05, -0.75, 0.0)
    private val BALANCE = V(-0.5, 0.35, -0.1)
    /** Imbuing: the scroll's arm in front of the chest, and the weapon arm where the middle of
     *  a held sword's blade rests on that fist (solved for vanilla's arms, its held-item placing
     *  and the `handheld` model's third-person transform). */
    private val IMBUE_SCROLL = V(-1.15, 0.3, 0.0)
    private val IMBUE_MEET = V(-0.748, -0.762, 0.0)
    /** A Beam's off hand, up under the casting arm's wrist. */
    private val BEAM_BRACE = V(-1.42, 0.62, 0.0)

    private fun carried(rc: V, ro: V, t: Double): Pose {
        val g = bump((t % 6) - 3, 0.5)
        return Pose(rc + V(-0.12 * g, -0.1 * g, -0.02 * g), ro, null)
    }

    /** Charging a Bolt, [held] ticks in. */
    private fun charging(rc: V, ro: V, t: Double, held: Double): Pose {
        val s = held / 20
        val k = sm(s / 0.35); val c = (held / FULL_CHARGE_TICKS).coerceIn(0.0, 1.0)
        val full = if (held >= FULL_CHARGE_TICKS) 1.0 else 0.0
        val p = sm(c)
        val drawn = BOLT_DRAWN + V(BOLT_PULL.x * p, BOLT_PULL.y * p, BOLT_PULL.z * p)
        return Pose(rc.lerp(drawn, k) + tremor(t, 0.025 * full + 0.006 * c), ro.lerp(BALANCE, k), BOLT_TWIST * k * (0.7 + 0.3 * p))
    }

    /** Letting a Bolt go from [from], [u] 0..1. */
    private fun throwing(rc: V, ro: V, look: V, from: Pose, u: Double): Pose {
        val thrust = BOLT_THRUST + V(look.x, look.y, 0.0)
        val cast = glide(u, from.cast, 0.24 to thrust, 0.48 to BOLT_ACROSS, 0.82 to rc, 1.0 to rc)
        val body = glide(u, 0.0 to (from.body ?: 0.0), 0.24 to -0.1, 0.48 to -BOLT_TWIST - 0.05, 0.82 to 0.0, 1.0 to 0.0)
        return Pose(cast, from.other.lerp(ro, sm(u / 0.4)), body)
    }

    /** Holding a Beam, Spray or Chain, [held] ticks in. */
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

    /** A rune, an imbue or an Area cast, [u] 0..1. For an imbue the cast arm is the weapon's. */
    private fun using(use: SpellHand.Use, rc: V, ro: V, u: Double): Pose = when (use) {
        SpellHand.Use.RUNE -> {
            val x = curve(u, 0.0 to -PI / 10, 0.34 to 0.25, 0.62 to -2.35, 1.0 to -PI / 10)
            val y = curve(u, 0.0 to 0.0, 0.34 to 0.0, 0.62 to -0.2, 1.0 to 0.0)
            Pose(rc.lerp(V(x, y, 0.05), sm(u / 0.12) * (1 - sm((u - 0.88) / 0.12))), ro, 0.1 * trap(u, 0.2, 0.4, 0.6, 0.9))
        }
        SpellHand.Use.IMBUE -> {
            val k = trap(u, 0.05, 0.25, 0.65, 0.9); val j = bump(u - 0.33, 0.04)
            // A small tip back, just above the meeting pose, then down onto the scroll's hand.
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

    // ---- What each player is doing ---------------------------------------------------------------

    private class Using(val use: SpellHand.Use, val at: Double, val arm: HumanoidArm)
    /** A cast ending from [from]: [thrown] when a Bolt was let go, otherwise the arm settles back. */
    private class Release(val at: Double, val thrown: Boolean, val arm: HumanoidArm, val from: Pose)

    private class State {
        var using: Using? = null
        /** The cast under way when last posed: its hand and shape, and the pose it was in. */
        var casting: Pair<InteractionHand, Shape>? = null
        var lastCast: Pose? = null
        var release: Release? = null
        /** When the cast under way was signalled let go. */
        var released = Double.NEGATIVE_INFINITY
    }

    // Render thread only.
    private val states = WeakHashMap<Player, State>()

    private fun armOf(player: Player, hand: InteractionHand) = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite

    /** [player] used the scroll in [hand] as [use]: from your own click, or word from the server. */
    @JvmStatic
    fun started(player: Player, use: SpellHand.Use, hand: InteractionHand) {
        val st = states.getOrPut(player) { State() }
        val now = player.tickCount.toDouble()
        // Creative's repeated clicks don't restart one under way.
        st.using?.let { if (it.use == use && now - it.at < use.ticks) return }
        st.using = Using(use, now, armOf(player, hand))
    }

    /** [player] let go of a held cast (rather than switching away from it), just before the use ends. */
    @JvmStatic
    fun released(player: Player) {
        states.getOrPut(player) { State() }.released = player.tickCount.toDouble()
    }

    /** Ticks from now until the spell leaves [entity]'s hand in [use]'s motion, if it's under way;
     *  otherwise 0. */
    fun momentDelay(entity: Entity, use: SpellHand.Use): Float {
        val u = (entity as? Player)?.let { states[it] }?.using?.takeIf { it.use == use } ?: return 0f
        val since = entity.tickCount - u.at
        return (use.ticks * use.at - since).coerceIn(0.0, use.ticks.toDouble()).toFloat()
    }

    /** After vanilla's `setupAnim` for [player] ([age] its tick count with the partial tick). */
    @JvmStatic
    fun pose(model: HumanoidModel<*>, player: Player, age: Float) {
        val st = states.getOrPut(player) { State() }
        val time = age.toDouble()
        val partial = time - player.tickCount
        val casting = player.isUsingItem && player.useItem.item is MysticScrollItem
        val castHand = player.usedItemHand

        // A cast that stopped: let go (a Bolt is thrown) or switched away from (nothing was cast).
        st.casting?.let { (hand, shape) ->
            if (casting) return@let
            st.casting = null
            val from = st.lastCast ?: return@let
            st.lastCast = null
            val letGo = time - st.released <= RELEASE_SIGNAL_TICKS
            st.release = Release(time, letGo && shape == Shape.BOLT, armOf(player, hand), from)
        }

        val picked: Pair<HumanoidArm, (V, V, V) -> Pose> = run<Pair<HumanoidArm, (V, V, V) -> Pose>?> {
            st.using?.let { u ->
                val since = time - u.at
                if (since in 0.0..u.use.ticks.toDouble()) {
                    // An imbue is led by the weapon's arm, the scroll's coming to meet it.
                    val arm = if (u.use == SpellHand.Use.IMBUE) u.arm.opposite else u.arm
                    return@run arm to { rc: V, ro: V, _: V -> using(u.use, rc, ro, since / u.use.ticks) }
                }
                if (since > u.use.ticks) st.using = null
            }
            if (casting) {
                val shape = MysticScrollItem.spellOf(player.useItem)?.castShape ?: return@run null
                if (st.casting == null) st.released = Double.NEGATIVE_INFINITY
                st.casting = castHand to shape
                st.release = null
                val held = player.ticksUsingItem + partial
                return@run armOf(player, castHand) to { rc: V, ro: V, look: V ->
                    (if (shape == Shape.BOLT) charging(rc, ro, time / 20, held) else holding(shape, rc, ro, look, time / 20, held)).also { st.lastCast = it }
                }
            }
            st.release?.let { r ->
                val since = time - r.at
                if (r.thrown && since < RELEASE_TICKS) return@run r.arm to { rc: V, ro: V, look: V -> throwing(rc, ro, look, r.from, since / RELEASE_TICKS) }
                if (!r.thrown && since < UNCHANNEL_TICKS) return@run r.arm to { rc: V, ro: V, _: V ->
                    val x = ease(since / UNCHANNEL_TICKS)
                    Pose(r.from.cast.lerp(rc, x), r.from.other.lerp(ro, x), (r.from.body ?: 0.0) * (1 - x))
                }
                st.release = null
            }
            val hand = when {
                player.mainHandItem.item is MysticScrollItem -> InteractionHand.MAIN_HAND
                player.offhandItem.item is MysticScrollItem -> InteractionHand.OFF_HAND
                else -> return@run null
            }
            armOf(player, hand) to { rc: V, ro: V, _: V -> carried(rc, ro, time / 20) }
        } ?: return

        val (arm, poseFor) = picked
        // Posed standing, then leaned with the body: vanilla's crouching lean comes off the rest first.
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
            // The body turns, and the arms with it from their shoulders, as vanilla turns them in a swing.
            model.body.yRot = body.toFloat()
            model.rightArm.z = (sin(body) * 5).toFloat(); model.rightArm.x = (-cos(body) * 5).toFloat()
            model.leftArm.z = (-sin(body) * 5).toFloat(); model.leftArm.x = (cos(body) * 5).toFloat()
        }
        set(model.rightArm, turned(body ?: 0.0, lean, r)); set(model.leftArm, turned(body ?: 0.0, lean, l))
    }

    private fun set(part: ModelPart, v: V) {
        part.xRot = v.x.toFloat(); part.yRot = v.y.toFloat(); part.zRot = v.z.toFloat()
    }

    /** [v] carried round by the body turning [body] and leaning [lean]: the arm's own turn after
     *  the body's. */
    private fun turned(body: Double, lean: Double, v: V): V {
        if (body == 0.0 && lean == 0.0) return v
        val m = Matrix4f().rotateY(body.toFloat()).rotateX(lean.toFloat()).rotateZYX(v.z.toFloat(), v.y.toFloat(), v.x.toFloat())
        val e = m.getEulerAnglesZYX(Vector3f())
        return V(e.x.toDouble(), e.y.toDouble(), e.z.toDouble())
    }

    fun reset() = states.clear()
}
