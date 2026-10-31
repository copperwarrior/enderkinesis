package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.mixin.GameRendererFovInvoker
import org.shipwrights.enderkinesis.spell.Shape
import org.shipwrights.enderkinesis.spell.Spell
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Your first-person arm with a Mystic Scroll: the scroll itself isn't drawn; the spell sits just
 * ahead of the fist ([SpellCore]) and the arm moves with what you're doing.
 *
 * - **Carried:** a subtle sway; now and then the hand lifts a little as the wrist turns, as if
 *   glancing at the spell.
 * - **Charging a Bolt:** drawn back and out to the side, trembling at full charge, clear of the
 *   crosshair.
 * - **Letting a Bolt go:** a swing across from the drawn-back pose, then back to carried.
 * - **Holding a Beam:** braced, held out to the side; **a Spray:** sweeping in a lazy figure
 *   eight; **a Chain:** held up and out, the hand twitching with each strike.
 * - **Writing a rune:** the arm drops to point down, then scoops up in one swing; the rune
 *   appears at the top of it ([RuneVfx]).
 * - **Imbuing:** the off hand comes in toward the middle; the item in the main hand tips back,
 *   then swings over and down until its blade touches the off hand ([moveItem]), and the spell
 *   goes in.
 * - **An Area cast:** a quick slam straight down.
 *
 * The arm is vanilla's: moved and turned whole before it's drawn, never bent, so the fist lands
 * where the pose puts it, the arm rising from its shoulder as vanilla's does, or reaching forward
 * to it while charging and holding. Each pose is the fist's offset from where vanilla rests it
 * (forward, right, up, in blocks, as it moves on screen: see [fistFor]) and a roll round the
 * arm; changes between them blend rather than snap. Letting go uses the scroll up, so the
 * release and recovery play from a record of the cast, whatever the hand holds by then.
 *
 * Everything is timed by the client's own clock ([SpellClock.hand]), which never goes back, and
 * forgotten whenever the local player is a new one (respawning, changing dimension). Vanilla's
 * rules for the hand hold: nothing is drawn while looking through a spyglass, and an invisible
 * player's arm isn't drawn, though the spell in it is, as a held item would be.
 */
object SpellHand {
    /** The idle bob's tilt of the arm part, outward (`AnimationUtils.bobArms` at age 0). */
    private const val ARM_TILT = 0.1
    /** The fist, and the arm's direction, in the space the arm is drawn from (before the player
     *  model's arm part): the part's shoulder pivot (-5, 2, 0) px, tilted [ARM_TILT], and the hand
     *  at (-1, 8, 0) px down it. The right arm's; the left's mirror them. */
    private val FIST = Vec3((-5 - cos(ARM_TILT) - 8 * sin(ARM_TILT)) / 16, (2 - sin(ARM_TILT) + 8 * cos(ARM_TILT)) / 16, 0.0)
    private val ARM_DIR = Vec3(-sin(ARM_TILT), cos(ARM_TILT), 0.0)
    /** The arm part's shoulder pivot, in the same space: what the arm turns about. */
    private val SHOULDER = Vec3(-5.0 / 16, 2.0 / 16, 0.0)
    // The poses were made on a fist resting 0.95 ahead of the eye, 0.26 right and 0.3 down; see
    // [fistFor].
    private const val OLD_D = 0.95
    private const val OLD_X = 0.26
    private const val OLD_Y = -0.3
    /** How much of a pose's forward or back the fist moves in depth, vanilla's fist resting
     *  further out already. */
    private const val DEPTH = 0.6
    /** Across the screen (as a tangent from the eye), where the fist starts easing off toward
     *  the edge, and the furthest it goes. */
    private const val EDGE_A = 0.64
    private const val EDGE_B = 0.72
    /** A held item's middle as vanilla draws it in the right hand at rest (applyItemArmTransform
     *  and the handheld model's first-person translation), and the middle of a sword's blade in
     *  its model (centred, in blocks). */
    private val ITEM_CENTRE = Vector3f(0.56f + 1.13f / 16, -0.52f + 3.2f / 16, -0.72f + 1.13f / 16)
    private val BLADE = Vector3f(0.135f, 0.135f, 0f)
    /** The shoulder the poses' arm reached forward from: right of, below and behind the eye. */
    private val OLD_SHOULDER = Vector3f(0.42f, -0.62f, 0.05f)
    /** How far ahead of the fist, along the arm, the spell sits. */
    private const val AHEAD = 0.35
    private const val FULL_CHARGE_TICKS = 30f
    private const val CAST_TICKS = 12f
    private const val RECOVER_TICKS = 14f
    private const val UNCHANNEL_TICKS = 10f
    /** How long an off hand a use has left empty takes to lower out of view. */
    private const val LOWER_TICKS = 6.0

