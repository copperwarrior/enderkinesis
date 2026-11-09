package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.ChunkPos
import org.joml.Vector3d
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** The jets of a server level. Owners call [hold] each tick or two; a jet that is not held ends, and its parcels fly out. */
object GasJets {
    // Server thread only.
    private val levels = WeakHashMap<ServerLevel, Long2ObjectOpenHashMap<GasJetEmitter>>()
    private const val MOVE_RESEND = 0.2
    private const val TURN_RESEND = 0.97

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register { level -> tick(level) }
    }

    /**
     * Keeps the jet [id] of [kind] going. [px], [py], [pz] and the unit direction are in the frame [frame] (0 is the world).
     * [mass] is the gas in cell units that it hands to the grid each tick; the vent passes what it drained.
     * [intake]: the jet may carry only the [mass] that its owner passed, summed over the holds (a vent drains it), and the
     * budget it did not send goes back to this cell. A free source such as the sack passes null.
     * [thrust] is the recoil in newtons whose impulse the parcels carry to what they hit; 0 is no push.
     */
    @Suppress("LongParameterList") // The whole state of an emitter.
    fun hold(
        level: ServerLevel, id: Long, kind: GasKind, frame: Long, px: Double, py: Double, pz: Double,
        dx: Double, dy: Double, dz: Double, strength: Float, mass: Float, thrust: Double = 0.0, intake: BlockPos? = null,
    ) {
        require(strength in 0f..1f) { "strength $strength" }
        val map = levels.getOrPut(level) { Long2ObjectOpenHashMap() }
        val key = id * KIND_SPREAD + kind.ordinal
        val time = level.gameTime
        var e = map.get(key)
        val fresh = e == null || !e.active
        if (e == null) {
            e = GasJetEmitter(key, kind, level.random.nextLong())
            map.put(key, e)
        }
        if (fresh) {
            e.active = true
            e.startTick = time
            e.carry = 0f
        }
        val changed = fresh || e.frame != frame || strength != e.strength ||
            moved(px - e.sentPx, py - e.sentPy, pz - e.sentPz) ||
            dx * e.sentDx + dy * e.sentDy + dz * e.sentDz < TURN_RESEND
        e.frame = frame; e.px = px; e.py = py; e.pz = pz
        e.dx = dx; e.dy = dy; e.dz = dz
        e.strength = strength
        e.thrust = thrust
        e.budgeted = intake != null
        if (intake != null) {
            e.budget += mass
            e.intake = intake.asLong()
        }
        // A vent jet at its parcel cap drains nothing and keeps the parcel size of its last drain.
        if (intake == null || mass > 0f) e.parcelMass = if (strength > 0f) mass / (GasJetTuning.PARCELS_PER_TICK * strength) else 0f
        e.lastHeld = time
        if (changed) send(level, e, false)
    }

    /** How much of [want] the vent may drain this tick for jet [id], so the jet never holds more than it can send. */
    fun intakeRoom(level: ServerLevel, id: Long, kind: GasKind, want: Float): Float {
        val e = levels.get(level)?.get(id * KIND_SPREAD + kind.ordinal) ?: return want
        if (e.parcels.n >= GasJetTuning.PARCEL_CAP) return 0f
        return minOf(want, want * BUDGET_TICKS - e.budget).coerceAtLeast(0f)
    }

    fun hasBudget(level: ServerLevel, id: Long, kind: GasKind): Boolean =
        (levels.get(level)?.get(id * KIND_SPREAD + kind.ordinal)?.budget ?: 0f) > 0f

    private fun moved(x: Double, y: Double, z: Double): Boolean = x * x + y * y + z * z > MOVE_RESEND * MOVE_RESEND

    private fun send(level: ServerLevel, e: GasJetEmitter, stop: Boolean) {
        e.sentPx = e.px; e.sentPy = e.py; e.sentPz = e.pz
        e.sentDx = e.dx; e.sentDy = e.dy; e.sentDz = e.dz
        e.lastSent = level.gameTime
        val world = Vector3d(e.px, e.py, e.pz)
        if (e.frame != 0L) level.shipObjectWorld.allShips.getById(e.frame)?.transform?.shipToWorld?.transformPosition(world)
        val at = BlockPos.containing(world.x, world.y, world.z)
        SpellNetwork.sendJet(level, ChunkPos(at), e, stop)
    }

    private fun tick(level: ServerLevel) {
        val map = levels.get(level) ?: return
        val time = level.gameTime
        val it = map.long2ObjectEntrySet().fastIterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (e.active && time - e.lastHeld > GasJetTuning.HOLD_TIMEOUT) {
                e.active = false
                send(level, e, true)
            } else if (e.active && time - e.lastSent >= GasJetTuning.RESEND_EVERY) {
                send(level, e, false)
            }
            GasJetSim.tick(level, e, sink, GasJetImpacts)
            GasJetImpacts.flush(e)
            if (!e.active && e.budget > 0f) {
                e.budget = GasField.forceDeposit(level, intakePos.set(e.intake), e.budget, e.kind)
            }
            if (MASS_DEBUG) {
                val ps = e.parcels
                for (n in 0 until ps.n) parcelMass += ps.held[n]
                parcelMass += e.budget
            }
            if (!e.active && e.parcels.n == 0 && e.budget <= 0f) it.remove()
        }
        if (MASS_DEBUG) logMass(level, time)
        if (map.isEmpty()) levels.remove(level)
    }

    private val sink = object : JetSink {
        override fun handOff(level: Level, kind: GasKind, parcels: GasJetParcels, i: Int, parcelMass: Float, force: Boolean): Float =
            if (parcelMass > 0f && level is ServerLevel) spreadOver27(level, kind, parcels, i, parcelMass, force) else 0f

        override fun shed(level: Level, kind: GasKind, parcels: GasJetParcels, i: Int, mass: Float): Float =
            if (level is ServerLevel) depositInCell(level, kind, parcels, i, mass) else 0f
    }

    /** Gives [mass] to the one cell that holds the parcel. Returns what the cell took. */
    private fun depositInCell(level: ServerLevel, kind: GasKind, p: GasJetParcels, i: Int, mass: Float): Float {
        val flow = Vector3d(p.vx[i], p.vy[i], p.vz[i])
        val ship = if (p.frame[i] == 0L) null else level.shipObjectWorld.allShips.getById(p.frame[i])
        ship?.transform?.shipToWorld?.transformDirection(flow)
        val pos = shedPos.set(floor(p.x[i]).toInt(), floor(p.y[i]).toInt(), floor(p.z[i]).toInt())
        return GasField.deposit(level, pos, mass, kind, flow.x, flow.y, flow.z)
    }

    // Server thread only.
    private val shedPos = BlockPos.MutableBlockPos()
    private val intakePos = BlockPos.MutableBlockPos()

    /**
     * Spreads the mass of a stopped parcel over the cells around it by distance, then spills what is left outward. Only
     * cells that a walk over cell faces through open cells reaches take gas, never cells that touch by an edge or a corner.
     * Returns the mass that found no room; the parcel keeps it. A small rest, or all of it with [force], goes farther out.
     */
    private fun spreadOver27(level: ServerLevel, kind: GasKind, p: GasJetParcels, i: Int, mass: Float, force: Boolean): Float {
        val cx = floor(p.x[i]).toInt(); val cy = floor(p.y[i]).toInt(); val cz = floor(p.z[i]).toInt()
        val reach = p.r[i] + 0.6
        val flow = Vector3d(p.vx[i], p.vy[i], p.vz[i])
        val ship = if (p.frame[i] == 0L) null else level.shipObjectWorld.allShips.getById(p.frame[i])
        ship?.transform?.shipToWorld?.transformDirection(flow)
        val pos = BlockPos.MutableBlockPos()
        var left = mass
        var count = walk(level, cx, cy, cz, 1)
        var sum = 0.0
        for (n in 0 until count) sum += weightAt(p, i, cx, cy, cz, order[n], reach)
        if (sum > 1.0e-6) {
            for (n in 0 until count) {
                val w = weightAt(p, i, cx, cy, cz, order[n], reach)
                if (w <= 0.0) continue
                pos.set(cx + dx(order[n]), cy + dy(order[n]), cz + dz(order[n]))
                left -= GasField.deposit(level, pos, (mass * w / sum).toFloat(), kind, flow.x, flow.y, flow.z)
            }
        }
        if (left > SPILL_DONE) {
            // Nearest cells first, out to SPILL_RADIUS cells over faces.
            count = walk(level, cx, cy, cz, SPILL_RADIUS)
            for (n in 0 until count) {
                if (left <= SPILL_DONE) break
                pos.set(cx + dx(order[n]), cy + dy(order[n]), cz + dz(order[n]))
                left -= GasField.deposit(level, pos, left, kind, flow.x, flow.y, flow.z)
            }
        }
        if (left > 0f && (force || left <= SPILL_DONE)) left = GasField.forceDeposit(level, pos.set(cx, cy, cz), left, kind)
        return left
    }

    // Server thread only. A breadth-first walk over the faces of open cells around the parcel cell.
    private val reached = BooleanArray(SPILL_SIDE * SPILL_SIDE * SPILL_SIDE)
    private val order = IntArray(SPILL_SIDE * SPILL_SIDE * SPILL_SIDE)
    private val walkPos = BlockPos.MutableBlockPos()
    private val FACES: Array<Direction> = Direction.values()

    private fun dx(code: Int): Int = code % SPILL_SIDE - SPILL_RADIUS
    private fun dy(code: Int): Int = (code / SPILL_SIDE) % SPILL_SIDE - SPILL_RADIUS
    private fun dz(code: Int): Int = code / (SPILL_SIDE * SPILL_SIDE) - SPILL_RADIUS

    /** Fills [order] with the cells within [radius] of the cell (cx, cy, cz) over open faces, nearest first. Returns how many. */
    private fun walk(level: ServerLevel, cx: Int, cy: Int, cz: Int, radius: Int): Int {
        java.util.Arrays.fill(reached, false)
        val start = SPILL_RADIUS + SPILL_SIDE * (SPILL_RADIUS + SPILL_SIDE * SPILL_RADIUS)
        order[0] = start
        reached[start] = true
        var count = 1
        var next = 0
        while (next < count) {
            val code = order[next++]
            val x = dx(code); val y = dy(code); val z = dz(code)
            for (face in FACES) {
                val nx = x + face.stepX; val ny = y + face.stepY; val nz = z + face.stepZ
                if (abs(nx) > radius || abs(ny) > radius || abs(nz) > radius) continue
                val n = (nx + SPILL_RADIUS) + SPILL_SIDE * ((ny + SPILL_RADIUS) + SPILL_SIDE * (nz + SPILL_RADIUS))
                if (reached[n]) continue
                walkPos.set(cx + nx, cy + ny, cz + nz)
                if (!level.isLoaded(walkPos) || !GasField.open(level, walkPos)) continue
                reached[n] = true
                order[count++] = n
            }
        }
        return count
    }

    private fun weightAt(p: GasJetParcels, i: Int, cx: Int, cy: Int, cz: Int, code: Int, reach: Double): Double =
        weight(p, i, cx + dx(code), cy + dy(code), cz + dz(code), reach)

    private fun weight(p: GasJetParcels, i: Int, x: Int, y: Int, z: Int, reach: Double): Double {
        val dx = x + 0.5 - p.x[i]; val dy = y + 0.5 - p.y[i]; val dz = z + 0.5 - p.z[i]
        return max(0.0, 1.0 - sqrt(dx * dx + dy * dy + dz * dz) / reach)
    }

    private const val KIND_SPREAD = 8L

    // TEMP gas mass debug, remove after the loop check. Run with -Denderkinesis.gasMassDebug=true or ENDERKINESIS_GAS_MASS_DEBUG=true.
    private val MASS_DEBUG = (System.getProperty("enderkinesis.gasMassDebug") ?: System.getenv("ENDERKINESIS_GAS_MASS_DEBUG")).toBoolean()
    private val lastMass = WeakHashMap<ServerLevel, Double>()
    private var parcelMass = 0.0
    private val debugLog = com.mojang.logging.LogUtils.getLogger()

    private fun logMass(level: ServerLevel, time: Long) {
        if (time % 20L != 0L) {
            parcelMass = 0.0
            return
        }
        val grid = GasField.totalMass(level)
        val total = grid + parcelMass
        val before = lastMass.put(level, total)
        debugLog.info("TEMP gas mass {}: grid={} parcels={} total={} delta={}", level.dimension().location(), grid, parcelMass, total, if (before == null) 0.0 else total - before)
        parcelMass = 0.0
    }
    private const val BUDGET_TICKS = 4f
    private const val SPILL_RADIUS = 4
    private const val SPILL_SIDE = 2 * SPILL_RADIUS + 1
    private const val SPILL_DONE = 1.0e-4f
}
