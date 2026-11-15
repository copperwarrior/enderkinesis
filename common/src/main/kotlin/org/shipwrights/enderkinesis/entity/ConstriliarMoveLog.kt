package org.shipwrights.enderkinesis.entity

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.Locale

/** Server-side movement log for the Constriliar walker. Every line starts with `[ConstriliarMove]`
 *  and the walker's short id. A line is written only when a walker's state changes. */
internal object ConstriliarMoveLog {

    const val ENABLED = true

    private const val STALL_SAMPLE_TICKS = 20
    private const val STALL_SAMPLES = 3
    private const val STALL_MOVE_SQ = 0.04

    private const val NO_KEY = Long.MIN_VALUE
    private const val ORDER_NONE = 0
    private const val ORDER_COMMAND = 1
    private const val ORDER_CLAWHOD = 2
    private const val ORDER_FOLLOW = 3

    private val LOG = LogUtils.getLogger()

    /** Per-walker change-detection state. Primitives and one reused vector, so a tick without a change allocates nothing. */
    class Note {
        internal var orderKind = ORDER_NONE
        internal var orderKey = NO_KEY
        internal var arrived = false
        internal var failed = false
        internal var searching = false
        internal var trapped = false
        internal var routeEnd = NO_KEY
        internal var jumpPhase = 0
        internal var flightTicks = 0
        internal var climbing = false
        internal var stalled = false
        internal var stallSamples = 0
        internal var sampleX = Double.NaN
        internal var sampleY = 0.0
        internal var sampleZ = 0.0
        internal var sampleShip = -1L
        internal val samplePos = Vector3d()
    }

    fun observe(walker: ConstriliarEntity, note: Note, level: ServerLevel) {
        val sitting = walker.isSitting()
        val kind = orderKind(walker)
        val command = walker.commandTarget()
        // A ship-held order's world point moves with the deck; its shipyard cell does not.
        val shipCell = walker.shipOrderCell()
        val key = when {
            kind == ORDER_NONE || sitting -> NO_KEY
            kind == ORDER_FOLLOW -> 0L
            command == null -> NO_KEY
            shipCell != NO_KEY -> shipCell
            else -> BlockPos.asLong(Mth.floor(command.x), Mth.floor(command.y), Mth.floor(command.z))
        }
        val effectiveKind = if (key == NO_KEY) ORDER_NONE else kind
        if (effectiveKind != note.orderKind || key != note.orderKey) {
            if (note.orderKind != ORDER_NONE) {
                emit(walker, "order end ${endName(note)}${if (note.arrived) " (had arrived)" else ""}")
            }
            note.orderKind = effectiveKind
            note.orderKey = key
            note.arrived = false
            note.failed = false
            note.stalled = false
            note.stallSamples = 0
            if (effectiveKind != ORDER_NONE) emit(walker, "order start ${kindName(walker, effectiveKind)}")
        }
        val active = note.orderKind != ORDER_NONE
        observeArrival(walker, note, command, active)
        observeSearch(walker, note, active)
        observeJump(walker, note)
        observeClimb(walker, note)
        if (active && walker.tickCount % STALL_SAMPLE_TICKS == 0) sampleStall(walker, note, level)
        if (!active) note.stallSamples = 0
    }

    private fun observeArrival(walker: ConstriliarEntity, note: Note, command: Vec3?, active: Boolean) {
        if (!active || command == null) {
            note.arrived = false
            note.failed = false
            return
        }
        val arrived = walker.orderSettledPeek()
        if (arrived != note.arrived) {
            note.arrived = arrived
            emit(walker, if (arrived) "arrived" else "displaced from the spot, going back")
        }
        val failed = !arrived && walker.orderFailed()
        if (failed != note.failed) {
            note.failed = failed
            emit(walker, if (failed) "order failed: ${failReason(walker)}" else "order recovered")
        }
    }

    private fun observeSearch(walker: ConstriliarEntity, note: Note, active: Boolean) {
        val nav = walker.surfaceNavigator()
        val searching = active && nav.isSearching()
        if (searching != note.searching) {
            note.searching = searching
            emit(
                walker,
                if (searching) "search start" else {
                    "search end routeLen=${nav.routeLength()} expansions=${nav.searchExpansions()}" +
                        if (nav.routeLength() == 0) " (no path)" else " (found)"
                },
            )
        }
        val trapped = active && nav.isTrapped()
        if (trapped != note.trapped) {
            note.trapped = trapped
            emit(walker, if (trapped) "trapped: search exhausted every reachable node" else "no longer trapped")
        }
        val end = if (active) nav.routeEndKey() else NO_KEY
        if (end != note.routeEnd) {
            val replan = note.routeEnd != NO_KEY && end != NO_KEY
            note.routeEnd = end
            if (end != NO_KEY) {
                emit(walker, "${if (replan) "replan" else "route"} len=${nav.routeLength()} end=${BlockPos.of(end).toShortString()}")
            }
        }
    }

    private fun observeJump(walker: ConstriliarEntity, note: Note) {
        val phase = walker.jumpPhase()
        if (phase == 2) note.flightTicks++
        if (phase == note.jumpPhase) return
        val before = note.jumpPhase
        note.jumpPhase = phase
        when {
            phase == 1 -> emit(walker, "jump crouch")
            phase == 2 -> {
                note.flightTicks = 0
                emit(walker, if (before == 1) "jump launch" else "hop launch")
            }
            before == 2 -> emit(walker, "jump land after ${note.flightTicks} ticks")
            else -> emit(walker, "jump cancelled")
        }
    }

    private fun observeClimb(walker: ConstriliarEntity, note: Note) {
        val climbing = walker.onClimbable()
        if (climbing == note.climbing) return
        note.climbing = climbing
        emit(walker, if (climbing) "climb start" else "climb end")
    }

