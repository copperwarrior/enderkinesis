package org.shipwrights.enderkinesis.spell

import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.util.blockLoaded
import org.shipwrights.enderkinesis.util.colliderClip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** The numbers of the puff jet, as the user tuned them in the preview. Server and client read the same values. */
object GasJetTuning {
    const val SPREAD_BLEND = 0.55
    const val SPREAD_K = 0.25
    const val SPEED_BLEND = 0.40
    const val SPEED_L = 7.0
    const val LAUNCH_SPEED = 1.30
    const val R0 = 0.125
    const val PARCELS_PER_TICK = 3
    // The linear cone: tan 11.5 degrees.
    const val LINEAR_SPREAD = 0.2
    const val SPEED_POWER = 0.6
    const val MIN_SPEED = 0.07
    const val MAX_AGE = 34
    const val WALL_WIDEN = 1.08
    const val WALL_PATH = 0.3
    const val SPEED_JITTER = 0.02
    /** Parcels of one emitter; with 3 a tick and a 34 tick life, a full jet holds about 100. */
    const val PARCEL_CAP = 96
    const val RADIUS_CAP = 2.0
    const val HOLD_TIMEOUT = 4
    const val RESEND_EVERY = 40
    /** Gas in cell units that a full-strength jet hands to the grid each tick. */
    const val MASS_PER_TICK = 0.5f

    /** Share of the launch speed that a vent adds at an intake of 2 cells; 1 cell or less adds none. */
    const val PRESSURE_SPEED_BOOST = 0.15

    /** The speed factor of a vent jet from its [parcelMass]: 3 parcels a tick of it make the intake amount. */
    fun pressureSpeed(parcelMass: Float): Double =
        1.0 + PRESSURE_SPEED_BOOST * ((parcelMass * PARCELS_PER_TICK).coerceIn(1f, GasField.MAX_AMOUNT) - 1f)

    /** Speed factor of an emitter: the strength (power / 15 for a vent) times [pressureSpeed]. Strength 1 at an intake of 1 is 1. */
    fun emitterSpeed(strength: Float, parcelMass: Float): Double = strength * pressureSpeed(parcelMass)

    /** World path length that a straight parcel of speed factor [emitterSpeed] covers before it stops. */
    fun reach(emitterSpeed: Double): Double {
        var s = 0.0
        var ticks = 0
        while (ticks < MAX_AGE) {
            val v = speed(radius(s), s) * emitterSpeed
            if (v < MIN_SPEED) break
            s += v
            ticks++
        }
        return s
    }

    /** Path length (blocks) before a parcel starts to shed gas into the grid, so the nozzle cell stays clear. */
    const val SHED_START = 1.0
    /** Buoyant acceleration scale in blocks per tick squared: 9.81 m/s² for the sim on both sides, so they agree. */
    const val BUOYANCY_G = 9.81 / 400.0

    /**
     * Share of its gas that a parcel still carries after path length [s]. It follows the speed from [SHED_START] on, as
     * entrainment loses mass in step with speed. The parcel sheds the drop of this share each tick.
     */
    fun remaining(s: Double): Double {
        if (s <= SHED_START) return 1.0
        val start = speed(radius(SHED_START), SHED_START)
        return (speed(radius(s), s) / start).coerceIn(0.0, 1.0)
    }

    fun radius(s: Double): Double =
        (1.0 - SPREAD_BLEND) * (R0 + LINEAR_SPREAD * s) + SPREAD_BLEND * R0 * exp(SPREAD_K * s)

    fun speed(r: Double, s: Double): Double =
        LAUNCH_SPEED * ((1.0 - SPEED_BLEND) * (R0 / r).pow(SPEED_POWER) + SPEED_BLEND * exp(-s / SPEED_L))

}

