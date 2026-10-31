package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity

/** One quadratic Bezier part of a beam. [spanStart]..[spanEnd] is its part of the whole beam, 0 at SEND and 1 at RECV. */
internal class BeamPiece(val start: Vec3, val control: Vec3, val end: Vec3, val spanStart: Double, val spanEnd: Double)

/** The path of a beam that Mystic Beam Anchors bend. Client thread only. */
internal object OrbBeamRoute {

    private val anchors = ArrayList<Vec3>(2 * OrbOfLinkingBlockEntity.MAX_ANCHORS)
    private val anchorPositions = ArrayList<BlockPos>(2 * OrbOfLinkingBlockEntity.MAX_ANCHORS)
    private val keys = DoubleArray(2 * OrbOfLinkingBlockEntity.MAX_ANCHORS)

    /**
     * Fills [pieces] and, if given, [polyline] (the corners, SEND first and RECV last). With no anchors this is
     * the usual single sagging piece. With anchors it is straight lines through the anchors of both orbs, in order
     * of their projection on the chord, so neither anchor order nor which orb sends changes the path.
     */
    fun build(
        level: ClientLevel,
        sendPos: BlockPos, sendCentre: Vec3,
        recvPos: BlockPos, recvCentre: Vec3,
        pieces: MutableList<BeamPiece>,
        polyline: MutableList<Vec3>?,
    ) {
        pieces.clear()
        polyline?.clear()
        val s = sendCentre
        val r = recvCentre
        anchors.clear()
        anchorPositions.clear()
        collectAnchors(level, sendPos)
        collectAnchors(level, recvPos)
        val chord = r.subtract(s)
        val chordSqr = chord.lengthSqr()
        if (anchors.isEmpty() || chordSqr < 1.0e-6) {
            val sag = Vec3((s.x + r.x) * 0.5, (s.y + r.y) * 0.5 - OrbOfLinkingClient.BEAM_SAG, (s.z + r.z) * 0.5)
            pieces.add(BeamPiece(s, sag, r, 0.0, 1.0))
            polyline?.add(s)
            polyline?.add(r)
            return
        }
        sortAlongChord(s, chord)

        var pathLen = s.distanceTo(anchors[0]) + anchors[anchors.size - 1].distanceTo(r)
        for (i in 0 until anchors.size - 1) pathLen += anchors[i].distanceTo(anchors[i + 1])
        var from = s
        var walked = 0.0
        for (i in 0..anchors.size) {
            val to = if (i < anchors.size) anchors[i] else r
            val segLen = from.distanceTo(to)
            val mid = Vec3((from.x + to.x) * 0.5, (from.y + to.y) * 0.5, (from.z + to.z) * 0.5)
            pieces.add(BeamPiece(from, mid, to, walked / pathLen, (walked + segLen) / pathLen))
            polyline?.add(from)
            walked += segLen
            from = to
        }
        polyline?.add(r)
    }

    private fun collectAnchors(level: ClientLevel, orbPos: BlockPos) {
        val orb = level.getBlockEntity(orbPos) as? OrbOfLinkingBlockEntity ?: return
        var added = 0
        for (pos in orb.beamAnchors()) {
            if (added >= OrbOfLinkingBlockEntity.MAX_ANCHORS) break
            // An anchor that both orbs of the link bind is in both lists.
            if (pos in anchorPositions) continue
            val point = OrbClientGeometry.anchorPoint(level, pos) ?: continue
            anchors.add(point)
            anchorPositions.add(pos)
            added++
        }
    }

    /** Insertion sort; at most 2 × [OrbOfLinkingBlockEntity.MAX_ANCHORS] points. */
    private fun sortAlongChord(s: Vec3, chord: Vec3) {
        for (i in anchors.indices) keys[i] = anchors[i].subtract(s).dot(chord)
        for (i in 1 until anchors.size) {
            val point = anchors[i]
            val key = keys[i]
            var j = i - 1
            while (j >= 0 && keys[j] > key) {
                anchors[j + 1] = anchors[j]
                keys[j + 1] = keys[j]
                j--
            }
            anchors[j + 1] = point
            keys[j + 1] = key
        }
    }
}
