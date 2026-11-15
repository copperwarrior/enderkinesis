package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc

/** Writes the world point of the Ulder Beam Anchor at [pos] into [out]. False when no anchor is there. */
fun interface BeamAnchorPoints {
    fun anchorPoint(level: Level, pos: BlockPos, out: Vector3d): Boolean
}

/**
 * The path of an orb beam. With anchors it is straight lines from SEND through the anchors of both orbs, in order
 * of their projection on the chord, to RECV. Thus neither the anchor order nor which orb sends changes the path.
 * With no anchors it is the sagging quadratic Bezier that the client draws. Not thread-safe: keep one instance
 * for each thread.
 */
class AnchoredBeamPath {

    private val anchors = Array(CAPACITY) { Vector3d() }
    private val positions = arrayOfNulls<BlockPos>(CAPACITY)
    private val keys = DoubleArray(CAPACITY)
    private val send = Vector3d()
    private val recv = Vector3d()
    private val sagControl = Vector3d()
    private val sagLengths = DoubleArray(SAG_SAMPLES + 1)
    private val sagPrev = Vector3d()
    private val sagNext = Vector3d()

    var anchorCount: Int = 0
        private set

    fun send(): Vector3dc = send
    fun recv(): Vector3dc = recv

    fun anchor(i: Int): Vector3dc {
        require(i in 0 until anchorCount) { "anchor index $i outside 0..<$anchorCount" }
        return anchors[i]
    }

    fun anchorPos(i: Int): BlockPos {
        require(i in 0 until anchorCount) { "anchor index $i outside 0..<$anchorCount" }
        return checkNotNull(positions[i])
    }

    /** SEND, then the anchors or the sag samples, then RECV. Consecutive corners bound straight segments. */
    val cornerCount: Int
        get() = if (anchorCount == 0) SAG_SAMPLES + 1 else anchorCount + 2

    fun corner(i: Int, out: Vector3d): Vector3d {
        require(i in 0 until cornerCount) { "corner index $i outside 0..<$cornerCount" }
        return when {
            i == 0 -> out.set(send)
            i == cornerCount - 1 -> out.set(recv)
            anchorCount == 0 -> sagCurve(i.toDouble() / SAG_SAMPLES, out)
            else -> out.set(anchors[i - 1])
        }
    }

    /** The anchor block at corner [i], or null for SEND, RECV and sag samples. */
    fun cornerPos(i: Int): BlockPos? =
        if (anchorCount == 0 || i <= 0 || i > anchorCount) null else positions[i - 1]

    fun build(
        level: Level, s: Vec3, r: Vec3,
        sendOrb: OrbOfLinkingBlockEntity?, recvOrb: OrbOfLinkingBlockEntity?, points: BeamAnchorPoints,
    ) {
        send.set(s.x, s.y, s.z)
        recv.set(r.x, r.y, r.z)
        collectAndSort(level, sendOrb, recvOrb, points)
    }

    fun build(
        level: Level, s: Vector3dc, r: Vector3dc,
        sendOrb: OrbOfLinkingBlockEntity?, recvOrb: OrbOfLinkingBlockEntity?, points: BeamAnchorPoints,
    ) {
        send.set(s)
        recv.set(r)
        collectAndSort(level, sendOrb, recvOrb, points)
    }

    fun length(): Double {
        if (anchorCount == 0) return sagLengths[SAG_SAMPLES]
        var len = send.distance(anchors[0]) + anchors[anchorCount - 1].distance(recv)
        for (i in 0 until anchorCount - 1) len += anchors[i].distance(anchors[i + 1])
        return len
    }

    /** Writes the point at fraction [t] of the path length into [out] and returns [out]. */
    fun pointAt(t: Double, out: Vector3d): Vector3d {
        val target = t.coerceIn(0.0, 1.0) * length()
        if (anchorCount == 0) return sagPointAt(target, out)
        var walked = 0.0
        var from: Vector3dc = send
        for (i in 0 until anchorCount) {
            val to = anchors[i]
            val seg = from.distance(to)
            if (walked + seg >= target) return lerpOnSegment(from, to, target - walked, seg, out)
            walked += seg
            from = to
        }
        return lerpOnSegment(from, recv, target - walked, from.distance(recv), out)
    }