/** Parcels of one emitter as plain arrays. Position and velocity are in the frame of [frame]: 0 is the world, else a ship id. */
class GasJetParcels(capacity: Int) {
    var n = 0
    val x = DoubleArray(capacity); val y = DoubleArray(capacity); val z = DoubleArray(capacity)
    val vx = DoubleArray(capacity); val vy = DoubleArray(capacity); val vz = DoubleArray(capacity)
    /** Path length in world blocks. */
    val s = DoubleArray(capacity)
    /** Radius in blocks of the frame. */
    val r = DoubleArray(capacity)
    val age = IntArray(capacity)
    /** Gas mass (cell units) that the parcel still carries, and its mass in kg for pushes (0: no push). */
    val held = FloatArray(capacity)
    /** Ticks a stopped parcel with gas left waits before its next hand-off, and the tries so far. */
    val wait = IntArray(capacity)
    val tries = IntArray(capacity)
    val push = FloatArray(capacity)
    val frame = LongArray(capacity)
    /** The world position at the last step, so a parcel whose ship is gone can still give its gas to the world. */
    val wx = DoubleArray(capacity); val wy = DoubleArray(capacity); val wz = DoubleArray(capacity)
    /** The last point in an open cell, in the frame [lastFrame]. */
    val lx = DoubleArray(capacity); val ly = DoubleArray(capacity); val lz = DoubleArray(capacity)
    val lastFrame = LongArray(capacity)
    // Position and radius one tick ago, in the same frame, for the client to blend between ticks.
    val ox = DoubleArray(capacity); val oy = DoubleArray(capacity); val oz = DoubleArray(capacity); val oldR = DoubleArray(capacity)

    fun settlePrevious(i: Int) {
        ox[i] = x[i]; oy[i] = y[i]; oz[i] = z[i]; oldR[i] = r[i]
    }

    fun removeAt(i: Int) {
        val last = --n
        if (i == last) return
        x[i] = x[last]; y[i] = y[last]; z[i] = z[last]
        vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last]
        s[i] = s[last]; r[i] = r[last]; age[i] = age[last]; frame[i] = frame[last]
        wx[i] = wx[last]; wy[i] = wy[last]; wz[i] = wz[last]
        lx[i] = lx[last]; ly[i] = ly[last]; lz[i] = lz[last]; lastFrame[i] = lastFrame[last]
        held[i] = held[last]; push[i] = push[last]; wait[i] = wait[last]; tries[i] = tries[last]
        ox[i] = ox[last]; oy[i] = oy[last]; oz[i] = oz[last]; oldR[i] = oldR[last]
    }
}

/** Takes the gas of a parcel. The server hands it to the grid; the client has no sink. */
interface JetSink {
    /**
     * The parcel stopped: [parcelMass] goes to the grid. Returns what found no room; the parcel waits and tries again.
     * [force]: the parcel waited too long, so the gas goes to the nearest open cells farther out.
     */
    fun handOff(level: Level, kind: GasKind, parcels: GasJetParcels, i: Int, parcelMass: Float, force: Boolean): Float

    /** The parcel moves on and gives up to [mass]. Returns what the grid took; the parcel keeps the rest. */
    fun shed(level: Level, kind: GasKind, parcels: GasJetParcels, i: Int, mass: Float): Float
}

/** Server only: the push of the parcels of a vent jet on ships, bodies and entities. */
interface JetPush {
    /** A parcel hit the wall of body [bodyId] at a world point; [jx], [jy], [jz] is the impulse in kg m/s. */
    @Suppress("LongParameterList") // A body, a point and an impulse.
    fun wall(e: GasJetEmitter, bodyId: Long, x: Double, y: Double, z: Double, jx: Double, jy: Double, jz: Double)

    /** Tests the world segment of a parcel for bodies and entities. True when the parcel hit one and ends. */
    fun segment(level: Level, e: GasJetEmitter, parcels: GasJetParcels, i: Int, from: Vector3d, to: Vector3d): Boolean
}