    /** An arm pose: the fist moved [f] forward, [r] right, [u] up, and rolled [roll] round the
     *  arm; [fw] 0..1, how far the arm reaches forward to it rather than up from its shoulder. */
    class Pose(val f: Double, val r: Double, val u: Double, val roll: Double, val fw: Double = 0.0) {
        operator fun plus(o: Pose) = Pose(f + o.f, r + o.r, u + o.u, roll + o.roll, fw + o.fw)
        fun scale(k: Double) = Pose(f * k, r * k, u * k, roll * k, fw * k)
        fun lerp(o: Pose, t: Double) = Pose(f + (o.f - f) * t, r + (o.r - r) * t, u + (o.u - u) * t, roll + (o.roll - roll) * t, fw + (o.fw - fw) * t)
        /** This pose, with the arm reaching forward. */
        fun forward() = Pose(f, r, u, roll, 1.0)
    }

    // ---- The poses --------------------------------------------------------------------------------

    private fun ease(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return 1 - (1 - t) * (1 - t) * (1 - t) }
    private fun bump(x: Double, w: Double) = exp(-(x * x) / (w * w))
    private fun hash(n: Double): Double { val x = sin(n * 12.9898 + 4.1) * 43758.5453; return x - floor(x) }
    private fun tremor(t: Double, a: Double) = Pose(a * sin(t * 47), a * sin(t * 53), a * sin(t * 41), 0.0)

    /** Carried: a subtle sway and, every five seconds, a glance. [t] in seconds. */
    private fun carried(t: Double): Pose {
        val glance = bump((t % 5) - 2.5, 0.45)
        return Pose(0.0, 0.0075 * sin(t * 1.1), 0.01 * sin(t * 2.2) + 0.035 * glance, 0.025 * sin(t * 1.1) + 0.4 * glance)
    }

    /** Charging a Bolt, [k] 0..1: drawn back and out to the side. */
    private fun charging(t: Double, k: Double): Pose =
        (Pose(-0.2 * ease(k), 0.13 * ease(k), 0.0, 0.1 * k) + tremor(t, 0.006 * ((k - 0.8) / 0.2).coerceIn(0.0, 1.0))).forward()

    /** Letting go, [u] 0..1: a swing across from the drawn-back pose. */
    private fun casting(t: Double, u: Double): Pose {
        val from = Pose(-0.2, 0.13, 0.0, 0.1) + tremor(t, 0.006)
        val a = sin(sqrt(u) * Math.PI)
        val swing = Pose(0.2 * sin(u * Math.PI), -0.25 * 1.2 * a, 0.12 * sin(sqrt(u) * 2 * Math.PI), -0.6 * 1.2 * a)
        return from.scale(1 - ease(u / 0.45)) + swing
    }

    /** Holding a Beam, Spray or Chain. */
    private fun holding(shape: Shape, t: Double): Pose = holdingAt(shape, t).forward()

    private fun holdingAt(shape: Shape, t: Double): Pose = when (shape) {
        Shape.BEAM -> Pose(0.03, 0.08, 0.01, 0.0) + tremor(t, 0.004)
        Shape.SPRAY -> Pose(0.03, 0.05 * sin(t * 1.6), 0.03 * sin(t * 3.2), 0.1 * sin(t * 1.6))
        else -> {
            val n = floor(t / 0.4)
            val b = bump(((t % 0.4) / 0.4) - 0.05, 0.15)
            Pose(0.0, 0.06 + 0.03 * (hash(n) - 0.5) * b, 0.08 + 0.03 * (hash(n + 99) - 0.5) * b, 0.2 * (hash(n + 7) - 0.5) * b) + tremor(t, 0.004)
        }
    }

