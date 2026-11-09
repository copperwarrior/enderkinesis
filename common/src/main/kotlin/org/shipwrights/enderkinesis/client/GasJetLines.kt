// Client jet lines are built and read only on the render thread.
package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.client.volume.VolumeView
import org.shipwrights.enderkinesis.spell.GasJetEmitter
import org.shipwrights.enderkinesis.spell.GasJetParcels
import org.shipwrights.enderkinesis.spell.GasJetTuning
import org.shipwrights.enderkinesis.spell.GasKind
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One smooth centreline for each run of live parcels of an emitter, built once a frame from the parcels blended by the
 * frame time, then read by every volume. The line is resampled by arc length; each sample holds the radius and the
 * flux concentration c = 3 * mdot / (v * pi * r^2), capped at 1, with a fade at the tip.
 * All samples live in one flat buffer under a global budget; the nearest jets are built first.
 */
@OptIn(GameTickOnly::class)
object GasJetLines {
    const val MAX_LINES = 256
    /** Samples of all lines together; the nearest lines take what they need and the rest share what is left, each keeping 3. */
    const val SAMPLE_BUDGET = 4096
    const val MAX_SAMPLES_PER_LINE = 97
    const val SAMPLE_TEX_W = 256
    const val SAMPLE_TEX_H = SAMPLE_BUDGET / SAMPLE_TEX_W
    const val RUN_TEX_W = 1024
    const val RUN_TEX_H = 32
    const val RUN_CAP = RUN_TEX_W * RUN_TEX_H
    const val PAGE_SIZE = 8
    const val PAGE_TILES = PAGE_SIZE * PAGE_SIZE * PAGE_SIZE
    const val ATLAS_PAGES_XY = 8
    const val ATLAS_PAGES_Z = 4
    const val ATLAS_PAGES = ATLAS_PAGES_XY * ATLAS_PAGES_XY * ATLAS_PAGES_Z
    const val ATLAS_W = ATLAS_PAGES_XY * PAGE_SIZE
    const val ATLAS_D = ATLAS_PAGES_Z * PAGE_SIZE
    const val TABLE_X = 16
    const val TABLE_Y = 8
    const val TABLE_Z = 16
    const val RUNS_PER_TILE = 8
    private const val MIN_SPACING = 0.15
    private const val RADIUS_SPACING = 0.4
    /** About 2 pixels at 1080p and 70 degrees, in radians. */
    private const val PIXEL_ANGLE = 0.003
    /** Very far, over-budget and over-8-runs jets are drawn into the 1-block field texture (for drawing only), so none is hidden. */
    const val OVERFLOW_SPLAT = true
    const val SPLAT_FAR = 64.0
    const val SPLAT_BUDGET = 2048
    const val SPLAT_LINES = 128
    private const val SPLAT_SPACING = 1.0
    private const val SPLAT_MAX_SAMPLES = 64
    /** Drawn concentration = FLUX_FACTOR * mdot / (v * pi * r^2). 3 would hold the whole flux in the (1 - rho^2)^2 profile; the user halved it twice. */
    private const val FLUX_FACTOR = 0.75
    private const val SUB = 8
    private const val GAP = 1.5
    private const val SKIP = 0.05
    private const val FADE_LENGTH = 1.0
    private const val CONTROLS = GasJetTuning.PARCEL_CAP + 2
    private const val POLY = (CONTROLS - 1) * SUB + 1

    /** A line: [first] is its first sample in the flat arrays, [n] its count. Positions are from the integer origin. */
    class Line {
        var kind = GasKind.MIASMA
        var frame = 0L
        var first = 0
        var n = 0
        var ox = 0; var oy = 0; var oz = 0
        var distSq = 0.0
    }

    val lines = Array(MAX_LINES) { Line() }

    /** A coarse line (about 1 block between samples) for the field splat; positions are from its integer origin. */
    class SplatLine {
        var kind = GasKind.MIASMA
        var frame = 0L
        var first = 0
        var n = 0
        var ox = 0; var oy = 0; var oz = 0
    }