/** One jet source: its place in its frame, its direction and its parcels. */
class GasJetEmitter(val id: Long, val kind: GasKind, seed: Long) {
    var frame = 0L
    var px = 0.0; var py = 0.0; var pz = 0.0
    var dx = 1.0; var dy = 0.0; var dz = 0.0
    var strength = 1f
    var parcelMass = 0f
    var startTick = 0L
    var seed = seed
    var active = true
    var lastHeld = 0L
    var lastSent = 0L
    var sentDx = 0.0; var sentDy = 0.0; var sentDz = 0.0; var sentPx = 0.0; var sentPy = 0.0; var sentPz = 0.0
    var carry = 0f
    /** A vent jet carries only what its vent drained: each hold adds the drained gas here, and each parcel takes from it. */
    var budgeted = false
    var budget = 0f
    /** Server: the vent intake cell (BlockPos.asLong) that takes back the budget the jet did not send. */
    var intake = 0L
    /** Recoil in newtons of the vent at full flow, set by the owner; 0 for a jet that does not push. */
    var thrust = 0.0
    /** Server: impulse sums by target body id, then the weighted point. */
    val pushSums = it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<DoubleArray>()
    val parcels = GasJetParcels(GasJetTuning.PARCEL_CAP)
    val random: RandomSource = RandomSource.create(seed)
    internal val a = Vector3d(); internal val b = Vector3d(); internal val c = Vector3d()
}

/** The step of a jet, the same on both sides; only the server passes a [JetSink]. */
object GasJetSim {
    private const val TICK_SECONDS = 0.05
    private val AXIS_ORDERS = arrayOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
    private const val RETRY_TICKS = 10
    private const val MAX_TRIES = 60
    private const val TICKS_PER_SECOND = 20.0
    /** World blocks that a parcel stays in front of a wall face it hit or would start in. */
    private const val WALL_INSET = 0.05

    fun tick(level: Level, e: GasJetEmitter, sink: JetSink?, push: JetPush? = null) {
        emit(level, e)
        val ps = e.parcels
        var i = ps.n - 1
        while (i >= 0) {
            if (!step(level, e, i, sink, push)) ps.removeAt(i)
            i--
        }
    }

    private fun emit(level: Level, e: GasJetEmitter) {
        // A nozzle against a wall sends nothing; its budget then stops the drain.
        if (!e.active || !GasField.open(level, stepAt.set(Math.floor(e.px), Math.floor(e.py), Math.floor(e.pz)))) return
        e.carry += GasJetTuning.PARCELS_PER_TICK * e.strength
        val ps = e.parcels
        val speed = GasJetTuning.LAUNCH_SPEED * GasJetTuning.emitterSpeed(e.strength, e.parcelMass)
        // Each tick the parcels carry the impulse of the recoil: rate * mass * speed = thrust * 0.05 s.
        val pushMass = if (e.thrust > 0.0 && speed > 0.0) {
            (e.thrust * TICK_SECONDS / (GasJetTuning.PARCELS_PER_TICK * e.strength * speed * TICKS_PER_SECOND)).toFloat()
        } else 0f
        val ship = shipOf(level, e.frame)
        // Parcel speeds are in frame blocks a tick; the path length stays in world blocks.
        val scale = ship?.transform?.shipToWorldScaling?.x() ?: 1.0
        val frameSpeed = speed / scale
        while (e.carry >= 1f) {
            e.carry -= 1f
            if (ps.n >= GasJetTuning.PARCEL_CAP) continue
            val gas = if (e.budgeted) minOf(e.parcelMass, e.budget) else e.parcelMass
            if (e.budgeted) {
                if (gas <= 0f) continue
                e.budget -= gas
            }
            // Spread over the tick, so the stream has no gaps between parcels.
            val d = clearAdvance(level, e, ship, frameSpeed * e.random.nextDouble())
            val i = ps.n++
            ps.x[i] = e.px + e.dx * d; ps.y[i] = e.py + e.dy * d; ps.z[i] = e.pz + e.dz * d
            ps.vx[i] = e.dx * frameSpeed; ps.vy[i] = e.dy * frameSpeed; ps.vz[i] = e.dz * frameSpeed
            ps.s[i] = d * scale; ps.r[i] = GasJetTuning.R0; ps.age[i] = 0; ps.frame[i] = e.frame
            ps.held[i] = gas; ps.push[i] = pushMass; ps.wait[i] = 0; ps.tries[i] = 0
            ps.lx[i] = e.px; ps.ly[i] = e.py; ps.lz[i] = e.pz; ps.lastFrame[i] = e.frame
            ps.settlePrevious(i)
            val world = e.c.set(ps.x[i], ps.y[i], ps.z[i])
            ship?.transform?.shipToWorld?.transformPosition(world)
            ps.wx[i] = world.x; ps.wy[i] = world.y; ps.wz[i] = world.z
        }
    }