    private fun sagPointAt(target: Double, out: Vector3d): Vector3d {
        var k = 0
        while (k < SAG_SAMPLES - 1 && sagLengths[k + 1] < target) k++
        val span = sagLengths[k + 1] - sagLengths[k]
        val f = if (span > MIN_SEGMENT) ((target - sagLengths[k]) / span).coerceIn(0.0, 1.0) else 1.0
        return sagCurve((k + f) / SAG_SAMPLES, out)
    }

    private fun sagCurve(u: Double, out: Vector3d): Vector3d {
        val a = (1.0 - u) * (1.0 - u)
        val b = 2.0 * (1.0 - u) * u
        val c = u * u
        return out.set(
            a * send.x + b * sagControl.x + c * recv.x,
            a * send.y + b * sagControl.y + c * recv.y,
            a * send.z + b * sagControl.z + c * recv.z,
        )
    }

    /** Arc length at each of [SAG_SAMPLES] equal steps of the curve parameter. */
    private fun measureSag() {
        sagControl.set((send.x + recv.x) * 0.5, (send.y + recv.y) * 0.5 - BEAM_SAG, (send.z + recv.z) * 0.5)
        sagLengths[0] = 0.0
        sagPrev.set(send)
        for (k in 1..SAG_SAMPLES) {
            sagCurve(k.toDouble() / SAG_SAMPLES, sagNext)
            sagLengths[k] = sagLengths[k - 1] + sagPrev.distance(sagNext)
            sagPrev.set(sagNext)
        }
    }

    private fun lerpOnSegment(from: Vector3dc, to: Vector3dc, along: Double, seg: Double, out: Vector3d): Vector3d {
        val f = if (seg > MIN_SEGMENT) (along / seg).coerceIn(0.0, 1.0) else 1.0
        return from.lerp(to, f, out)
    }

    private fun collectAndSort(
        level: Level, sendOrb: OrbOfLinkingBlockEntity?, recvOrb: OrbOfLinkingBlockEntity?, points: BeamAnchorPoints,
    ) {
        anchorCount = 0
        if (send.distanceSquared(recv) >= MIN_CHORD_SQR) {
            if (sendOrb != null) collect(level, sendOrb, points)
            if (recvOrb != null) collect(level, recvOrb, points)
            sortAlongChord()
        }
        if (anchorCount == 0) measureSag()
    }

    private fun collect(level: Level, orb: OrbOfLinkingBlockEntity, points: BeamAnchorPoints) {
        val list = orb.beamAnchors()
        var added = 0
        for (i in list.indices) {
            if (added >= OrbOfLinkingBlockEntity.MAX_ANCHORS || anchorCount >= CAPACITY) return
            val pos = list[i]
            // An anchor that both orbs of the link bind is in both lists.
            if (isCollected(pos)) continue
            if (!points.anchorPoint(level, pos, anchors[anchorCount])) continue
            positions[anchorCount] = pos
            anchorCount++
            added++
        }
    }

    private fun isCollected(pos: BlockPos): Boolean {
        for (i in 0 until anchorCount) if (positions[i] == pos) return true
        return false
    }

    /** Insertion sort; at most [CAPACITY] points. */
    private fun sortAlongChord() {
        val cx = recv.x - send.x
        val cy = recv.y - send.y
        val cz = recv.z - send.z
        for (i in 0 until anchorCount) {
            val a = anchors[i]
            keys[i] = (a.x - send.x) * cx + (a.y - send.y) * cy + (a.z - send.z) * cz
        }
        for (i in 1 until anchorCount) {
            val point = anchors[i]
            val pos = positions[i]
            val key = keys[i]
            var j = i - 1
            while (j >= 0 && keys[j] > key) {
                anchors[j + 1] = anchors[j]
                positions[j + 1] = positions[j]
                keys[j + 1] = keys[j]
                j--
            }
            anchors[j + 1] = point
            positions[j + 1] = pos
            keys[j + 1] = key
        }
    }

    companion object {
        /** Drop of the Bezier control point below the chord midpoint. */
        const val BEAM_SAG: Double = 0.25

        private const val CAPACITY: Int = 2 * OrbOfLinkingBlockEntity.MAX_ANCHORS
        private const val SAG_SAMPLES: Int = 16
        private const val MIN_CHORD_SQR: Double = 1.0e-6
        private const val MIN_SEGMENT: Double = 1.0e-9
    }
}