    val splatLines = Array(SPLAT_LINES) { SplatLine() }
    var splatCount = 0
        private set
    private var splatUsed = 0
    val sx = FloatArray(SPLAT_BUDGET); val sy = FloatArray(SPLAT_BUDGET); val sz = FloatArray(SPLAT_BUDGET)
    val sr = FloatArray(SPLAT_BUDGET); val sc = FloatArray(SPLAT_BUDGET)
    var count = 0
        private set
    var used = 0
        private set
    val x = FloatArray(SAMPLE_BUDGET); val y = FloatArray(SAMPLE_BUDGET); val z = FloatArray(SAMPLE_BUDGET)
    val r = FloatArray(SAMPLE_BUDGET); val c = FloatArray(SAMPLE_BUDGET); val s = FloatArray(SAMPLE_BUDGET)

    private var builtFrame = Long.MIN_VALUE
    private val order = IntArray(GasJetTuning.PARCEL_CAP)
    private val emitterOrder = IntArray(512)
    private val emitterDist = DoubleArray(512)
    private val cx = DoubleArray(CONTROLS); private val cy = DoubleArray(CONTROLS); private val cz = DoubleArray(CONTROLS)
    // Parcels spawn and end only at tick edges: a control fades out over its last tick (out) and in over its first (in).
    private val cOut = DoubleArray(CONTROLS); private val cIn = DoubleArray(CONTROLS)
    // The fade-out of each polyline point, linear between its controls.
    private val pw = DoubleArray(POLY)
    private var shipScaleFrame = 0L
    private var stub = false
    private var shipScale = 1.0
    private val px = DoubleArray(POLY); private val py = DoubleArray(POLY); private val pz = DoubleArray(POLY)
    private val arc = DoubleArray(POLY)
    private val scratch = Vector3d()
    private var controls = 0
    private var runFrame = 0L
    private var runS0 = 0.0
    private var frameTime = 0.0

    /** Builds the lines once for [frameId]; later calls in the same frame return at once. */
    fun update(level: ClientLevel, cam: Vec3, partial: Float, frameId: Long, view: VolumeView) {
        if (frameId == builtFrame) return
        builtFrame = frameId
        count = 0
        used = 0
        splatCount = 0
        splatUsed = 0
        cullView = view
        frameTime = partial.toDouble().coerceIn(0.0, 1.0)
        val all = GasJetClient.emitters
        val n = min(all.size, emitterOrder.size)
        if (n == 0) return
        // Nearest first, by an insertion sort on a reused array.
        for (i in 0 until n) {
            val e = all[i]
            val d = distanceSq(level, cam, e.frame, e.px, e.py, e.pz)
            var j = i - 1
            while (j >= 0 && emitterDist[j] > d) { emitterDist[j + 1] = emitterDist[j]; emitterOrder[j + 1] = emitterOrder[j]; j-- }
            emitterDist[j + 1] = d
            emitterOrder[j + 1] = i
        }
        for (k in 0 until n) {
            if (!OVERFLOW_SPLAT && (count >= MAX_LINES || used >= SAMPLE_BUDGET - 2)) break
            emittersLeft = n - k
            buildEmitter(level, cam, all[emitterOrder[k]])
        }
    }