    /**
     * The part of the advance [d] (frame blocks) from the launch point that stays clear of solids. Without it a parcel can
     * start inside a wall one cell ahead, and its hand-off then spills to the far side of that wall.
     */
    private fun clearAdvance(level: Level, e: GasJetEmitter, ship: Ship?, d: Double): Double {
        val from = e.a.set(e.px, e.py, e.pz)
        val to = e.b.set(e.px + e.dx * d, e.py + e.dy * d, e.pz + e.dz * d)
        if (ship != null) {
            ship.transform.shipToWorld.transformPosition(from)
            ship.transform.shipToWorld.transformPosition(to)
        }
        val total = from.distance(to)
        if (total <= 1.0e-9) return d
        val hit = level.clip(colliderClip(Vec3(from.x, from.y, from.z), Vec3(to.x, to.y, to.z)))
        if (hit.type == HitResult.Type.MISS) return d
        val hx = hit.location.x - from.x; val hy = hit.location.y - from.y; val hz = hit.location.z - from.z
        return d * ((sqrt(hx * hx + hy * hy + hz * hz) - WALL_INSET) / total).coerceIn(0.0, 1.0)
    }

    private fun shipOf(level: Level, id: Long): Ship? =
        if (id == 0L) null else level.shipObjectWorld.allShips.getById(id)

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One parcel step: curves, one wall test, frame changes.
    private fun step(level: Level, e: GasJetEmitter, i: Int, sink: JetSink?, push: JetPush?): Boolean {
        val ps = e.parcels
        val fid = ps.frame[i]
        val ship = shipOf(level, fid)
        if (fid != 0L && ship == null) {
            // The ship is gone: the parcel stops at its last world point and hands its gas to the world next step.
            ps.frame[i] = 0L
            ps.x[i] = ps.wx[i]; ps.y[i] = ps.wy[i]; ps.z[i] = ps.wz[i]
            ps.vx[i] = 0.0; ps.vy[i] = 0.0; ps.vz[i] = 0.0
            ps.settlePrevious(i)
            return true
        }
        if (ps.wait[i] > 0) {
            ps.wait[i]--
            return true
        }
        keepOpen(level, e, i)
        val scale = ship?.transform?.shipToWorldScaling?.x() ?: 1.0
        var speedNow = sqrt(ps.vx[i] * ps.vx[i] + ps.vy[i] * ps.vy[i] + ps.vz[i] * ps.vz[i])
        if (speedNow * scale < GasJetTuning.MIN_SPEED || ps.age[i] >= GasJetTuning.MAX_AGE) return !finish(level, e, ps, i, sink)
        val sBefore = ps.s[i]
        val s = sBefore + speedNow * scale
        val rWorld = GasJetTuning.radius(s).coerceAtMost(GasJetTuning.RADIUS_CAP)
        val wanted = GasJetTuning.speed(GasJetTuning.radius(s), s) * GasJetTuning.emitterSpeed(e.strength, e.parcelMass) / scale
        addBuoyancy(e, ship, i, speedNow * scale, scale)
        val k = wanted / sqrt(ps.vx[i] * ps.vx[i] + ps.vy[i] * ps.vy[i] + ps.vz[i] * ps.vz[i]).coerceAtLeast(1.0e-9)
        val jitter = GasJetTuning.SPEED_JITTER * wanted
        ps.vx[i] = ps.vx[i] * k + (e.random.nextDouble() - 0.5) * jitter
        ps.vy[i] = ps.vy[i] * k + (e.random.nextDouble() - 0.5) * jitter
        ps.vz[i] = ps.vz[i] * k + (e.random.nextDouble() - 0.5) * jitter
        speedNow = wanted
        ps.settlePrevious(i)
        ps.s[i] = s
        ps.r[i] = rWorld / scale
        ps.age[i]++
        if (sink != null) shed(level, e, ps, i, sBefore, s, sink)
        val from = e.a.set(ps.x[i], ps.y[i], ps.z[i])
        val to = e.b.set(from).add(ps.vx[i], ps.vy[i], ps.vz[i])
        if (ship != null) {
            ship.transform.shipToWorld.transformPosition(from)
            ship.transform.shipToWorld.transformPosition(to)
        }
        ps.wx[i] = from.x; ps.wy[i] = from.y; ps.wz[i] = from.z
        val end = BlockPos.containing(to.x, to.y, to.z)
        if (!level.blockLoaded(end)) return !finish(level, e, ps, i, sink)
        if (push != null && ps.push[i] > 0f && push.segment(level, e, ps, i, from, to)) return !finish(level, e, ps, i, sink)
        val hit = level.clip(colliderClip(Vec3(from.x, from.y, from.z), Vec3(to.x, to.y, to.z)))
        if (hit.type == HitResult.Type.MISS) {
            // A step between two solids that touch only by an edge or a corner has no open way over cell faces: it ends here.
            if (!facesOpen(level, from, to)) return !finish(level, e, ps, i, sink)
            ps.x[i] += ps.vx[i]; ps.y[i] += ps.vy[i]; ps.z[i] += ps.vz[i]
            if (ship != null) leaveHull(ship, ps, i, to, e)
            return true
        }
        wallJet(level, e, i, ship, push, hit.location.x, hit.location.y, hit.location.z, hit.blockPos, hit.direction.stepX, hit.direction.stepY, hit.direction.stepZ)
        return true
    }

