package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BWaypoint
import org.shipwrights.enderkinesis.block.YgannBarnacleBlock
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity

/**
 * Ygann Barnacle live renderer: a staged, pulsing bulb plus tentacles that creep over
 * the hull. Paths are a pure function of (seed, hull geometry) from
 * [org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry], cached and periodically
 * re-walked so hull edits reshape the colony; a [MorphPath] slides limbs into new routes.
 * All box/tube drawing lives in [YgannBarnacleColonyDraw] (shared with the death render).
 * The shrivel-out DEATH is a detached effect ([YgannBarnacleDeathRenderer]) — the block
 * breaks normally, so nothing here fights the client's break prediction.
 */
class YgannBarnacleRenderer : BlockEntityRenderer<YgannBarnacleBlockEntity> {

    private class Waypoint(val pos: Vector3d, val normal: Vector3d, val arcPx: Double)

    private class TentaclePath(val points: List<Waypoint>, val phase: Double, val startArc: Double, val originArc: Double) {
        val totalPx: Double get() = points.lastOrNull()?.arcPx ?: 0.0
    }

    // Tentacles reach past the pip — without this, frustum culling of the 1-block BE
    // box clips them at screen edges.
    override fun shouldRenderOffScreen(be: YgannBarnacleBlockEntity): Boolean = true

    override fun render(
        be: YgannBarnacleBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level ?: return
        val state = be.blockState
        if (state.block !is YgannBarnacleBlock) return
        val facing = state.getValue(YgannBarnacleBlock.FACING)
        val consumer = buffers.getBuffer(YgannBarnacleColonyDraw.RENDER_TYPE)
        val time = level.gameTime + partialTick.toDouble()
        val now = level.gameTime

        val n = Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
        val planeOrigin = Vector3d(0.5, 0.5, 0.5).sub(Vector3d(n).mul(0.5))
        val phase = (be.seed and 0xFFFF).toDouble() * 0.01

        val pulse = 1.0 + 0.08 * Math.sin(time * 0.09 + phase) +
            0.04 * Math.sin(time * 0.31 + phase * 1.7)
        val twitchA = Math.sin(time * 0.23 + phase) * Math.sin(time * 0.071 + phase * 2.3)
        val twitch = if (twitchA > 0.82) (twitchA - 0.82) * 0.05 else 0.0
        val sizeLevel = be.nodeSizeLevel(now, partialTick)
        YgannBarnacleColonyDraw.drawBody(consumer, poseStack, packedLight, planeOrigin, n, facing, sizeLevel, pulse, twitch)

        val growth = be.growthPx(now, partialTick)
        if (growth <= 1.0) return

        @Suppress("UNCHECKED_CAST")
        var target = be.clientPaths as? List<TentaclePath>
        if (target == null) {
            target = walkPaths(be, level, facing)
            storeWalk(be, level, target, now)
        }
        @Suppress("UNCHECKED_CAST")
        var display = be.clientMorph as? MutableList<MorphPath>

        // On a slow tick, walk again when a tendril lost its support (block broken under it),
        // or when the paths are as old as the server's own walk cache. The limbs whose route
        // changed slither into the new route; the rest of the colony stays put.
        if (now - be.clientPathsBuiltAt > VALIDATE_TICKS) {
            be.clientPathsBuiltAt = now
            val refreshAge = YgannBarnacleBlockEntity.SERVER_WALK_REFRESH_TICKS +
                (be.blockPos.asLong() and YgannBarnacleBlockEntity.SERVER_WALK_STAGGER_MASK)
            val stale = now - be.clientPathsWalkedAt >= refreshAge
            if (stale || unsupportedCount(level, base(be), target) > be.clientUnsupportedBaseline) {
                val newTarget = walkPaths(be, level, facing)
                if (display != null && display.size == newTarget.size) {
                    for (i in newTarget.indices) {
                        if (!sameRoute(target[i], newTarget[i])) display[i].retarget(newTarget[i], time)
                    }
                } else {
                    display = null
                }
                target = newTarget
                storeWalk(be, level, newTarget, now)
            }
        }

        if (display == null || display.size != target.size) {
            display = target.mapTo(ArrayList()) { MorphPath(it.phase, it.startArc, it.originArc, MORPH_SAMPLES).apply { snapTo(it) } }
                as MutableList<MorphPath>
            be.clientMorph = display as MutableList<Any>
        }

        for (mp in display) {
            mp.update(time)
            val reveal = growth - mp.startArc
            if (reveal > 0.5) {
                YgannBarnacleColonyDraw.drawTentacle(
                    consumer, poseStack, packedLight, mp.currentPoints(), mp.phase, mp.originArc, reveal, time, -1.0,
                )
            }
        }
        // Pulse SECOND pass — all base geometry is emitted above; drawing the additive
        // band now (then endBatch) keeps the cutout batch uninterrupted.
        val pulseConsumer = buffers.getBuffer(YgannBarnacleColonyDraw.PULSE_TYPE)
        for (mp in display) {
            val reveal = growth - mp.startArc
            if (reveal > 0.5) {
                YgannBarnacleColonyDraw.drawTentaclePulse(
                    pulseConsumer, poseStack, mp.currentPoints(), mp.phase, mp.originArc, reveal, time, -1.0,
                )
            }
        }
        (buffers as? MultiBufferSource.BufferSource)?.endBatch(YgannBarnacleColonyDraw.PULSE_TYPE)
    }

