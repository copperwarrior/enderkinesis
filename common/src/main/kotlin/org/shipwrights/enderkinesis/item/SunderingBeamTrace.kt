package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKItems

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
)

/** Polyline tracer shared by [SunderingManager] (server damage / mining)
 *  and `SunderingClient` (client beam render). Both sides feed it the
 *  same player + view ray + shield list so the visual beam and the
 *  physical effect agree segment-for-segment.
 *
 *  Mirror reflection off Aegis shields uses `D − 2(D·N)N` with [N] being
 *  the outward normal returned by [AegisBox.rayIntersect]. Capped at
 *  [MAX_REFLECTIONS] bounces so two facing shields don't lock the beam
 *  into infinite recursion. Total path length across all segments stays
 *  ≤ `maxRange`. */
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
    fun collectWieldedShields(level: Level, exclude: Player): List<AegisBox.Frame> {
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
        level: Level, player: Player,
        origin: Vec3, dir: Vec3, maxRange: Double,
        shields: List<AegisBox.Frame>,
    ): BeamTrace {
        val segments = ArrayList<BeamSegment>()
        var curOrigin = origin
        var curDir = dir.normalize()
        var remaining = maxRange

        for (bounce in 0..MAX_REFLECTIONS) {
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