    private fun sampleStall(walker: ConstriliarEntity, note: Note, level: ServerLevel) {
        // Sample in the deck's shipyard so a walker stuck on a moving deck reads as stalled.
        val deckId = walker.deckShipId()
        val deck = if (deckId == -1L) null else level.shipObjectWorld.loadedShips.getById(deckId)
        val pos = note.samplePos.set(walker.x, walker.y, walker.z)
        deck?.transform?.worldToShip?.transformPosition(pos)
        val ship = deck?.id ?: -1L
        val moved = note.sampleX.isNaN() || ship != note.sampleShip ||
            pos.distanceSquared(note.sampleX, note.sampleY, note.sampleZ) > STALL_MOVE_SQ
        note.sampleShip = ship
        note.sampleX = pos.x
        note.sampleY = pos.y
        note.sampleZ = pos.z
        if (moved || note.arrived) {
            note.stallSamples = 0
            if (note.stalled && moved) {
                note.stalled = false
                emit(walker, "moving again")
            }
            return
        }
        if (++note.stallSamples >= STALL_SAMPLES && !note.stalled) {
            note.stalled = true
            val nav = walker.surfaceNavigator()
            emit(
                walker,
                "stall: no movement for ${note.stallSamples} s, routeLen=${nav.routeLength()} searching=${nav.isSearching()} " +
                    "stallTicks=${nav.stallTicks()}",
            )
        }
    }

    /** Event hook for the fall tracker, which already knows its own start and landing. */
    fun fall(walker: ConstriliarEntity, text: String) {
        emit(walker, text)
    }

    fun bump(walker: ConstriliarEntity, speed: Double, outcome: String) {
        emit(walker, "bump ${String.format(Locale.ROOT, "%.2f", speed)} m/s $outcome")
    }

    private fun orderKind(walker: ConstriliarEntity): Int = when {
        walker.commandTarget() != null -> if (walker.moveClaim != null) ORDER_CLAWHOD else ORDER_COMMAND
        walker.followingOwner -> ORDER_FOLLOW
        else -> ORDER_NONE
    }

    private fun kindName(walker: ConstriliarEntity, kind: Int): String = when (kind) {
        ORDER_CLAWHOD -> "clawhod claim=${walker.moveClaim?.toString()?.take(SHORT_ID)} target=${target(walker)}"
        ORDER_COMMAND -> "command(staff) target=${target(walker)}"
        ORDER_FOLLOW -> "follow-owner target=${target(walker)}"
        else -> "none"
    }

    // The ended order's own cell: the walker's live target already belongs to the next order.
    private fun endName(note: Note): String = when (note.orderKind) {
        ORDER_CLAWHOD -> "clawhod cell=${BlockPos.of(note.orderKey).toShortString()}"
        ORDER_COMMAND -> "command(staff) cell=${BlockPos.of(note.orderKey).toShortString()}"
        ORDER_FOLLOW -> "follow-owner"
        else -> "none"
    }

    private fun failReason(walker: ConstriliarEntity): String {
        val nav = walker.surfaceNavigator()
        return when {
            !walker.hasBackShip() -> "vanilla navigation is done and the spot is not reached"
            nav.isTrapped() -> "trapped"
            nav.stallsInPlace > 0 && nav.routeEnd() == null ->
                "the body stalled ${nav.stallsInPlace}x in one place on its route (route dropped, plan ${planName(nav.planResult)}); " +
                    "last stall: ${nav.lastStallSummary ?: "-"}"
            nav.routeEnd() == null -> "no route: ${planName(nav.planResult)} (h=${walker.navShape().height})"
            nav.routeWhole -> "the whole route ends at ${nav.routeEnd()?.pos?.toShortString()}, the nearest node the " +
                "whole body fits (h=${walker.navShape().height}); the spot is out of its reach (${spotReject(walker)})"
            else -> "no route and no search left"
        }
    }

    private fun planName(result: Int): String = when (result) {
        ConstriliarSurfaceNavigator.PLAN_DROPPED -> "the route was dropped (stall or fall), a replan follows"
        ConstriliarSurfaceNavigator.PLAN_NO_START -> "no node under the body"
        ConstriliarSurfaceNavigator.PLAN_NO_GOAL -> "no node near the spot fits the whole body"
        ConstriliarSurfaceNavigator.PLAN_NO_PATH -> "no path found yet"
        else -> "route"
    }

    /** Why the walker's planner refuses the floor under the order's spot. */
    private fun spotReject(walker: ConstriliarEntity): String {
        val t = walker.commandTarget() ?: return "no spot"
        val floor = BlockPos.containing(t.x, t.y - SPOT_FLOOR_PROBE, t.z)
        val code = walker.standReject(floor, walker.otherWalkersNear(t))
        return "the spot's floor ${floor.toShortString()}: ${ConstriliarSurfaceNavigator.STAND_NAMES[code]}"
    }

    private fun target(walker: ConstriliarEntity): String {
        val t = walker.commandTarget()
            ?: (walker.level() as? ServerLevel)?.let { walker.surfaceNavigator().currentDestWorld(it) }
        return t?.let { "(${round(it.x)}, ${round(it.y)}, ${round(it.z)})" } ?: "-"
    }

    private fun emit(walker: ConstriliarEntity, text: String) {
        LOG.info(
            "[ConstriliarMove] {} @({}, {}, {}) {} [target={}]",
            walker.uuid.toString().take(SHORT_ID), round(walker.x), round(walker.y), round(walker.z), text, target(walker),
        )
    }

    private fun round(v: Double): Long = Math.round(v)

    private const val SHORT_ID = 8

    // A worker spot lies on its floor's top face: half a block down is inside the floor block.
    private const val SPOT_FLOOR_PROBE = 0.5
}