    private fun base(be: YgannBarnacleBlockEntity): Vector3d =
        Vector3d(be.blockPos.x.toDouble(), be.blockPos.y.toDouble(), be.blockPos.z.toDouble())

    private fun storeWalk(be: YgannBarnacleBlockEntity, level: Level, paths: List<TentaclePath>, now: Long) {
        be.clientPaths = paths
        be.clientPathsBuiltAt = now
        be.clientPathsWalkedAt = now
        be.clientUnsupportedBaseline = unsupportedCount(level, base(be), paths)
    }

    /** The number of waypoints with no hull block below them. */
    private fun unsupportedCount(level: Level, base: Vector3d, paths: List<TentaclePath>): Int {
        val probe = BlockPos.MutableBlockPos()
        var count = 0
        for (path in paths) {
            for (wp in path.points) {
                val sx = base.x + wp.pos.x - wp.normal.x * SUPPORT_PROBE
                val sy = base.y + wp.pos.y - wp.normal.y * SUPPORT_PROBE
                val sz = base.z + wp.pos.z - wp.normal.z * SUPPORT_PROBE
                probe.set(Math.floor(sx).toInt(), Math.floor(sy).toInt(), Math.floor(sz).toInt())
                if (level.getBlockState(probe).getCollisionShape(level, probe).isEmpty) count++
            }
        }
        return count
    }

    // The walk is deterministic, so an unchanged hull gives equal coordinates.
    private fun sameRoute(a: TentaclePath, b: TentaclePath): Boolean {
        if (a.startArc != b.startArc || a.originArc != b.originArc || a.points.size != b.points.size) return false
        for (i in a.points.indices) if (a.points[i].pos != b.points[i].pos) return false
        return true
    }

    private fun walkPaths(be: YgannBarnacleBlockEntity, level: Level, facing: Direction): List<TentaclePath> =
        org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
            .walk(level, be.blockPos, be.seed, facing)
            .map { bp -> TentaclePath(bp.points.map { Waypoint(it.pos, it.normal, it.arcPx) }, bp.phase, bp.startArc, bp.originArc) }