    private fun distanceSq(level: ClientLevel, cam: Vec3, frame: Long, ax: Double, ay: Double, az: Double): Double {
        scratch.set(ax, ay, az)
        if (frame != 0L) {
            val ship = level.shipObjectWorld.allShips.getById(frame) as? ClientShip ?: return Double.MAX_VALUE
            ship.renderTransform.shipToWorld.transformPosition(scratch)
        }
        val dx = scratch.x - cam.x; val dy = scratch.y - cam.y; val dz = scratch.z - cam.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun buildEmitter(level: ClientLevel, cam: Vec3, e: GasJetEmitter) {
        val ps = e.parcels
        val n = ps.n
        controls = 0
        if (e.active) {
            // A vent jet is drawn from the middle of its block, so it seems to come out through the slats. Drawing only.
            if (isVent(e)) addControl(e.px - e.dx * VENT_BACK, e.py - e.dy * VENT_BACK, e.pz - e.dz * VENT_BACK, e.frame, -VENT_BACK, 1.0, 1.0)
            addControl(e.px, e.py, e.pz, e.frame, 0.0, 1.0, 1.0)
        }
        for (i in 0 until n) {
            val v = ps.s[i]
            var j = i - 1
            while (j >= 0 && ps.s[order[j]] > v) { order[j + 1] = order[j]; j-- }
            order[j + 1] = i
        }
        for (k in 0 until n) {
            val i = order[k]
            val x = ps.ox[i] + (ps.x[i] - ps.ox[i]) * frameTime
            val y = ps.oy[i] + (ps.y[i] - ps.oy[i]) * frameTime
            val z = ps.oz[i] + (ps.z[i] - ps.oz[i]) * frameTime
            if (controls > 0) {
                val dx = x - cx[controls - 1]; val dy = y - cy[controls - 1]; val dz = z - cz[controls - 1]
                val gap = sqrt(dx * dx + dy * dy + dz * dz)
                if (ps.frame[i] != runFrame || gap > GAP) {
                    finishRun(level, cam, e, false)
                    controls = 0
                } else if (gap < SKIP) continue
            }
            addControl(x, y, z, ps.frame[i], ps.s[i], fadeOut(level, ps, i), if (ps.age[i] <= 1) frameTime else 1.0)
        }
        finishRun(level, cam, e, true)
    }

    /** 1 - frameTime for a parcel that GasJetSim ends at the next tick (age or speed), else 1. A hit or hand-off end is not known ahead. */
    private fun fadeOut(level: ClientLevel, ps: GasJetParcels, i: Int): Double {
        val speed = sqrt(ps.vx[i] * ps.vx[i] + ps.vy[i] * ps.vy[i] + ps.vz[i] * ps.vz[i]) * scaleOf(level, ps.frame[i])
        return if (ps.age[i] >= GasJetTuning.MAX_AGE || speed < GasJetTuning.MIN_SPEED) 1.0 - frameTime else 1.0
    }

    private fun scaleOf(level: ClientLevel, frame: Long): Double {
        if (frame == 0L) return 1.0
        if (frame != shipScaleFrame) {
            shipScaleFrame = frame
            shipScale = (level.shipObjectWorld.allShips.getById(frame) as? ClientShip)?.transform?.shipToWorldScaling?.x() ?: 1.0
        }
        return shipScale
    }

    // The vent starts its jet 0.6 in front of its centre.
    private fun isVent(e: GasJetEmitter): Boolean = !e.hand

    @Suppress("LongParameterList") // A point, its frame and arc, and its two fades.
    private fun addControl(x: Double, y: Double, z: Double, frame: Long, s: Double, out: Double, fadeIn: Double) {
        if (controls >= CONTROLS) return
        if (controls == 0) { runFrame = frame; runS0 = s }
        cx[controls] = x; cy[controls] = y; cz[controls] = z
        cOut[controls] = out; cIn[controls] = fadeIn
        controls++
    }

    private fun finishRun(level: ClientLevel, cam: Vec3, e: GasJetEmitter, last: Boolean) {
        if (controls < 1) return
        // A lone parcel is a stub of one point: a capsule of length 0, faded like any tip.
        stub = controls == 1
        if (stub) addControl(cx[0], cy[0], cz[0], runFrame, runS0, cOut[0], cIn[0])
        val d2 = distanceSq(level, cam, runFrame, cx[0], cy[0], cz[0])
        if (d2 == Double.MAX_VALUE) return
        buildPolyline()
        val total = tipEnd()
        if (!visible(level, cam)) return
        // Every later emitter keeps at least 3 samples; this one may take the rest, up to its own cap.
        val room = min(MAX_SAMPLES_PER_LINE, SAMPLE_BUDGET - used - 3 * (emittersLeft - 1))
        val tooFar = d2 > SPLAT_FAR * SPLAT_FAR
        if (OVERFLOW_SPLAT && (tooFar || room < 3 || count >= MAX_LINES)) {
            buildSplat(e, last, total)
            return
        }
        if (room < 3 || count >= MAX_LINES) return
        val dist = sqrt(d2)
        val line = lines[count]
        line.kind = e.kind; line.frame = runFrame; line.first = used; line.distSq = d2
        line.ox = floor(px[0]).toInt(); line.oy = floor(py[0]).toInt(); line.oz = floor(pz[0]).toInt()
        val mdot = e.parcelMass * GasJetTuning.PARCELS_PER_TICK * e.strength
        // The spacing follows the radius; when the line would need more than [room] samples, the spacing grows to fit.
        val coarsen = coarsening(total, dist, room)
        var seg = 0
        var at = 0.0
        var i = 0
        while (true) {
            while (seg < polyCount - 2 && arc[seg + 1] < at) seg++
            val span = arc[seg + 1] - arc[seg]
            val u = if (span > 1.0e-9) ((at - arc[seg]) / span).coerceIn(0.0, 1.0) else 0.0
            val fade = fadeAt(seg, u, at, total, last)
            val g = used + i
            x[g] = (px[seg] + (px[seg + 1] - px[seg]) * u - line.ox).toFloat()
            y[g] = (py[seg] + (py[seg + 1] - py[seg]) * u - line.oy).toFloat()
            z[g] = (pz[seg] + (pz[seg + 1] - pz[seg]) * u - line.oz).toFloat()
            val sCurve = max(0.0, runS0 + at)
            val rawR = GasJetTuning.radius(sCurve)
            val rr = min(rawR, GasJetTuning.RADIUS_CAP)
            val v = GasJetTuning.speed(rawR, sCurve) * GasJetTuning.emitterSpeed(e.strength, e.parcelMass)
            val conc = min(1.0, FLUX_FACTOR * mdot * GasJetTuning.remaining(sCurve) / (v * PI * rr * rr)) * fade
            r[g] = rr.toFloat(); c[g] = conc.toFloat(); s[g] = sCurve.toFloat()
            i++
            // A capsule needs two samples, also for a stub of length 0.
            if ((at >= total && i >= 2) || i >= room) break
            at = min(total, at + spacingAt(rr, dist) * coarsen)
        }
        line.n = i
        used += i
        count++
    }

    /** True when the polyline can touch the frustum; the box of every 8th point (and the last) is tested, grown by the radius cap. */
    private fun visible(level: ClientLevel, cam: Vec3): Boolean {
        val view = cullView ?: return true
        var lx = Double.MAX_VALUE; var ly = Double.MAX_VALUE; var lz = Double.MAX_VALUE
        var hx = -Double.MAX_VALUE; var hy = -Double.MAX_VALUE; var hz = -Double.MAX_VALUE
        val ship = if (runFrame == 0L) null else level.shipObjectWorld.allShips.getById(runFrame) as? ClientShip
        if (runFrame != 0L && ship == null) return false
        var i = 0
        while (true) {
            val k = min(i, polyCount - 1)
            scratch.set(px[k], py[k], pz[k])
            ship?.renderTransform?.shipToWorld?.transformPosition(scratch)
            lx = min(lx, scratch.x); hx = maxOf(hx, scratch.x)
            ly = min(ly, scratch.y); hy = maxOf(hy, scratch.y)
            lz = min(lz, scratch.z); hz = maxOf(hz, scratch.z)
            if (k == polyCount - 1) break
            i += 8
        }
        val pad = GasJetTuning.RADIUS_CAP
        return view.boxVisible(
            (lx - pad - cam.x).toFloat(), (ly - pad - cam.y).toFloat(), (lz - pad - cam.z).toFloat(),
            (hx + pad - cam.x).toFloat(), (hy + pad - cam.y).toFloat(), (hz + pad - cam.z).toFloat(),
        )
    }

    /** A coarse copy of the current polyline for the 1-block field splat. */
    private fun buildSplat(e: GasJetEmitter, last: Boolean, total: Double) {
        if (splatCount >= SPLAT_LINES) return
        val room = min(SPLAT_MAX_SAMPLES, SPLAT_BUDGET - splatUsed)
        if (room < 2) return
        val line = splatLines[splatCount]
        line.kind = e.kind; line.frame = runFrame; line.first = splatUsed
        line.ox = floor(px[0]).toInt(); line.oy = floor(py[0]).toInt(); line.oz = floor(pz[0]).toInt()
        val mdot = e.parcelMass * GasJetTuning.PARCELS_PER_TICK * e.strength
        val step = maxOf(SPLAT_SPACING, total / (room - 1))
        var seg = 0
        var at = 0.0
        var i = 0
        while (true) {
            while (seg < polyCount - 2 && arc[seg + 1] < at) seg++
            val span = arc[seg + 1] - arc[seg]
            val u = if (span > 1.0e-9) ((at - arc[seg]) / span).coerceIn(0.0, 1.0) else 0.0
            val fade = fadeAt(seg, u, at, total, last)
            val g = splatUsed + i
            sx[g] = (px[seg] + (px[seg + 1] - px[seg]) * u - line.ox).toFloat()
            sy[g] = (py[seg] + (py[seg + 1] - py[seg]) * u - line.oy).toFloat()
            sz[g] = (pz[seg] + (pz[seg + 1] - pz[seg]) * u - line.oz).toFloat()
            val sCurve = max(0.0, runS0 + at)
            val rawR = GasJetTuning.radius(sCurve)
            val rr = min(rawR, GasJetTuning.RADIUS_CAP)
            val conc = min(1.0, FLUX_FACTOR * mdot * GasJetTuning.remaining(sCurve) / (GasJetTuning.speed(rawR, sCurve) * GasJetTuning.emitterSpeed(e.strength, e.parcelMass) * PI * rr * rr)) * fade
            // The mean of the profile over a block cell: the tube fills only part of it.
            sr[g] = rr.toFloat(); sc[g] = (conc * min(1.0, PI * rr * rr / 3.0)).toFloat()
            i++
            if (at >= total || i >= room) break
            at = min(total, at + step)
        }
        line.n = i
        splatUsed += i
        splatCount++
    }

    /**
     * The arc where the run ends: the last control, pulled back toward the one before it as the last fades out or in, so
     * the end slides when a parcel starts or ends at a tick edge and does not jump.
     */
    private fun tipEnd(): Double {
        val tip = controls - 1
        val atTip = arc[tip * SUB]
        val atPrev = arc[(tip - 1) * SUB]
        return atTip - (1.0 - cOut[tip] * cIn[tip]) * (atTip - atPrev)
    }

    /** The fades at [at] on segment [seg] (fraction [u]): the inner fade-outs, nothing past [end], and the tip taper of the [last] run. */
    private fun fadeAt(seg: Int, u: Double, at: Double, end: Double, last: Boolean): Double {
        if (at > end + 1.0e-9) return 0.0
        var f = pw[seg] + (pw[seg + 1] - pw[seg]) * u
        if (stub) f *= cIn[0]
        if (last) {
            val t = min(1.0, (end - at) / FADE_LENGTH)
            f *= t * t * (3.0 - 2.0 * t)
        }
        return f
    }

    /** Spacing of a sample: at least 0.15, at least 0.4 radius, and at least about 2 pixels at the distance. */
    private fun spacingAt(radius: Double, dist: Double): Double =
        maxOf(MIN_SPACING, RADIUS_SPACING * radius, dist * PIXEL_ANGLE)

    /** The factor (>= 1) on the spacing that makes the line fit [room] samples. */
    private fun coarsening(total: Double, dist: Double, room: Int): Double {
        var factor = 1.0
        repeat(3) {
            var at = 0.0
            var needed = 1
            while (at < total && needed <= room) {
                at += spacingAt(min(GasJetTuning.radius(runS0 + at), GasJetTuning.RADIUS_CAP), dist) * factor
                needed++
            }
            if (needed <= room) return factor
            factor *= needed.toDouble() / room
        }
        return factor
    }

    private var polyCount = 0
    private var emittersLeft = 0
    private const val VENT_BACK = 0.6
    private var cullView: VolumeView? = null

    /** Uniform Catmull-Rom through the controls, [SUB] points for each span, ends doubled. */
    private fun buildPolyline() {
        var p = 0
        // The tip fades through tipEnd, so its own weight stays 1 here.
        val tip = controls - 1
        for (i in 0 until controls - 1) {
            val a = maxOf(i - 1, 0); val b = i; val cc = i + 1; val d = min(i + 2, controls - 1)
            val wb = cOut[b]; val wc = if (cc == tip) 1.0 else cOut[cc]
            for (u in 0 until SUB) {
                val t = u.toDouble() / SUB
                px[p] = spline(cx[a], cx[b], cx[cc], cx[d], t)
                py[p] = spline(cy[a], cy[b], cy[cc], cy[d], t)
                pz[p] = spline(cz[a], cz[b], cz[cc], cz[d], t)
                pw[p] = wb + (wc - wb) * t
                p++
            }
        }
        px[p] = cx[tip]; py[p] = cy[tip]; pz[p] = cz[tip]
        pw[p] = 1.0
        p++
        polyCount = p
        arc[0] = 0.0
        for (i in 1 until p) {
            val dx = px[i] - px[i - 1]; val dy = py[i] - py[i - 1]; val dz = pz[i] - pz[i - 1]
            arc[i] = arc[i - 1] + sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    private fun spline(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (2.0 * p1 + (p2 - p0) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 + (3.0 * p1 - p0 - 3.0 * p2 + p3) * t3)
    }

    /** The block bounds of the lines of [kind] in [frame], grown by the radius and a block, into [out] (min xyz, max xyz). */
    fun bounds(kind: GasKind, frame: Long, out: IntArray): Boolean {
        var any = false
        out[0] = Int.MAX_VALUE; out[1] = Int.MAX_VALUE; out[2] = Int.MAX_VALUE
        out[3] = Int.MIN_VALUE; out[4] = Int.MIN_VALUE; out[5] = Int.MIN_VALUE
        for (l in 0 until count) {
            val line = lines[l]
            if (line.kind != kind || line.frame != frame) continue
            any = true
            for (i in 0 until line.n) {
                val g = line.first + i
                val reach = r[g] + 1.0
                out[0] = min(out[0], floor(line.ox + x[g] - reach).toInt()); out[3] = maxOf(out[3], floor(line.ox + x[g] + reach).toInt())
                out[1] = min(out[1], floor(line.oy + y[g] - reach).toInt()); out[4] = maxOf(out[4], floor(line.oy + y[g] + reach).toInt())
                out[2] = min(out[2], floor(line.oz + z[g] - reach).toInt()); out[5] = maxOf(out[5], floor(line.oz + z[g] + reach).toInt())
            }
        }
        for (l in 0 until splatCount) {
            val line = splatLines[l]
            if (line.kind != kind || line.frame != frame) continue
            any = true
            for (i in 0 until line.n) {
                val g = line.first + i
                val reach = sr[g] + 1.0
                out[0] = min(out[0], floor(line.ox + sx[g] - reach).toInt()); out[3] = maxOf(out[3], floor(line.ox + sx[g] + reach).toInt())
                out[1] = min(out[1], floor(line.oy + sy[g] - reach).toInt()); out[4] = maxOf(out[4], floor(line.oy + sy[g] + reach).toInt())
                out[2] = min(out[2], floor(line.oz + sz[g] - reach).toInt()); out[5] = maxOf(out[5], floor(line.oz + sz[g] + reach).toInt())
            }
        }
        return any
    }

    /** Calls [action] once for each ship frame that holds a line of [kind]; frames may repeat. */
    inline fun forEachShipFrame(kind: GasKind, action: (Long) -> Unit) {
        for (l in 0 until count) {
            val line = lines[l]
            if (line.kind == kind && line.frame != 0L) action(line.frame)
        }
        for (l in 0 until splatCount) {
            val line = splatLines[l]
            if (line.kind == kind && line.frame != 0L) action(line.frame)
        }
    }
}
