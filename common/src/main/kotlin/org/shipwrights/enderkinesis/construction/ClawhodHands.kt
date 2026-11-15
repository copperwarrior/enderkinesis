package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import org.joml.Matrix4dc
import org.joml.Vector3d
import kotlin.math.ceil

/** How far a hand goes from the gem (blocks, gem centre to block centre). Also the build area. */
const val CLAWHOD_REACH = 8.0

const val CLAWHOD_HANDS = 2
const val HAND_SLOTS = 2

/** Hand flight speed (blocks per tick). The client draws the hands at the same speed. */
const val HAND_SPEED = 0.5

/** The gem sits this far (blocks) from the block centre along the column: the crepusculite cube at (8, 9.5, 8) px. */
const val GEM_LIFT = 9.5 / 16.0 - 0.5

/** Sets this vector to the gem of the clawhod block at [pos] whose column points along [facing], in the block's frame. */
fun Vector3d.setClawhodGem(pos: BlockPos, facing: Direction): Vector3d =
    set(pos.x + 0.5 + facing.stepX * GEM_LIFT, pos.y + 0.5 + facing.stepY * GEM_LIFT, pos.z + 0.5 + facing.stepZ * GEM_LIFT)

/** Sets this vector to the unit world direction of [facing] on a block that [shipToWorld] carries; null when not on a ship. */
fun Vector3d.setClawhodFacingWorld(facing: Direction, shipToWorld: Matrix4dc?): Vector3d {
    set(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
    shipToWorld?.transformDirection(this)
    return normalize()
}

/** The way the hands point at rest: the heading for a vertical column, else the column itself (out of the wall). */
fun clawhodForward(facing: Direction, heading: Direction): Direction = if (facing.axis.isVertical) heading else facing

/** Which side of [clawhodForward] (+1 clockwise, -1 counter-clockwise) the left hand rests on, seen from the front or behind. */
fun clawhodLeftSign(facing: Direction): Int = if (facing.axis.isVertical) -1 else 1

/** A planned work position keeps its cells this far inside the reach sideways: the carrier stops up to 1.25 off its target. */
const val WORK_SPOT_MARGIN = 1.25

/** True when the centre of the block at ([bx], [by], [bz]) is within [reach] of the point ([x], [y], [z]). */
internal fun withinReach(x: Double, y: Double, z: Double, bx: Int, by: Int, bz: Int, reach: Double = CLAWHOD_REACH): Boolean {
    require(reach >= 0.0) { "Reach must not be negative: $reach" }
    val dx = bx + 0.5 - x
    val dy = by + 0.5 - y
    val dz = bz + 0.5 - z
    return dx * dx + dy * dy + dz * dz <= reach * reach
}

/** Ticks a hand needs to fly [distance] blocks: at least one. */
internal fun flightTicks(distance: Double, speed: Double = HAND_SPEED): Int {
    require(distance >= 0.0) { "Distance must not be negative: $distance" }
    require(speed > 0.0) { "Speed must be positive: $speed" }
    return maxOf(1, ceil(distance / speed).toInt())
}

/** The block a hand can place: that of the first slot that holds a placeable block, or null. */
internal fun handBlock(slots: List<ItemStack>): Block? {
    for (slot in slots) clawhodBlockOf(slot)?.let { return it }
    return null
}

/** How many of [block] the hand holds in all its slots. */
internal fun handCount(slots: List<ItemStack>, block: Block): Int {
    val item = block.asItem()
    var n = 0
    for (slot in slots) if (slot.`is`(item) && !slot.hasTag()) n += slot.count
    return n
}

/**
 * How many more of [block] the hand takes: 0 when it already holds another block type. A block with no item
 * (a cut gives only loot) takes a stack's worth while a slot is empty.
 */
internal fun handRoom(slots: List<ItemStack>, block: Block): Int {
    val held = handBlock(slots)
    if (held != null && held != block) return 0
    val item = block.asItem()
    if (item == Items.AIR) return if (slots.any { it.isEmpty }) DEFAULT_STACK else 0
    return pickupRoom(slots, ItemStack(item))
}

/** True when the hand holds anything other than a tool: the tool stays in the hand through unloads. */
internal fun handHasItems(slots: List<ItemStack>): Boolean = slots.any { !it.isEmpty && !isClawhodTool(it) }

/** Takes [count] of [block] out of the slots, from the last slot first. Returns how many it took. */
internal fun handTake(slots: MutableList<ItemStack>, block: Block, count: Int): Int {
    require(count >= 0) { "Count must not be negative: $count" }
    val item = block.asItem()
    var left = count
    for (i in slots.indices.reversed()) {
        if (left == 0) break
        val slot = slots[i]
        if (!slot.`is`(item) || slot.hasTag()) continue
        val n = minOf(left, slot.count)
        slot.shrink(n)
        if (slot.isEmpty) slots[i] = ItemStack.EMPTY
        left -= n
    }
    return count - left
}

private const val DEFAULT_STACK = 64

/** Rings of [WORK_SPOT_OFFSETS] at scale 1: a floor further out than the reach less the stop margin never reaches. */
const val WORK_SPOT_RINGS = 6

/** The most rings a scaled reach may use: it bounds the floor search. */
const val WORK_SPOT_MAX_RINGS = 12

/** Rings to search for a [reach] and stop [margin] in the walk frame's units: the floor of their difference, bounded. */
internal fun workSpotRings(reach: Double, margin: Double): Int = Math.floor(reach - margin).toInt().coerceIn(1, WORK_SPOT_MAX_RINGS)

/**
 * Floor offsets round a target column a carrier may stand on: rings 1 to [WORK_SPOT_MAX_RINGS], the four sides before
 * the corners, so the first `rings * 16` values are rings 1 to `rings`.
 */
internal val WORK_SPOT_OFFSETS: IntArray = buildList {
    for (d in 1..WORK_SPOT_MAX_RINGS) {
        for ((x, z) in listOf(0 to -d, d to 0, 0 to d, -d to 0, d to -d, d to d, -d to d, -d to -d)) {
            add(x)
            add(z)
        }
    }
}.toIntArray()

/** Work spot classes: the ground, a floor outside the plan, and the top of finished work. */
const val SPOT_GROUND: Byte = 0
const val SPOT_OUTSIDE: Byte = 1
const val SPOT_TOP: Byte = 2
internal val SPOT_CLASS_NAMES = arrayOf("ground", "outside", "top")

/** The ground and floors outside the plan rank equal; a finished-work top is used only when neither reaches. */
internal fun spotRank(spotClass: Byte): Int = if (spotClass == SPOT_TOP) 1 else 0

/** Pending cells of a layer that one spot choice counts at most. */
const val COVER_CAP = 256

/**
 * The best floor (packed pos, in the walk frame) to stand the carrier on, or [NO_POS]. A floor puts the gem at its
 * top centre plus [lift]; it must reach the first point of [targets] (x, y, z triples in the same frame). Then, in
 * order: the better [classes] rank ([spotRank]); the [supply] point (null for none) in reach; the most of the other
 * [targets] and of the [cover] points in reach; and last the floor whose gem is [furthest] from the first target,
 * or the closest after a failed move. Floors in [tried] are skipped. [faces] (null: all floors) holds each spot's
 * support face, for its gem point ([spotGemX]).
 */
@Suppress("LongParameterList") // Plain values keep it testable without a level.
internal fun chooseWorkSpot(
    floors: LongArray, classes: ByteArray, count: Int, lift: Double, targets: DoubleArray, targetCount: Int,
    supply: DoubleArray?, cover: DoubleArray, coverCount: Int, tried: LongArray, triedCount: Int, furthest: Boolean,
    reach: Double = CLAWHOD_REACH, margin: Double = WORK_SPOT_MARGIN, faces: ByteArray? = null,
): Long {
    require(count in 0..minOf(floors.size, classes.size)) { "Count $count is outside 0..${floors.size}" }
    require(targetCount in 1..targets.size / 3) { "Target count $targetCount is outside 1..${targets.size / 3}" }
    require(coverCount in 0..cover.size / 3) { "Cover count $coverCount is outside 0..${cover.size / 3}" }
    val wx = targets[0]
    val wy = targets[1]
    val wz = targets[2]
    var best = NO_POS
    var bestRank = 0
    var bestSupply = false
    var bestVotes = 0
    var bestDist = 0.0
    for (i in 0 until count) {
        val f = floors[i]
        if (contains(tried, triedCount, f)) continue
        val face = if (faces == null) FLOOR_FACE else faces[i].toInt()
        val gx = spotGemX(f, face, lift)
        val gy = spotGemY(f, face, lift)
        val gz = spotGemZ(f, face, lift)
        if (!plannedReachTo(gx, gy, gz, wx, wy, wz, reach, margin)) continue
        val rank = spotRank(classes[i])
        val hasSupply = supply != null && plannedReachTo(gx, gy, gz, supply[0], supply[1], supply[2], reach, margin)
        val votes = coverVotes(gx, gy, gz, targets, targetCount, cover, coverCount, reach, margin)
        val dist = (gx - wx) * (gx - wx) + (gy - wy) * (gy - wy) + (gz - wz) * (gz - wz)
        val better = when {
            best == NO_POS -> true
            rank != bestRank -> rank < bestRank
            hasSupply != bestSupply -> hasSupply
            votes != bestVotes -> votes > bestVotes
            else -> if (furthest) dist > bestDist else dist < bestDist
        }
        if (!better) continue
        best = f
        bestRank = rank
        bestSupply = hasSupply
        bestVotes = votes
        bestDist = dist
    }
    return best
}

/** How many of the other [targets] and of the [cover] points a gem planned at ([x], [y], [z]) reaches. */
@Suppress("LongParameterList") // Plain values keep it testable without a level.
internal fun coverVotes(
    x: Double, y: Double, z: Double, targets: DoubleArray, targetCount: Int, cover: DoubleArray, coverCount: Int,
    reach: Double = CLAWHOD_REACH, margin: Double = WORK_SPOT_MARGIN,
): Int {
    var votes = 0
    for (t in 1 until targetCount) {
        if (plannedReachTo(x, y, z, targets[t * 3], targets[t * 3 + 1], targets[t * 3 + 2], reach, margin)) votes++
    }
    for (c in 0 until coverCount) if (plannedReachTo(x, y, z, cover[c * 3], cover[c * 3 + 1], cover[c * 3 + 2], reach, margin)) votes++
    return votes
}

/**
 * The climb step toward an out-of-reach target point ([tx], [ty], [tz]): the floor whose gem comes nearest it, if
 * that is at least [CLIMB_MIN_GAIN] nearer than the gem at ([fromX], [fromY], [fromZ]) is now; else [NO_POS].
 * Floors in [tried] are skipped, so the carrier never goes back and forth.
 */
@Suppress("LongParameterList") // Plain values keep it testable without a level.
internal fun closerWorkSpot(
    floors: LongArray, count: Int, lift: Double, tx: Double, ty: Double, tz: Double,
    fromX: Double, fromY: Double, fromZ: Double, tried: LongArray, triedCount: Int, faces: ByteArray? = null,
): Long {
    require(count in 0..floors.size) { "Count $count is outside 0..${floors.size}" }
    val now = Math.sqrt((tx - fromX) * (tx - fromX) + (ty - fromY) * (ty - fromY) + (tz - fromZ) * (tz - fromZ))
    var best = NO_POS
    var bestDist = now - CLIMB_MIN_GAIN
    for (i in 0 until count) {
        val f = floors[i]
        if (contains(tried, triedCount, f)) continue
        val face = if (faces == null) FLOOR_FACE else faces[i].toInt()
        val dx = spotGemX(f, face, lift) - tx
        val dy = spotGemY(f, face, lift) - ty
        val dz = spotGemZ(f, face, lift) - tz
        val d = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (d <= bestDist) {
            best = f
            bestDist = d
        }
    }
    return best
}

/** The support face of a floor spot, as [Direction.get3DDataValue]. */
val FLOOR_FACE = Direction.DOWN.get3DDataValue()

/**
 * The gem point of a spot on support [face] ([Direction.get3DDataValue]): the body's base face centre plus the
 * outward normal times [lift]. A floor spot ([FLOOR_FACE]) is its support block; any other spot is the body cell.
 */
internal fun spotGemX(spot: Long, face: Int, lift: Double): Double =
    gemAxis(BlockPos.getX(spot), Direction.from3DDataValue(face).stepX, lift)

internal fun spotGemY(spot: Long, face: Int, lift: Double): Double =
    gemAxis(BlockPos.getY(spot) + (if (face == FLOOR_FACE) 1 else 0), Direction.from3DDataValue(face).stepY, lift)

internal fun spotGemZ(spot: Long, face: Int, lift: Double): Double =
    gemAxis(BlockPos.getZ(spot), Direction.from3DDataValue(face).stepZ, lift)

private fun gemAxis(cell: Int, step: Int, lift: Double): Double = cell + 0.5 + step * (0.5 - lift)

/** A climb step must bring the gem at least this much nearer the cell (blocks). */
const val CLIMB_MIN_GAIN = 1.0

/**
 * True when a gem planned at ([x], [y], [z]) reaches the block at [packed] even when the carrier stops
 * [WORK_SPOT_MARGIN] off its spot. The carrier stands on the floor, so the margin is horizontal only.
 */
internal fun plannedReach(x: Double, y: Double, z: Double, packed: Long): Boolean =
    plannedReachTo(x, y, z, BlockPos.getX(packed) + 0.5, BlockPos.getY(packed) + 0.5, BlockPos.getZ(packed) + 0.5)

/**
 * [plannedReach] to the exact point ([px], [py], [pz]), such as a ship cell's centre moved into the walk frame.
 * [reach] and [margin] are in that frame's units: the carrier-scaled reach through the frame's own scale.
 */
@Suppress("LongParameterList") // Plain values keep it testable without a level.
internal fun plannedReachTo(
    x: Double, y: Double, z: Double, px: Double, py: Double, pz: Double,
    reach: Double = CLAWHOD_REACH, margin: Double = WORK_SPOT_MARGIN,
): Boolean {
    val dx = px - x
    val dy = py - y
    val dz = pz - z
    val h = Math.sqrt(dx * dx + dz * dz) + margin
    return h * h + dy * dy <= reach * reach
}

private fun contains(values: LongArray, count: Int, value: Long): Boolean {
    for (i in 0 until count) if (values[i] == value) return true
    return false
}

/** Ticks a player with an empty hand takes to break a block of [hardness]: [breakTicks] at speed 1. */
internal fun bareHandBreakTicks(hardness: Float, needsTool: Boolean): Int = breakTicks(hardness, 1f, !needsTool)

/** The vanilla crack stage 0..9 after [ticks] of [total]. */
internal fun punchStage(ticks: Int, total: Int): Int {
    require(total > 0) { "Total must be positive: $total" }
    return (ticks.coerceIn(0, total) * CRACK_STAGES / total).coerceAtMost(CRACK_STAGES - 1)
}

private const val CRACK_STAGES = 10

/** Ticks of one punch: vanilla plays the hit sound every 4 ticks while a player breaks a block. */
const val PUNCH_TICKS = 4

/** The id a hand reserves cells by: one per hand, made from the clawhod's id so it lasts over a reload. */
internal fun handWorkerId(clawhod: java.util.UUID, hand: Int): java.util.UUID {
    require(hand in 0 until CLAWHOD_HANDS) { "Hand $hand is outside 0 until $CLAWHOD_HANDS" }
    return java.util.UUID(clawhod.mostSignificantBits, clawhod.leastSignificantBits xor (hand + 1L))
}

/** Client: one hand's drawn motion. Render thread only; made once per block entity. */
internal class HandMotion {
    /** Drawn position in world space; NaN until the first frame. */
    val pos = org.joml.Vector3d(Double.NaN, 0.0, 0.0)

    /** Where the leg started, in the Clawhod's ship frame (shipyard), or the world when not on a ship. */
    val from = org.joml.Vector3d()
    val prev = org.joml.Vector3d()
    var start = 0.0
    var duration = 0.0
    var target = Long.MAX_VALUE
    var punch = false
    var yaw = 0f
    var pitch = 0f
    var roll = 0f

    /** The clip drawn now and the one it blends from (indexes of the renderer's clip table; -1 for none yet). */
    var clip = -1
    var clipStart = 0.0
    var prevClip = -1
    var prevStart = 0.0
    var switchAt = 0.0

    /** The cell a place clip already played at, so it plays once for each arrival. */
    var placeTarget = Long.MAX_VALUE

    /** A cell this hand reached while it was open: it counts as open until the hand leaves, though the place fills it. */
    var openTarget = Long.MAX_VALUE

    /** 0 for the empty-hand rest position, 1 for the holding one; eased between them. */
    var homeHold = 0.0
}

/** Smooth start and stop, no overshoot: 0 at 0, 1 at 1. */
internal fun easeInOut(t: Double): Double {
    val c = t.coerceIn(0.0, 1.0)
    return if (c < HALF) 4 * c * c * c else 1 - Math.pow(-2 * c + 2, 3.0) / 2
}

/** Height a flying hand lifts at leg fraction [t]: a low arc, full height from [ARC_FULL_LENGTH] blocks up. */
internal fun legLift(t: Double, length: Double): Double =
    ARC_LIFT * minOf(1.0, length / ARC_FULL_LENGTH) * Math.sin(Math.PI * t.coerceIn(0.0, 1.0))

/**
 * How far a punching hand stands back from the cell centre at punch [phase] (0 to 1): it draws back a little
 * (wind-up), jabs in fast, then eases back (follow-through). Continuous, and [PUNCH_REST] at both ends.
 */
internal fun punchBack(phase: Double): Double {
    val p = phase.coerceIn(0.0, 1.0)
    return when {
        p < PUNCH_WIND_END -> PUNCH_REST + PUNCH_WIND * Math.sin(Math.PI * p / PUNCH_WIND_END)
        p < PUNCH_JAB_END -> PUNCH_REST - PUNCH_DEPTH * easeOut((p - PUNCH_WIND_END) / (PUNCH_JAB_END - PUNCH_WIND_END))
        else -> PUNCH_REST - PUNCH_DEPTH * (1 - easeInOut((p - PUNCH_JAB_END) / (1 - PUNCH_JAB_END)))
    }
}

/** The small press of a place, [k] = 0 to 1 after arrival: down and back up. */
internal fun placeDip(k: Double): Double = if (k <= 0.0 || k >= 1.0) 0.0 else PLACE_DIP * Math.sin(Math.PI * k)

private fun easeOut(t: Double): Double = 1 - Math.pow(1 - t.coerceIn(0.0, 1.0), 3.0)

private const val HALF = 0.5

// Hand motion values (blocks, ticks, radians), tuned to look calm.
const val ARC_LIFT = 0.5
const val ARC_FULL_LENGTH = 4.0
const val PUNCH_REST = 0.85
/** A placing hand's palm stops this far (blocks) from the cell centre: fingertips (0.41 ahead) and the clip's 0.28 reach stay outside the cell. */
const val PLACE_REST = 1.2
const val PUNCH_WIND = 0.08
const val PUNCH_DEPTH = 0.25
const val PUNCH_WIND_END = 0.45
const val PUNCH_JAB_END = 0.65
const val PLACE_DIP = 0.06
const val PLACE_DIP_TICKS = 5.0
const val IDLE_BOB = 0.03
const val IDLE_SWAY = 0.015
const val IDLE_PERIOD_TICKS = 80.0
const val TURN_TICKS = 4.0
const val MAX_PITCH = 0.5f
/** A leg for a punch start or stop at the same cell. */
const val SHIFT_TICKS = 3.0

/**
 * The "place" clip (1.375 s) plays at [PLACE_SPEED] times its speed and starts when the hand arrives at the cell.
 * Its palm reaches furthest in at 0.708 s, which is 7 ticks at that speed: the block appears then. The hand stays
 * until the clip ends (14 ticks) before it flies on.
 */
const val PLACE_SPEED = 2.0
const val PLACE_PRESS_TICKS = 7L
const val PLACE_CLIP_TICKS = 14L

/** Crossfade between two hand clips (ticks). */
const val HAND_BLEND_TICKS = 4.0

/** Ticks a hand that arrived [sinceArrival] ticks ago waits before the place press (0 when it is time). */
internal fun placeWait(sinceArrival: Long): Long = (PLACE_PRESS_TICKS - sinceArrival).coerceAtLeast(0L)

/** Ticks a hand that arrived [sinceArrival] ticks ago stays before it flies on (0 when the clip is over). */
internal fun placeHold(sinceArrival: Long): Long = (PLACE_CLIP_TICKS - sinceArrival).coerceAtLeast(0L)
