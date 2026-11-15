package org.shipwrights.enderkinesis.client

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.AnchoredBeamPath
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity

/** One quadratic Bezier part of a beam. [spanStart]..[spanEnd] is its part of the whole beam, 0 at SEND and 1 at RECV. */
internal class BeamPiece(val start: Vec3, val control: Vec3, val end: Vec3, val spanStart: Double, val spanEnd: Double)

/** The path of a beam that Ulder Beam Anchors bend. Client thread only. */
internal object OrbBeamRoute {

    private val path = AnchoredBeamPath()
    private val sendScratch = Vector3d()
    private val recvScratch = Vector3d()

    /**
     * Fills [pieces] and, if given, [polyline] (the corners, SEND first and RECV last). With no anchors this is
     * the usual single sagging piece. With anchors it is straight lines through the anchors of both orbs.
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
        path.build(level, s, r, orbAt(level, sendPos), orbAt(level, recvPos), OrbClientGeometry.RenderAnchorPoints)
        if (path.anchorCount == 0) {
            val sag = Vec3((s.x + r.x) * 0.5, (s.y + r.y) * 0.5 - OrbOfLinkingClient.BEAM_SAG, (s.z + r.z) * 0.5)
            pieces.add(BeamPiece(s, sag, r, 0.0, 1.0))
            polyline?.add(s)
            polyline?.add(r)
            return
        }

        val pathLen = path.length()
        var from = s
        var walked = 0.0
        for (i in 0..path.anchorCount) {
            val to = if (i < path.anchorCount) path.anchor(i).let { Vec3(it.x(), it.y(), it.z()) } else r
            val segLen = from.distanceTo(to)
            val mid = Vec3((from.x + to.x) * 0.5, (from.y + to.y) * 0.5, (from.z + to.z) * 0.5)
            pieces.add(BeamPiece(from, mid, to, walked / pathLen, (walked + segLen) / pathLen))
            polyline?.add(from)
            walked += segLen
            from = to
        }
        polyline?.add(r)
    }

    /** Writes the point at fraction [t] of the drawn beam path from SEND to RECV into [out]. False when an orb is gone. */
    fun pointAt(level: ClientLevel, sendPos: BlockPos, recvPos: BlockPos, t: Double, out: Vector3d): Boolean {
        val s = OrbClientGeometry.worldCenter(level, sendPos, sendScratch) ?: return false
        val r = OrbClientGeometry.worldCenter(level, recvPos, recvScratch) ?: return false
        path.build(level, s, r, orbAt(level, sendPos), orbAt(level, recvPos), OrbClientGeometry.RenderAnchorPoints)
        path.pointAt(t, out)
        return true
    }

    private fun orbAt(level: ClientLevel, pos: BlockPos): OrbOfLinkingBlockEntity? =
        level.getBlockEntity(pos) as? OrbOfLinkingBlockEntity
}