    private fun sm(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
    /** A rise over [a]..[b], a hold, and a fall over [c]..[d]. */
    private fun trap(u: Double, a: Double, b: Double, c: Double, d: Double) = sm((u - a) / (b - a)) * (1 - sm((u - c) / (d - c)))

    /** [u] through [keys] ([t, value], t rising from 0 to 1) on a Catmull-Rom curve: one smooth
     *  motion through every key. */
    private fun curve(u: Double, keys: List<Pair<Double, Double>>): Double {
        val x0 = u.coerceIn(0.0, 1.0)
        var i = 0
        while (i < keys.size - 2 && x0 > keys[i + 1].first) i++
        fun v(j: Int) = keys[j.coerceIn(0, keys.size - 1)].second
        val x = (x0 - keys[i].first) / (keys[i + 1].first - keys[i].first)
        val p0 = v(i - 1); val p1 = v(i); val p2 = v(i + 1); val p3 = v(i + 2)
        return 0.5 * (2 * p1 + (p2 - p0) * x + (2 * p0 - 5 * p1 + 4 * p2 - p3) * x * x + (3 * p1 - p0 - 3 * p2 + p3) * x * x * x)
    }

    // Writing a rune: key poses (t, forward, right, up, roll) the scoop passes through.
    private val SCOOP = listOf(
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.34, 0.06, 0.0, -0.36, 0.4),
        doubleArrayOf(0.62, 0.1, -0.06, 0.38, -0.35),
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0),
    )

    /** The scroll's other uses: how long each one's motion takes, and when in it (0..1) the
     *  spell leaves the hand. */
    enum class Use(val ticks: Float, val at: Double) { RUNE(16f, 0.62), IMBUE(36f, 0.32), AREA(20f, 0.15) }

    /** Each use's arm motion, [u] 0..1: at rest at both ends. */
    private fun usePose(use: Use, u: Double): Pose = when (use) {
        Use.RUNE -> {
            val c = { k: Int -> curve(u, SCOOP.map { it[0] to it[k] }) }
            Pose(c(1), c(2), c(3), c(4), trap(u, 0.0, 0.25, 0.8, 1.0))
        }
        Use.IMBUE -> {
            // The off hand: in toward the middle, nudged up and over as the blade lands on it.
            val k = trap(u, 0.05, 0.25, 0.65, 0.9); val j = bump(u - 0.33, 0.04)
            Pose(0.05 * k, -0.45 * k, 0.06 * k + 0.03 * j, -0.4 * k + 0.2 * j, k)
        }
        Use.AREA -> {
            val k = trap(u, 0.05, 0.15, 0.5, 0.9)
            Pose(0.06 * k, 0.0, -0.22 * k, 0.0, k)
        }
    }

    /** Imbuing: the item tipping back and over, and how far its blade is brought to the off hand. */
    private fun imbueRoll(u: Double) = -0.6 * bump(u - 0.18, 0.08) + 0.4 * trap(u, 0.22, 0.32, 0.6, 0.85)
    private fun imbueMeet(u: Double) = 0.75 * trap(u, 0.12, 0.32, 0.6, 0.85)

    // ---- What you're doing --------------------------------------------------------------------------

    /** A use under way: which, when it started (on [SpellClock.hand]), the arm doing it, and the
     *  spell it used. */
    private class Using(val use: Use, val at: Double, val arm: HumanoidArm, val spell: Spell?)

    // Render thread only (started from the client's own clicks).
    private var using: Using? = null
    /** The player all this is about: anything kept from another is forgotten. */
    private var owner: Player? = null

    /** Forgets everything if [player] isn't the one it was kept for: a respawned player, or one
     *  in a new dimension, is a new player. */
    private fun sync(player: Player) {
        if (owner === player) return
        owner = player
        using = null
        castingNow = null; castSlot = -1; lastCastPose = null; release = null
        armPose = null; thisFrame = null
        spellView.clear(); fistView.clear()
        lastFist.clear(); lastShoulder.clear(); lastAxis.clear(); lastMove.clear()
    }

    /** The local player just used the scroll in [hand] ([use]): plays its arm motion. */
    @JvmStatic
    fun startUse(player: Player, use: Use, hand: InteractionHand) {
        if (player !== Minecraft.getInstance().player) return
        sync(player)
        // Creative's repeated clicks don't restart one under way.
        val now = SpellClock.hand(0f)
        using?.let { if (it.use == use && now - it.at < use.ticks) return }
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        using = Using(use, now, arm, MysticScrollItem.spellOf(player.getItemInHand(hand)))
    }

    /**
     * How many ticks from now until the moment the spell leaves [entity]'s hand in [use]'s motion
     * (the rune appearing, the blade meeting the off hand), if that motion is under way; otherwise
     * 0. Your own, seen in first person, is this arm's; anyone in third person, yours included,
     * is [SpellArmPoses]'. Asked as word of the use arrives, at the start of a tick.
     */
    fun momentDelay(entity: Entity, use: Use): Float {
        val player = Minecraft.getInstance().player ?: return 0f
        if (entity !== player || owner !== player || !firstPerson(player)) return SpellArmPoses.momentDelay(entity, use)
        val u = using?.takeIf { it.use == use } ?: return 0f
        val since = SpellClock.hand(0f) - u.at
        return (use.ticks * use.at - since).coerceIn(0.0, use.ticks.toDouble()).toFloat()
    }

    /** The use under way at [time], if any: its motion, then an empty off hand lowering. */
    private fun usingAt(time: Double): Using? {
        val u = using ?: return null
        if (time - u.at >= u.use.ticks + LOWER_TICKS) { using = null; return null }
        return u
    }

    /** The cast just let go of: when, which hand, its shape, and the pose at that moment. */
    private class Release(val at: Double, val hand: InteractionHand, val shape: Shape, val pose: Pose)

    // Render thread only.
    private var castingNow: Pair<InteractionHand, Shape>? = null
    /** The hotbar slot a main-hand cast was last seen in. */
    private var castSlot = -1
    private var lastCastPose: Pose? = null
    private var release: Release? = null

    /** Notes the moment a cast is let go of, with the pose it was in. Whatever either hand holds
     *  now: the scroll is used up by letting go. A cast switched away from (another hotbar slot,
     *  or something else swapped into its hand) wasn't let go of: vanilla stops it, and nothing
     *  is cast, so nothing plays. */
    private fun track(player: Player, time: Double) {
        if (player.isUsingItem && player.useItem.item is MysticScrollItem) {
            val hand = player.usedItemHand
            castingNow = hand to (MysticScrollItem.spellOf(player.useItem)?.castShape ?: Shape.BOLT)
            castSlot = if (hand == InteractionHand.MAIN_HAND) player.inventory.selected else -1
            return
        }
        val (hand, shape) = castingNow ?: return
        castingNow = null
        val pose = lastCastPose ?: carried(time / 20.0)
        lastCastPose = null
        val held = player.getItemInHand(hand)
        val switched = (hand == InteractionHand.MAIN_HAND && player.inventory.selected != castSlot) ||
            (!held.isEmpty && held.item !is MysticScrollItem)
        release = if (switched) null else Release(time, hand, shape, pose)
    }

    /** What the hand is doing this frame, and the spell to show in it; [lower] 0..1 how far it
     *  has dropped out of view, as vanilla drops a hand putting an item away. */
    private class Now(val pose: Pose, val core: Core?, val spell: Spell?, val charge: Float, val releasing: Boolean = false, val lower: Float = 0f)
    private enum class Core { CARRIED, HELD }

    private fun now(player: Player, hand: InteractionHand, stack: ItemStack, time: Double, partialTick: Float): Now? {
        val t = time / 20.0
        val using = player.isUsingItem && player.useItem.item is MysticScrollItem
        val carriedSpell = if (stack.item is MysticScrollItem) MysticScrollItem.spellOf(stack) else null
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        usingAt(time)?.let { u ->
            if (u.arm != arm) return@let
            val since = (time - u.at).coerceAtLeast(0.0)
            if (since < u.use.ticks) {
                val x = since / u.use.ticks
                val pose = carried(t).lerp(usePose(u.use, x), ease(since / 20.0 / 0.15))
                return Now(pose, if (x < u.use.at && u.spell != null) Core.CARRIED else null, u.spell, 0f, true)
            }
            // The motion's over, at rest. An off hand it left empty lowers out of view rather
            // than vanishing at once (vanilla draws no empty off hand).
            if (hand == InteractionHand.OFF_HAND && stack.isEmpty) {
                return Now(Pose(0.0, 0.0, 0.0, 0.0), null, null, 0f, true, ease((since - u.use.ticks) / LOWER_TICKS).toFloat())
            }
        }
        if (using && player.usedItemHand == hand) {
            val spell = MysticScrollItem.spellOf(player.useItem) ?: return null
            val shape = spell.castShape ?: return null
            val held = (player.ticksUsingItem + partialTick).toDouble()
            val blendIn = ease(held / 20 / 0.25)
            val pose = if (shape == Shape.BOLT) {
                val k = (held / FULL_CHARGE_TICKS).coerceIn(0.0, 1.0)
                carried(t).lerp(charging(t, k), blendIn)
            } else carried(t).lerp(holding(shape, t), blendIn)
            lastCastPose = pose
            val charge = if (shape == Shape.BOLT) (held / FULL_CHARGE_TICKS).toFloat().coerceIn(0f, 1f) else 1f
            return Now(pose, Core.HELD, spell, charge)
        }
        release?.let { r ->
            if (r.hand != hand) return@let
            val since = (time - r.at).coerceAtLeast(0.0)
            if (r.shape == Shape.BOLT) {
                if (since < CAST_TICKS) {
                    val u = since / CAST_TICKS
                    return Now(r.pose.lerp(casting(t, u), ease(u / 0.3)), null, null, 0f, true)
                }
                if (since < CAST_TICKS + RECOVER_TICKS) {
                    val x = ease((since - CAST_TICKS) / RECOVER_TICKS)
                    return Now(casting(t, 1.0).lerp(carried(t), x), if (x > 0.6 && carriedSpell != null) Core.CARRIED else null, carriedSpell, 0f, true)
                }
            } else if (since < UNCHANNEL_TICKS) {
                val x = ease(since / UNCHANNEL_TICKS)
                return Now(r.pose.lerp(carried(t), x), if (x > 0.5 && carriedSpell != null) Core.CARRIED else null, carriedSpell, 0f, true)
            }
            release = null
        }
        if (carriedSpell == null) return null
        return Now(carried(t), Core.CARRIED, carriedSpell, 0f)
    }

    // ---- Drawing --------------------------------------------------------------------------------

    // Render thread only: the arm's pose as it was last drawn, and where the spell showed.
    private var armPose: PoseStack.Pose? = null
    private val spellView = HashMap<HumanoidArm, Vec3>()
    /** Each arm's fist, shoulder and direction as vanilla last drew them, in the space the arm is
     *  moved in, before its move; and the move with the pose it's made in (for undoing it). */
    private val lastFist = HashMap<HumanoidArm, Vector3f>()
    private val lastShoulder = HashMap<HumanoidArm, Vector3f>()
    private val lastAxis = HashMap<HumanoidArm, Vector3f>()
    private val lastMove = HashMap<HumanoidArm, Matrix4f>()
    /** Each arm's fist as last drawn, in the hand's space. */
    private val fistView = HashMap<HumanoidArm, Vector3f>()
    private var thisFrame: Now? = null

    /** Whether to draw [hand]'s arm here this frame (casting, letting go, or carrying a scroll). */
    @JvmStatic
    fun takesOver(player: Player, hand: InteractionHand, stack: ItemStack, partialTick: Float): Boolean {
        sync(player)
        val time = SpellClock.hand(partialTick)
        // Whatever this hand holds: a cast can be let go of, or switched away from, either way.
        track(player, time)
        thisFrame = null
        if (!stack.isEmpty && stack.item !is MysticScrollItem) return false
        thisFrame = now(player, hand, stack, time, partialTick)
        return thisFrame != null
    }

    /** Whether the arm itself is left out this frame (an invisible player's), the spell in it
     *  still drawn. */
    @JvmStatic
    fun hidesArm(player: Player): Boolean = player.isInvisible

    /** Vanilla's equip drop for the arm, except while a cast is charged, held or let go of: the
     *  click that starts one, the server marking the scroll as cast and using it up all count as
     *  a new item in the hand, and would pull the arm down in the middle of its motion. An empty
     *  off hand a use has finished with lowers as vanilla's would. */
    @JvmStatic
    fun equipProgress(progress: Float): Float {
        val now = thisFrame ?: return progress
        return when {
            now.lower > 0f -> now.lower
            now.releasing || now.core == Core.HELD -> 0f
            else -> progress
        }
    }

    /** Whether a click with the scroll in [hand] should leave the hand's height alone (it does,
     *  for anything the scroll does: see [equipProgress]). */
    @JvmStatic
    fun keepsHeight(player: Player, hand: InteractionHand): Boolean = player.getItemInHand(hand).item is MysticScrollItem

    /** Vanilla's swing for the arm, except while a cast or a use is let go of: the click that
     *  starts one swings the hand, over the arm's own motion. */
    @JvmStatic
    fun swingProgress(progress: Float): Float = if (thisFrame?.releasing == true) 0f else progress

    /** Whether the item [hand] holds is being moved for a use under way (imbuing it), so it
     *  neither drops to re-equip as the imbue changes it nor swings with the click. */
    @JvmStatic
    fun holdsItemStill(player: Player, hand: InteractionHand, partialTick: Float): Boolean {
        if (player !== owner) return false
        val time = SpellClock.hand(partialTick)
        val u = usingAt(time) ?: return false
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        return u.use == Use.IMBUE && u.arm != arm && time - u.at < u.use.ticks
    }

    /**
     * Imbuing: moves the item in [hand] (the main hand) before vanilla draws it, tipping it back,
     * then over and down until the middle of its blade touches the off hand's fist as the off hand
     * last drew. Vanilla places a held item by `applyItemArmTransform` and its model's first-person
     * display: its middle at [ITEM_CENTRE], turned (0, -90, 25) degrees and scaled 0.68 for a
     * handheld item; the left hand's mirrors it.
     */
    @JvmStatic
    fun moveItem(pose: PoseStack, player: Player, hand: InteractionHand, partialTick: Float) {
        if (!holdsItemStill(player, hand, partialTick)) return
        val u = using ?: return
        val x = ((SpellClock.hand(partialTick) - u.at) / u.use.ticks).coerceAtLeast(0.0)
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val side = if (arm == HumanoidArm.RIGHT) 1f else -1f
        val roll = (imbueRoll(x) * side).toFloat()
        val centre = Vector3f(ITEM_CENTRE.x * side, ITEM_CENTRE.y, ITEM_CENTRE.z)
        var shift = Vector3f()
        val fist = fistView[u.arm]
        if (fist != null) {
            val model = Matrix4f().translate(centre).rotateZ(roll)
                .rotateXYZ(0f, Math.toRadians(-90.0 * side).toFloat(), Math.toRadians(25.0 * side).toFloat()).scale(0.68f)
            val blade = model.transformPosition(Vector3f(BLADE.x * side, BLADE.y, BLADE.z))
            // The off hand's fist, from the hand's space into the space the item is drawn from.
            val there = Matrix4f(pose.last().pose()).invert().transformPosition(Vector3f(fist))
            shift = there.sub(blade).mul(imbueMeet(x).toFloat())
        }
        pose.translate(shift.x, shift.y, shift.z)
        pose.rotateAround(Quaternionf().rotateZ(roll), centre.x, centre.y, centre.z)
    }

    /**
     * Where [p] puts the fist, from [rest] (where vanilla's rests; the right arm's, or the left's
     * mirrored): it moves on screen as it moved the fist the poses were made on, the same turn
     * from the eye and nearer or further by [DEPTH] of the same share. Toward the screen's edge
     * it eases off rather than leaving it.
     */
    private fun fistFor(p: Pose, rest: Vector3f): Vector3f {
        val d0 = -rest.z.toDouble(); val od = OLD_D + p.f
        var tx = rest.x / d0 + (OLD_X + p.r) / od - OLD_X / OLD_D
        val ty = rest.y / d0 + (OLD_Y + p.u) / od - OLD_Y / OLD_D
        if (tx > EDGE_A) tx = EDGE_A + (EDGE_B - EDGE_A) * tanh((tx - EDGE_A) / (EDGE_B - EDGE_A))
        val d = d0 * (OLD_D + DEPTH * p.f) / OLD_D
        return Vector3f((tx * d).toFloat(), (ty * d).toFloat(), (-d).toFloat())
    }

    /** Moves the arm, before it's drawn, to this frame's pose: the fist to its place, the arm
     *  pointing there from its own shoulder or, as far as the pose's [Pose.fw], reaching forward
     *  along the line the poses' arm reached along; then rolled round itself. */
    @JvmStatic
    fun moveArm(pose: PoseStack, arm: HumanoidArm) {
        val move = Matrix4f()
        val p = thisFrame?.pose
        val fist = lastFist[arm]; val shoulder = lastShoulder[arm]; val axis = lastAxis[arm]
        var turn = Quaternionf()
        if (p != null && fist != null && shoulder != null && axis != null) {
            val side = if (arm == HumanoidArm.RIGHT) 1f else -1f
            val target = fistFor(p, Vector3f(fist.x * side, fist.y, fist.z)).mul(side, 1f, 1f)
            val roll = Quaternionf().rotateAxis((p.roll * side).toFloat(), axis)
            turn = Quaternionf().rotationTo(Vector3f(fist).sub(shoulder).normalize(), Vector3f(target).sub(shoulder).normalize())
            if (p.fw > 0) {
                val old = Vector3f((OLD_X + p.r).toFloat(), (OLD_Y + p.u).toFloat(), (-(OLD_D + p.f)).toFloat()).sub(OLD_SHOULDER).mul(side, 1f, 1f).normalize()
                turn.slerp(Quaternionf().rotationTo(axis, old), p.fw.toFloat().coerceIn(0f, 1f))
            }
            move.translate(target).rotate(turn).translate(-fist.x, -fist.y, -fist.z).rotateAround(roll, fist.x, fist.y, fist.z)
            turn.mul(roll)
        }
        lastMove[arm] = Matrix4f(pose.last().pose()).mul(move)
        pose.last().pose().mul(move)
        pose.last().normal().rotate(turn)
    }

    fun firstPerson(player: Player): Boolean {
        val mc = Minecraft.getInstance()
        return player === mc.cameraEntity && mc.options.cameraType.isFirstPerson
    }

    /** Called with the arm's pose just before the arm model is drawn. */
    @JvmStatic
    fun capture(pose: PoseStack) {
        val last = pose.last()
        val copy = PoseStack().apply { last().pose().set(Matrix4f(last.pose())); last().normal().set(Matrix3f(last.normal())) }
        armPose = copy.last()
    }

    private fun fist(arm: HumanoidArm) = if (arm == HumanoidArm.RIGHT) FIST else Vec3(-FIST.x, FIST.y, FIST.z)
    private fun armDir(arm: HumanoidArm) = if (arm == HumanoidArm.RIGHT) ARM_DIR else Vec3(-ARM_DIR.x, ARM_DIR.y, ARM_DIR.z)

    /**
     * How much wider the world's view is than the hand's, as a ratio of the tangents of their half
     * angles: the fields of view as they're drawn this frame, the world's moved by sprinting,
     * Speed or a drawn bow, and both narrowed under water.
     */
    private fun fovScale(): Double {
        val mc = Minecraft.getInstance()
        val renderer = mc.gameRenderer as GameRendererFovInvoker
        val camera = mc.gameRenderer.mainCamera
        val pt = mc.frameTime
        val world = renderer.`enderkinesis$getFov`(camera, pt, true)
        val hand = renderer.`enderkinesis$getFov`(camera, pt, false)
        return tan(Math.toRadians(world / 2)) / tan(Math.toRadians(hand / 2))
    }

    /** [p], in the first-person hand's space, as the point in the world at its depth that lands
     *  on the screen where it does. */
    private fun handToWorld(p: Vector3f): Vec3 {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val k = fovScale()
        return camera.position
            .add(Vec3(camera.leftVector).scale(-p.x * k))
            .add(Vec3(camera.upVector).scale(p.y * k))
            .add(Vec3(camera.lookVector).scale(-p.z.toDouble()))
    }

    /**
     * Where the spell in the first-person hand on [arm]'s side shows in the world: the point at
     * its depth that lands on the screen where it does, though the hand is drawn with its own
     * field of view. A beam cast from here stays in the hand as the view turns.
     */
    fun palmInWorld(arm: HumanoidArm): Vec3 {
        val p = spellView[arm] ?: Vec3(0.45 * (if (arm == HumanoidArm.RIGHT) 1.0 else -1.0), -0.36, -0.78)
        return handToWorld(Vector3f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
    }

    /** Where [arm]'s first-person fist shows in the world, as [palmInWorld] finds the spell's. */
    fun fistInWorld(arm: HumanoidArm): Vec3? = fistView[arm]?.let(::handToWorld)

    /** After the arm is drawn: the spell ahead of the fist, in the arm's own space. */
    @JvmStatic
    fun render(player: Player, arm: HumanoidArm, buffers: MultiBufferSource, light: Int, partialTick: Float) {
        val armPose = armPose ?: return
        val now = thisFrame ?: return
        val m = armPose.pose()
        val fist = fist(arm); val dir = armDir(arm)
        val palm = fist.add(dir.scale(AHEAD))
        // Where the fist, the arm and the spell are in the hand's space, for next frame's roll and the beam.
        val shoulder = if (arm == HumanoidArm.RIGHT) SHOULDER else Vec3(-SHOULDER.x, SHOULDER.y, SHOULDER.z)
        // Back to before the move: where vanilla drew them, in the space they're moved in.
        val unmove = Matrix4f(lastMove[arm] ?: Matrix4f()).invert().mul(m)
        lastFist[arm] = unmove.transformPosition(Vector3f(fist.x.toFloat(), fist.y.toFloat(), fist.z.toFloat()))
        lastShoulder[arm] = unmove.transformPosition(Vector3f(shoulder.x.toFloat(), shoulder.y.toFloat(), shoulder.z.toFloat()))
        lastAxis[arm] = unmove.transformDirection(Vector3f(dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat())).normalize()
        fistView[arm] = m.transformPosition(Vector3f(fist.x.toFloat(), fist.y.toFloat(), fist.z.toFloat()))
        val handPalm = m.transformPosition(Vector3f(palm.x.toFloat(), palm.y.toFloat(), palm.z.toFloat()))
        spellView[arm] = Vec3(handPalm.x.toDouble(), handPalm.y.toDouble(), handPalm.z.toDouble())
        this.armPose = null
        val spell = now.spell ?: return
        val core = now.core ?: return
        // Drawn in the arm's space; the hand's space has the eye at its origin, looking down -z.
        val time = SpellClock.time(player.level(), partialTick)
        val draw = SpellCore.Draw(armPose, buffers, SpellCore.viewIn(armPose), light, time, inHand = true)
        when (core) {
            Core.CARRIED -> SpellCore.carried(draw, palm, spell, dir)
            Core.HELD -> SpellCore.held(draw, palm, SpellCore.heldSize(now.charge, time), spell, now.charge, dir)
        }
    }
}
