package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.spell.BeamGeometry
import org.shipwrights.enderkinesis.util.LoadedRay

/** One straight-line piece of a Sundering beam. The beam is a polyline of
 *  these segments — first segment from staff tip to the first shield hit
 *  (or to the block / range end if no shield), each subsequent segment
 *  starting at the previous segment's shield reflection point. */
data class BeamSegment(
    val start: Vec3,
    /** Unit-length direction the segment travels. */
    val direction: Vec3,
    val end: Vec3,
    val length: Double,
)

/** Polyline trace + block hit (if any) returned by [SunderingBeamTrace.trace]. */
data class BeamTrace(
    val segments: List<BeamSegment>,
    /** Block position of the final terminating hit, or null when the
     *  beam ran out of range without landing on a block. */
    val finalBlockPos: BlockPos?,
    /** Where the beam met itself (a loop), or null. The polyline ends there and the cast
     *  holding the beam is cancelled. */
    val loopPoint: Vec3? = null,
)

/** Polyline tracer shared by [SunderingManager] (server damage / mining)
 *  and `SunderingClient` (client beam render). Both sides feed it the
 *  same player + view ray + shield list so the visual beam and the
 *  physical effect agree segment-for-segment.
 *
 *  Mirror reflection off Aegis shields uses `D − 2(D·N)N` with [N] being
 *  the outward normal returned by [AegisBox.rayIntersect]. Total path
 *  length across all segments stays ≤ `maxRange`.
 *
 *  A beam that meets itself is a loop and ends there ([BeamTrace.loopPoint]):
 *  a reflected segment sent straight back along the one before it (within
 *  [BeamGeometry.HEAD_ON_DEGREES] of opposite), or any segment passing within
 *  the beam's width of an earlier one. Two facing shields therefore loop the
 *  beam; [MAX_REFLECTIONS] still caps a path that never crosses itself. */
object SunderingBeamTrace {

    /** Max bounces — including the initial straight segment, the trace
     *  can have up to `MAX_REFLECTIONS + 1` segments. */
    const val MAX_REFLECTIONS: Int = 4

    /** Tiny forward nudge applied to the reflection origin so the next
     *  iteration's ray-OBB test doesn't immediately re-hit the same
     *  shield at distance 0. */
    private const val REFLECTION_EPSILON: Double = 0.001

    /** Frames for every player on `level` who is currently wielding the
     *  Staff of Aegis, except the beam shooter (`exclude`). A player can
     *  only `isUsingItem` one item at a time so they can't simultaneously
     *  fire Sundering and project an Aegis box, but the explicit skip
     *  keeps the contract obvious. */
    fun collectWieldedShields(level: Level, exclude: net.minecraft.world.entity.Entity?): List<AegisBox.Frame> {
        val out = ArrayList<AegisBox.Frame>()
        for (p in level.players()) {
            if (p === exclude) continue
            if (!p.isUsingItem) continue
            if (p.useItem.item != EKItems.STAFF_OF_AEGIS.get()) continue
            out.add(AegisBox.forPlayer(p, 1f))
        }
        return out
    }

    /** Trace the beam from [origin] along [dir] for at most [maxRange]
     *  blocks total path length, bouncing off any frame in [shields].
     *  Returns the segment list plus the block hit at the polyline's end
     *  (null if the beam terminated without hitting a block). */
    fun trace(
        level: Level, player: net.minecraft.world.entity.Entity?,
        origin: Vec3, dir: Vec3, maxRange: Double,
        shields: List<AegisBox.Frame>,
        halfWidth: Double = DEFAULT_HALF_WIDTH,
    ): BeamTrace {
        val traced = traceStraight(level, player, origin, dir, maxRange, shields)
        return truncateAtLoop(traced, halfWidth)
    }

    /** Beam half-width for self-crossing, matching the beams' entity-hit radius. */
    const val DEFAULT_HALF_WIDTH: Double = 0.5

    /** Ends [traced] at the first place it meets itself. */
    fun truncateAtLoop(traced: BeamTrace, halfWidth: Double): BeamTrace {
        val segments = traced.segments
        for (k in 1 until segments.size) {
            val seg = segments[k]
            val prev = segments[k - 1]
            if (BeamGeometry.headOn(seg.direction, prev.direction)) {
                return BeamTrace(segments.subList(0, k), null, seg.start)
            }
            for (j in 0 until k - 1) {
                val other = segments[j]
                val c = BeamGeometry.closest(seg.start, seg.end, other.start, other.end)
                if (c.distance >= 2.0 * halfWidth) continue
                val cut = BeamSegment(seg.start, seg.direction, c.a, seg.length * c.s)
                return BeamTrace(segments.subList(0, k) + cut, null, c.midpoint)
            }
        }
        return traced
    }

    private fun traceStraight(
        level: Level, player: net.minecraft.world.entity.Entity?,
        origin: Vec3, dir: Vec3, maxRange: Double,
        shields: List<AegisBox.Frame>,
    ): BeamTrace {
        val segments = ArrayList<BeamSegment>()
        var curOrigin = origin
        var curDir = dir.normalize()
        var remaining = maxRange

        for (bounce in 0..MAX_REFLECTIONS) {
            if (remaining <= 0.0) break
            // A server `clip` loads or generates each chunk on its line. The range has no cap
            // (Infinity), so the beam ends at the edge of the loaded area.
            if (level is ServerLevel) remaining = LoadedRay.range(level, curOrigin, curDir, remaining)
            if (remaining <= 0.0) break
            val rangeEnd = curOrigin.add(curDir.scale(remaining))

            // Closest shield hit along this segment, within remaining range.
            var shieldDist = Double.MAX_VALUE
            var shieldNormal: Vec3? = null
            for (frame in shields) {
                val hit = AegisBox.rayIntersect(curOrigin, curDir, frame) ?: continue
                if (hit.distance >= shieldDist) continue
                if (hit.distance > remaining) continue
                shieldDist = hit.distance
                shieldNormal = hit.normal
            }

            // Block raycast on the same segment.
            val ctx = ClipContext(curOrigin, rangeEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
            val blockHit = level.clip(ctx)
            val blockDist = if (blockHit.type == HitResult.Type.BLOCK) {
                curOrigin.distanceTo(blockHit.location)
            } else Double.MAX_VALUE

            if (blockDist < shieldDist) {
                // Block hit — terminate the polyline here.
                val hitPoint = if (blockHit.type == HitResult.Type.BLOCK) blockHit.location else rangeEnd
                val len = curOrigin.distanceTo(hitPoint)
                segments.add(BeamSegment(curOrigin, curDir, hitPoint, len))
                return BeamTrace(segments, if (blockHit.type == HitResult.Type.BLOCK) blockHit.blockPos else null)
            }

            if (shieldNormal != null) {
                // Reflect off shield.
                val hitPoint = curOrigin.add(curDir.scale(shieldDist))
                segments.add(BeamSegment(curOrigin, curDir, hitPoint, shieldDist))
                val newDir = curDir.subtract(shieldNormal.scale(2.0 * curDir.dot(shieldNormal))).normalize()
                curOrigin = hitPoint.add(newDir.scale(REFLECTION_EPSILON))
                curDir = newDir
                remaining -= shieldDist
            } else {
                // No shield, no block — beam reaches end of range cleanly.
                segments.add(BeamSegment(curOrigin, curDir, rangeEnd, remaining))
                return BeamTrace(segments, null)
            }
        }
        return BeamTrace(segments, null)
    }
}