    // Server and client sim thread only.
    private val stepAt = BlockPos.MutableBlockPos()

    /** A parcel in a closed cell goes back to its last open point, so no hand-off or wall test starts inside a solid. */
    private fun keepOpen(level: Level, e: GasJetEmitter, i: Int) {
        val ps = e.parcels
        if (GasField.open(level, stepAt.set(Math.floor(ps.x[i]), Math.floor(ps.y[i]), Math.floor(ps.z[i])))) {
            ps.lx[i] = ps.x[i]; ps.ly[i] = ps.y[i]; ps.lz[i] = ps.z[i]; ps.lastFrame[i] = ps.frame[i]
            return
        }
        val p = e.c.set(ps.lx[i], ps.ly[i], ps.lz[i])
        if (ps.lastFrame[i] != ps.frame[i]) {
            if (ps.lastFrame[i] != 0L) (shipOf(level, ps.lastFrame[i]) ?: return).transform.shipToWorld.transformPosition(p)
            shipOf(level, ps.frame[i])?.transform?.worldToShip?.transformPosition(p)
        }
        ps.x[i] = p.x; ps.y[i] = p.y; ps.z[i] = p.z
        ps.settlePrevious(i)
    }

    /**
     * True when the move from the cell of [a] to the cell of [b] has an order of single-axis steps through open cells. A move
     * that changes two or three coordinates would otherwise slip through the gap between diagonal solids.
     */
    private fun facesOpen(level: Level, a: Vector3d, b: Vector3d): Boolean {
        val fx = Math.floor(a.x).toInt(); val fy = Math.floor(a.y).toInt(); val fz = Math.floor(a.z).toInt()
        val sx = Math.floor(b.x).toInt() - fx; val sy = Math.floor(b.y).toInt() - fy; val sz = Math.floor(b.z).toInt() - fz
        val changed = (if (sx != 0) 1 else 0) + (if (sy != 0) 1 else 0) + (if (sz != 0) 1 else 0)
        if (changed < 2 || abs(sx) > 1 || abs(sy) > 1 || abs(sz) > 1) return true
        for (order in AXIS_ORDERS) {
            var x = fx; var y = fy; var z = fz
            var open = true
            for (axis in order) {
                when (axis) {
                    0 -> if (sx != 0) x += sx else continue
                    1 -> if (sy != 0) y += sy else continue
                    else -> if (sz != 0) z += sz else continue
                }
                if (!GasField.open(level, stepAt.set(x, y, z))) { open = false; break }
            }
            if (open) return true
        }
        return false
    }