    /** Fixed-resolution morphing polyline: on a re-walk it snapshots its current shape
     *  and eases to the new target over [MORPH_DURATION_TICKS] of game time, so tendrils
     *  SLIDE into new positions. Sampled at arc FRACTIONS so source/target correspond. */
    private class MorphPath(var phase: Double, var startArc: Double, var originArc: Double, n: Int) {
        private val srcPos = Array(n) { Vector3d() }
        private val srcNrm = Array(n) { Vector3d(0.0, 1.0, 0.0) }
        private val tgtPos = Array(n) { Vector3d() }
        private val tgtNrm = Array(n) { Vector3d(0.0, 1.0, 0.0) }
        private val curPos = Array(n) { Vector3d() }
        private val curNrm = Array(n) { Vector3d(0.0, 1.0, 0.0) }
        private var srcTotal = 0.0
        private var tgtTotal = 0.0
        private var curTotal = 0.0
        private var morphStart = Double.NEGATIVE_INFINITY
        // One list for the life of the path: it shares the `cur` vectors, and [update] sets the arcs.
        private val points: List<BWaypoint> = List(n) { BWaypoint(curPos[it], curNrm[it], 0.0) }

        fun snapTo(tp: TentaclePath) {
            resample(tp, tgtPos, tgtNrm)
            tgtTotal = tp.totalPx
            for (i in tgtPos.indices) {
                srcPos[i].set(tgtPos[i]); srcNrm[i].set(tgtNrm[i])
                curPos[i].set(tgtPos[i]); curNrm[i].set(tgtNrm[i])
            }
            srcTotal = tgtTotal; curTotal = tgtTotal
            morphStart = Double.NEGATIVE_INFINITY
        }

        fun retarget(tp: TentaclePath, timeNow: Double) {
            // A walk on a changed hull can give this index a different tendril.
            phase = tp.phase
            originArc = tp.originArc
            startArc = tp.startArc
            for (i in curPos.indices) { srcPos[i].set(curPos[i]); srcNrm[i].set(curNrm[i]) }
            srcTotal = curTotal
            resample(tp, tgtPos, tgtNrm)
            tgtTotal = tp.totalPx
            morphStart = timeNow
        }

        fun update(timeNow: Double) {
            val p = if (morphStart == Double.NEGATIVE_INFINITY) 1.0
                else YgannBarnacleColonyDraw.smooth(((timeNow - morphStart) / MORPH_DURATION_TICKS).coerceIn(0.0, 1.0))
            for (i in curPos.indices) {
                curPos[i].set(srcPos[i]).lerp(tgtPos[i], p)
                curNrm[i].set(srcNrm[i]).lerp(tgtNrm[i], p)
                if (curNrm[i].lengthSquared() > 1.0e-9) curNrm[i].normalize()
            }
            curTotal = srcTotal + (tgtTotal - srcTotal) * p
            val last = curPos.size - 1
            for (i in points.indices) points[i].arcPx = if (last == 0) 0.0 else i.toDouble() / last * curTotal
        }

        /** Current (eased) centerline as shared waypoints for [YgannBarnacleColonyDraw]. Valid after [update]. */
        fun currentPoints(): List<BWaypoint> = points

        companion object {
            private fun resample(tp: TentaclePath, outP: Array<Vector3d>, outN: Array<Vector3d>) {
                val n = outP.size
                for (i in 0 until n) {
                    val frac = if (n == 1) 0.0 else i.toDouble() / (n - 1)
                    val s = sampleFrac(tp.points, frac * tp.totalPx)
                    outP[i].set(s[0]); outN[i].set(s[1])
                }
            }
            private fun sampleFrac(pts: List<Waypoint>, arc: Double): Array<Vector3d> {
                if (arc <= 0.0) return arrayOf(Vector3d(pts[0].pos), Vector3d(pts[0].normal))
                for (i in 1 until pts.size) {
                    val b = pts[i]
                    if (b.arcPx >= arc) {
                        val a = pts[i - 1]
                        val f = ((arc - a.arcPx) / (b.arcPx - a.arcPx)).coerceIn(0.0, 1.0)
                        return arrayOf(Vector3d(a.pos).lerp(b.pos, f), Vector3d(a.normal).lerp(b.normal, f).normalize())
                    }
                }
                val l = pts.size - 1
                return arrayOf(Vector3d(pts[l].pos), Vector3d(pts[l].normal))
            }
        }
    }

    companion object {
        private const val VALIDATE_TICKS = 20L
        private const val SUPPORT_PROBE = 0.2
        private const val MORPH_SAMPLES = 40
        private const val MORPH_DURATION_TICKS = 45.0
    }
}