    /** Pushes the velocity of parcel [i] up by the buoyancy of its gas, more as the parcel slows. Heavy gas sags. */
    private fun addBuoyancy(e: GasJetEmitter, ship: Ship?, i: Int, speedWorld: Double, scale: Double) {
        val launch = GasJetTuning.LAUNCH_SPEED * GasJetTuning.emitterSpeed(e.strength, e.parcelMass)
        if (launch <= 0.0) return
        val slow = 1.0 - (speedWorld / launch).coerceIn(0.0, 1.0)
        val a = GasJetTuning.BUOYANCY_G * (1.0 - e.kind.airDensityRatio) * slow / scale
        val up = e.c.set(0.0, a, 0.0)
        ship?.transform?.worldToShip?.transformDirection(up)
        val ps = e.parcels
        ps.vx[i] += up.x; ps.vy[i] += up.y; ps.vz[i] += up.z
    }

    /**
     * Hands the gas of a stopped parcel to the grid. True when the parcel is done. A parcel with gas that found no room
     * waits [RETRY_TICKS] and tries again, as a jet into a full space pushes back. After [MAX_TRIES] tries it spills
     * farther out; gas never vanishes, so it waits until all of it finds room.
     */
    private fun finish(level: Level, e: GasJetEmitter, ps: GasJetParcels, i: Int, sink: JetSink?): Boolean {
        val left = sink?.handOff(level, e.kind, ps, i, ps.held[i], ps.tries[i] >= MAX_TRIES) ?: 0f
        if (left <= 0f) return true
        if (ps.tries[i] < MAX_TRIES) ps.tries[i]++
        ps.held[i] = left
        ps.wait[i] = RETRY_TICKS
        return false
    }

    /** Gives the grid the gas that the parcel lost with its speed this tick. */
    private fun shed(level: Level, e: GasJetEmitter, ps: GasJetParcels, i: Int, sBefore: Double, s: Double, sink: JetSink) {
        val drop = GasJetTuning.remaining(sBefore) - GasJetTuning.remaining(s)
        if (drop <= 0.0) return
        val want = minOf((e.parcelMass * drop).toFloat(), ps.held[i])
        if (want <= 0f) return
        ps.held[i] -= sink.shed(level, e.kind, ps, i, want)
    }

    /** A parcel past the hull box of its ship moves on in the world, so it no longer turns with the ship. */
    private fun leaveHull(ship: Ship, ps: GasJetParcels, i: Int, world: Vector3d, e: GasJetEmitter) {
        val box = ship.worldAABB
        if (world.x >= box.minX() && world.x <= box.maxX() && world.y >= box.minY() && world.y <= box.maxY() &&
            world.z >= box.minZ() && world.z <= box.maxZ()
        ) return
        val v = e.c.set(ps.vx[i], ps.vy[i], ps.vz[i])
        absoluteVelocity(ship, world, v)
        val scale = ship.transform.shipToWorldScaling.x()
        ps.x[i] = world.x; ps.y[i] = world.y; ps.z[i] = world.z
        ps.vx[i] = v.x; ps.vy[i] = v.y; ps.vz[i] = v.z
        ps.r[i] *= scale
        ps.frame[i] = 0L
        ps.settlePrevious(i)
    }

    /** [v] (frame axes) becomes the world velocity of the point [world], with the frame motion of [ship] added. */
    private fun absoluteVelocity(ship: Ship, world: Vector3d, v: Vector3d) {
        ship.transform.shipToWorld.transformDirection(v)
        val r = Vector3d(world).sub(ship.transform.positionInWorld)
        val spin = ship.angularVelocity.cross(r, Vector3d())
        v.add(spin.add(ship.velocity).div(TICKS_PER_SECOND))
    }

    /** The world velocity [v] becomes the velocity relative to [ship], in its axes. */
    private fun relativeVelocity(ship: Ship, world: Vector3d, v: Vector3d) {
        val r = Vector3d(world).sub(ship.transform.positionInWorld)
        v.sub(ship.angularVelocity.cross(r, Vector3d()).add(ship.velocity).div(TICKS_PER_SECOND))
        ship.transform.worldToShip.transformDirection(v)
    }

    @Suppress("LongParameterList") // The hit point, its block and its face.
    private fun wallJet(
        level: Level, e: GasJetEmitter, i: Int, from: Ship?, push: JetPush?,
        hx: Double, hy: Double, hz: Double, block: BlockPos, nx: Int, ny: Int, nz: Int,
    ) {
        val ps = e.parcels
        val into = level.getShipManagingPos(block)
        // VS2 gives a ship hit a world location but a shipyard face; a wrong-axis inset puts the parcel inside the wall.
        val n = e.b.set(nx.toDouble(), ny.toDouble(), nz.toDouble())
        into?.transform?.shipToWorldRotation?.transform(n)
        val point = e.a.set(hx + n.x * WALL_INSET, hy + n.y * WALL_INSET, hz + n.z * WALL_INSET)
        val v = e.c.set(ps.vx[i], ps.vy[i], ps.vz[i])
        if (from != null) absoluteVelocity(from, point, v)
        // Relative to the wall that was hit, drop the part of the velocity that goes into it.
        val world = Vector3d(v)
        if (into != null) {
            world.sub(into.angularVelocity.cross(Vector3d(point).sub(into.transform.positionInWorld), Vector3d()).add(into.velocity).div(TICKS_PER_SECOND))
        }
        val dot = world.x * n.x + world.y * n.y + world.z * n.z
        if (dot < 0.0) world.sub(n.x * dot, n.y * dot, n.z * dot)
        val scaleFrom = from?.transform?.shipToWorldScaling?.x() ?: 1.0
        if (push != null && into != null && dot < 0.0 && ps.push[i] > 0f) {
            // The wall takes the normal part that the parcel loses. The speeds here are in blocks a tick.
            val j = dot * ps.push[i] * TICKS_PER_SECOND
            push.wall(e, into.id, point.x, point.y, point.z, n.x * j, n.y * j, n.z * j)
        }
        if (into != null) {
            into.transform.worldToShip.transformDirection(world)
            into.transform.worldToShip.transformPosition(point)
            ps.frame[i] = into.id
            ps.r[i] = ps.r[i] * scaleFrom / into.transform.shipToWorldScaling.x()
        } else {
            ps.frame[i] = 0L
            ps.r[i] *= scaleFrom
        }
        ps.x[i] = point.x; ps.y[i] = point.y; ps.z[i] = point.z
        ps.vx[i] = world.x; ps.vy[i] = world.y; ps.vz[i] = world.z
        ps.r[i] = max(ps.r[i] * GasJetTuning.WALL_WIDEN, GasJetTuning.R0)
        ps.s[i] += GasJetTuning.WALL_PATH
        ps.settlePrevious(i)
    }
}
