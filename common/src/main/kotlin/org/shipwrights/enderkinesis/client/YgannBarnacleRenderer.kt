package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BPath
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BWaypoint
import org.shipwrights.enderkinesis.block.YgannBarnacleBlock
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Ygann Barnacle live renderer: a staged, pulsing bulb plus tentacles that creep over
 * the hull. Paths are a pure function of (seed, hull geometry) from
 * [YgannBarnacleGeometry], cached and re-walked when a hull cell that the walk read
 * changes; a [MorphPath] slides limbs into new routes.
 * All box/tube drawing lives in [YgannBarnacleColonyDraw] (shared with the death render).
 * The shrivel-out DEATH is a detached effect ([YgannBarnacleDeathRenderer]) — the block
 * breaks normally, so nothing here fights the client's break prediction.
 */
class YgannBarnacleRenderer : BlockEntityRenderer<YgannBarnacleBlockEntity> {

    // Render thread only.
    private val hullTest = HullTest()
    private val cirri = Array(CIRRI_MAX) { List(CIRRUS_POINTS) { BWaypoint(Vector3d(), Vector3d(0.0, 1.0, 0.0), 0.0) } }
    private val cirrusBreaks = DoubleArray(CIRRUS_POINTS)
    private val cirrusRadial = Vector3d()
    private val cirrusSide = Vector3d()
    private val cirrusDir = Vector3d()
    private val cirrusPos = Vector3d()
    private val rippleEnd = DoubleArray(YgannBarnacleGeometry.MAX_TENTACLES)
    private val flinchSway = Array(YgannBarnacleGeometry.MAX_TENTACLES) { Vector3d() }
    private val flinchScratch = Vector3d()
    private val cullCorner = Vector3d()

    // Tentacles reach past the pip, so the 1-block BE box would clip them; [isVisible] culls with the walk's bounds.
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
        val time = level.gameTime + partialTick.toDouble()
        val now = level.gameTime
        // A culled barnacle still runs its walk, morph and cut logic, so it looks the same when it comes into view.
        val visible = isVisible(be, level)
        val consumer = if (visible) buffers.getBuffer(YgannBarnacleColonyDraw.RENDER_TYPE) else null

        val frame = YgannBarnacleGeometry.frameOf(facing)
        val phase = (be.seed and 0xFFFF).toDouble() * 0.01

        val pulse = 1.0 + 0.08 * Math.sin(time * 0.09 + phase) +
            0.04 * Math.sin(time * 0.31 + phase * 1.7)
        val twitchA = Math.sin(time * 0.23 + phase) * Math.sin(time * 0.071 + phase * 2.3)
        val twitch = if (twitchA > 0.82) (twitchA - 0.82) * 0.05 else 0.0
        val sizeLevel = be.nodeSizeLevel(now, partialTick)
        if (consumer != null) {
            YgannBarnacleColonyDraw.drawBody(consumer, poseStack, packedLight, facing, sizeLevel, pulse, twitch)
            drawCirri(consumer, poseStack, packedLight, be, frame, sizeLevel, pulse, time)
        }

        val growth = be.growthPx(now, partialTick)
        if (growth <= 1.0) return
        YgannBarnacleLimbClient.noteRendered(be)

        hullTest.level = level
        hullTest.origin = be.blockPos
        adoptPendingWalk(be, now, time)
        @Suppress("UNCHECKED_CAST")
        var display = be.clientMorph as? MutableList<MorphPath>
        val current = be.clientWalk
        if (current == null) {
            YgannBarnacleClientWork.requestWalk(be)
            return
        }
        val target = current.paths

        if (!be.clientWalkQueued && now - be.clientHullCheckedAt > VALIDATE_TICKS) {
            be.clientHullCheckedAt = now
            // The current walk stays on screen until the queued one is ready.
            if (current.hull.changedSince(level, YgannBarnacleClientWork.hullLog)) YgannBarnacleClientWork.requestWalk(be)
        }

        dissolveCutParts(be, target, now, partialTick)
        val reveals = be.clientReveals
        be.limbReveals(target, now, partialTick, reveals)

        if (display == null || display.size != target.size) {
            display = target.mapTo(ArrayList()) { newMorph(it) }
            be.clientMorph = display as MutableList<Any>
        }

        for (i in display.indices) display[i].update(time)
        if (consumer != null) drawLimbs(be, target, display, reveals, consumer, buffers, poseStack, packedLight, time)
    }

    @Suppress("LongParameterList")
    private fun drawLimbs(
        be: YgannBarnacleBlockEntity, target: List<BPath>, display: List<MorphPath>, reveals: DoubleArray,
        consumer: com.mojang.blaze3d.vertex.VertexConsumer, buffers: MultiBufferSource, poseStack: PoseStack,
        packedLight: Int, time: Double,
    ) {
        val flinchAge = be.flinchAge(time)
        if (flinchAge >= 0.0) {
            if (be.flinchLimb == 0) applyColonyFlinch(target, display, flinchAge)
            else applyCutRipple(be.flinchLimb, be.flinchArc, target, display, flinchAge)
        }

        for (i in display.indices) {
            val mp = display[i]
            val reveal = reveals[i]
            if (reveal > 0.5) {
                YgannBarnacleColonyDraw.drawTentacle(
                    consumer, poseStack, packedLight, mp.currentPoints(), mp.breaks, mp.phase, mp.originArc, reveal, time, -1.0,
                )
            }
        }
        // Pulse SECOND pass — all base geometry is emitted above; drawing the additive
        // band now (then endBatch) keeps the cutout batch uninterrupted.
        val pulseConsumer = buffers.getBuffer(YgannBarnacleColonyDraw.PULSE_TYPE)
        for (i in display.indices) {
            val mp = display[i]
            val reveal = reveals[i]
            if (reveal > 0.5) {
                YgannBarnacleColonyDraw.drawTentaclePulse(
                    pulseConsumer, poseStack, mp.currentPoints(), mp.breaks, mp.phase, mp.originArc, reveal, time, -1.0,
                )
            }
        }
        (buffers as? MultiBufferSource.BufferSource)?.endBatch(YgannBarnacleColonyDraw.PULSE_TYPE)
    }

    /** Takes the walk that the queue finished. After a hull change the limbs morph from the held walk to it. */
    private fun adoptPendingWalk(be: YgannBarnacleBlockEntity, now: Long, time: Double) {
        val pending = be.clientPendingWalk ?: return
        val held = be.clientWalk
        be.clientPendingWalk = null
        be.clientWalk = pending
        be.clientHullCheckedAt = now
        // A morph runs between both routes, so the bounds keep the old ones.
        val bounds = localBounds(pending)
        be.clientBounds = if (held == null) bounds else bounds.minmax(be.clientBounds ?: localBounds(held))
        if (held == null) return
        val oldReveals = be.clientOldScratch
        val newReveals = be.clientScratch
        be.adoptWalk(held, pending, now, oldReveals, newReveals)
        dissolveLostParts(be, held.paths, pending.paths, oldReveals, newReveals, now)
        @Suppress("UNCHECKED_CAST")
        val display = rematch(be.clientMorph as? MutableList<MorphPath>, held.paths, pending.paths, time)
        @Suppress("UNCHECKED_CAST")
        be.clientMorph = display as MutableList<Any>?
        // The last frame's reveals index the old paths.
        be.clientLastBaseAt = Long.MIN_VALUE
    }

    /** False when the barnacle's drawn bounds are outside this frame's frustum. Never culls under a shader pack,
     *  whose shadow pass draws block entities that the camera frustum does not hold. */
    private fun isVisible(be: YgannBarnacleBlockEntity, level: Level): Boolean {
        val frustum = YgannBarnacleClientWork.frustum ?: return true
        if (IrisCompat.isShaderPackInUse()) return true
        val local = be.clientBounds ?: BODY_BOUNDS
        val pos = be.blockPos
        val ship = level.getShipManagingPos(pos) as? ClientShip
        if (ship == null) {
            return frustum.isVisible(local.move(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()))
        }
        // Shipyard bounds: the world box holds all eight corners after the ship's render transform.
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        val c = cullCorner
        for (corner in 0 until 8) {
            c.set(
                pos.x + if ((corner and 1) == 0) local.minX else local.maxX,
                pos.y + if ((corner and 2) == 0) local.minY else local.maxY,
                pos.z + if ((corner and 4) == 0) local.minZ else local.maxZ,
            )
            ship.renderTransform.shipToWorld.transformPosition(c)
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x)
            minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y)
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z)
        }
        return frustum.isVisible(AABB(minX, minY, minZ, maxX, maxY, maxZ))
    }

    /** A hit on the core: every limb lifts and writhes, more toward its tip, then settles back.
     *  The length does not change. */
    private fun applyColonyFlinch(paths: List<BPath>, display: List<MorphPath>, age: Double) {
        val envelope = if (age < FLINCH_RISE_TICKS) YgannBarnacleColonyDraw.smooth(age / FLINCH_RISE_TICKS)
            else YgannBarnacleColonyDraw.smooth(
                1.0 - (age - FLINCH_RISE_TICKS) / (YgannBarnacleBlockEntity.FLINCH_TICKS - FLINCH_RISE_TICKS),
            )
        if (envelope <= 0.0) return
        for (i in paths.indices) {
            val path = paths[i]
            val parent = path.parent
            val head = parent < 0
            val points = display[i].currentPoints()
            if (head) {
                // One sway direction for the whole lineage, so a branch stays joined to its parent.
                val dir = flinchSway[i].set(points[1].pos).sub(points[0].pos)
                dir.cross(points[0].normal)
                if (dir.lengthSquared() > 1.0e-12) dir.normalize()
            } else if (parent in 0 until i) {
                flinchSway[i].set(flinchSway[parent])
            }
            for (k in points.indices) {
                val wp = points[k]
                val s = path.originArc + wp.arcPx
                val ramp = YgannBarnacleColonyDraw.smooth((s / FLINCH_RAMP_PX).coerceIn(0.0, 1.0)) * envelope
                if (ramp <= 0.0) continue
                val sway = FLINCH_SWAY_PX * Math.sin(age * FLINCH_SWAY_RATE - s * FLINCH_WAVE_PER_PX)
                val side = flinchScratch.set(flinchSway[i])
                YgannBarnacleGeometry.projectToPlane(side, wp.normal)
                wp.pos.add(
                    (wp.normal.x * FLINCH_LIFT_PX + side.x * sway) * ramp / PX_PER_BLOCK,
                    (wp.normal.y * FLINCH_LIFT_PX + side.y * sway) * ramp / PX_PER_BLOCK,
                    (wp.normal.z * FLINCH_LIFT_PX + side.z * sway) * ramp / PX_PER_BLOCK,
                )
            }
        }
    }

    /** A cut: a bump runs from the cut point down the cut limb and its ancestors to the core. Limb parts
     *  that leave that route (a side branch, or a parent past the branch point) follow it at their base. */
    private fun applyCutRipple(limb: Int, cutArc: Double, paths: List<BPath>, display: List<MorphPath>, age: Double) {
        val cut = paths.indexOfFirst { it.id == limb }
        if (cut < 0) return
        // rippleEnd[i]: the arc up to which limb i is on the route to the core; NaN when it is not.
        java.util.Arrays.fill(rippleEnd, 0, paths.size, Double.NaN)
        var j = cut
        var end = cutArc
        while (j >= 0) {
            rippleEnd[j] = end
            end = paths[j].parentArc
            j = paths[j].parent
        }
        val start = paths[cut].originArc + cutArc
        val front = start - age * RIPPLE_SPEED_PX_PER_TICK
        val fadeIn = YgannBarnacleColonyDraw.smooth((age / RIPPLE_FADE_IN_TICKS).coerceIn(0.0, 1.0))
        for (i in paths.indices) {
            val path = paths[i]
            val onRoute = !rippleEnd[i].isNaN()
            val parent = path.parent
            // An off-route limb follows only where it leaves the route.
            val baseS = when {
                onRoute -> path.originArc + rippleEnd[i]
                parent in 0 until i && !rippleEnd[parent].isNaN() && path.parentArc <= rippleEnd[parent] -> path.originArc
                else -> continue
            }
            val baseEnd = if (onRoute) rippleEnd[i] else 0.0
            val points = display[i].currentPoints()
            for (k in points.indices) {
                val wp = points[k]
                val past = wp.arcPx - baseEnd
                val s = if (past > 0.0) baseS else path.originArc + wp.arcPx
                val follow = if (past > 0.0) 1.0 - YgannBarnacleColonyDraw.smooth((past / RIPPLE_ATTACH_PX).coerceIn(0.0, 1.0)) else 1.0
                val lift = rippleLiftPx(s, front) * follow * fadeIn
                if (lift <= 0.0) continue
                wp.pos.add(
                    wp.normal.x * lift / PX_PER_BLOCK,
                    wp.normal.y * lift / PX_PER_BLOCK,
                    wp.normal.z * lift / PX_PER_BLOCK,
                )
            }
        }
    }

    /** The bump (px) at lineage arc [s] when its peak is at [front]. It dies out at the core. */
    private fun rippleLiftPx(s: Double, front: Double): Double {
        val x = (s - front) / RIPPLE_WIDTH_PX
        if (x * x > RIPPLE_CUTOFF_SQ) return 0.0
        val anchor = YgannBarnacleColonyDraw.smooth((s / FLINCH_RAMP_PX).coerceIn(0.0, 1.0))
        return RIPPLE_LIFT_PX * Math.exp(-x * x) * anchor
    }

    /** After a sync with new cuts, hands the limb parts that the cut removed to the dissolve effect. */
    private fun dissolveCutParts(be: YgannBarnacleBlockEntity, paths: List<BPath>, now: Long, partialTick: Float) {
        val base = be.clientScratch
        be.limbReveals(paths, now, partialTick, base)
        val last = be.clientLastBase
        if (be.clientCutsVersion != be.clientCutsSeen) {
            be.clientCutsSeen = be.clientCutsVersion
            // A stale last frame (barnacle out of view at the cut) has nothing current to dissolve.
            if (now - be.clientLastBaseAt in 0..STALE_BASE_TICKS) {
                var any = false
                for (i in paths.indices) if (last[i] - base[i] > MIN_SEVER_PX) any = true
                if (any) YgannBarnacleDeathRenderer.addSever(be.blockPos, paths, base, last, now)
            }
        }
        System.arraycopy(base, 0, last, 0, paths.size)
        be.clientLastBaseAt = now
    }

    private fun newMorph(path: BPath): MorphPath =
        MorphPath(path.phase, path.startArc, path.originArc).apply { snapTo(path) }

    /** Morphs in the new walk's order, matched by limb id. A limb on the same route stays put, a limb
     *  that only got shorter or longer snaps (the dissolve or the creep shows the change), a re-routed
     *  limb slides, and a new limb starts fresh. */
    private fun rematch(
        display: MutableList<MorphPath>?, old: List<BPath>, new: List<BPath>, time: Double,
    ): MutableList<MorphPath>? {
        if (display == null || display.size != old.size) return null
        val out = ArrayList<MorphPath>(new.size)
        for (np in new) {
            val i = old.indexOfFirst { it.id == np.id }
            if (i < 0) { out.add(newMorph(np)); continue }
            val mp = display[i]
            val op = old[i]
            when {
                sameRoute(op, np) -> Unit
                YgannBarnacleGeometry.isPrefix(np, op) || YgannBarnacleGeometry.isPrefix(op, np) -> mp.snapTo(np)
                // A limb the new block sits on reacts at once; the others start after their own delays,
                // so the colony does not move as one.
                else -> mp.retarget(np, time, if (mp.touchesHull(hullTest)) time else time + staggerTicks(np.id))
            }
            out.add(mp)
        }
        return out
    }

    /** Dissolves the parts of old limbs that a hull change took away: a limb that is gone, or one
     *  whose new route is the start of its old route. A re-routed limb slides and does not dissolve. */
    private fun dissolveLostParts(
        be: YgannBarnacleBlockEntity, old: List<BPath>, new: List<BPath>,
        oldReveals: DoubleArray, newReveals: DoubleArray, now: Long,
    ) {
        val from = DoubleArray(old.size)
        val to = DoubleArray(old.size)
        var any = false
        for (i in old.indices) {
            val op = old[i]
            val j = new.indexOfFirst { it.id == op.id }
            if (j >= 0 && !YgannBarnacleGeometry.isPrefix(new[j], op)) continue
            val kept = if (j < 0) 0.0 else Math.max(0.0, newReveals[j])
            if (oldReveals[i] - kept > MIN_SEVER_PX) {
                from[i] = kept
                to[i] = oldReveals[i]
                any = true
            }
        }
        if (any) YgannBarnacleDeathRenderer.addSever(be.blockPos, old, from, to, now)
    }

    /** Small writhing tentacles on the rim of the bulb's top. Their count, length, and how far they bend
     *  grow with the bulb's size. Drawn only; they have no hit boxes and do no damage. */
    @Suppress("LongParameterList")
    private fun drawCirri(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer, poseStack: PoseStack, light: Int,
        be: YgannBarnacleBlockEntity, frame: YgannBarnacleGeometry.FaceFrame,
        sizeLevel: Double, pulse: Double, time: Double,
    ) {
        val count = Math.round(CIRRI_MAX * sizeLevel).toInt()
        if (count <= 0) return
        val t1 = frame.t1
        val t2 = frame.t2
        val n = frame.n
        val planeOrigin = frame.planeOrigin
        val radius = (YgannBarnacleBlockEntity.BODY_MIN_R_PX +
            (YgannBarnacleBlockEntity.BODY_MAX_R_PX - YgannBarnacleBlockEntity.BODY_MIN_R_PX) * sizeLevel) / PX_PER_BLOCK * pulse
        val height = (YgannBarnacleBlockEntity.BODY_MIN_H_PX +
            (YgannBarnacleBlockEntity.BODY_MAX_H_PX - YgannBarnacleBlockEntity.BODY_MIN_H_PX) * sizeLevel) / PX_PER_BLOCK
        val length = CIRRUS_MIN_PX + (CIRRUS_MAX_PX - CIRRUS_MIN_PX) * sizeLevel
        val step = length / (CIRRUS_POINTS - 1)
        val bend = CIRRUS_BEND_MIN + (CIRRUS_BEND_MAX - CIRRUS_BEND_MIN) * sizeLevel
        for (k in 0 until CIRRUS_POINTS) cirrusBreaks[k] = k * step
        for (c in 0 until count) {
            val salt = it.unimi.dsi.fastutil.HashCommon.mix(be.seed.toInt() xor (c * CIRRUS_SALT))
            val phase = (salt and 0xFFFF) / 65536.0 * Math.PI * 2.0
            val angle = (c + ((salt ushr 16) and 0xFF) / 256.0 * 0.5) * Math.PI * 2.0 / count
            val radial = cirrusRadial.set(t1).mul(Math.cos(angle)).fma(Math.sin(angle), t2)
            val side = cirrusSide.set(n).cross(radial).normalize()
            val dir = cirrusDir.set(n).fma(CIRRUS_LEAN, radial).normalize()
            val pos = cirrusPos.set(planeOrigin).fma(height, n).fma(radius * CIRRUS_RIM, radial)
            val pts = cirri[c]
            for (k in 0 until CIRRUS_POINTS) {
                if (k > 0) {
                    // Two waves run up the cirrus, one bending it toward the core and one sideways.
                    val a = bend * Math.sin(time * CIRRUS_RATE + phase - k * CIRRUS_WAVE)
                    val b = bend * CIRRUS_SIDE_SHARE * Math.sin(time * CIRRUS_RATE * 0.7 + phase * 1.3 - k * CIRRUS_WAVE * 0.8)
                    YgannBarnacleGeometry.rotateAround(dir, side, a / (CIRRUS_POINTS - 1))
                    YgannBarnacleGeometry.rotateAround(dir, radial, b / (CIRRUS_POINTS - 1))
                    dir.normalize()
                    pos.fma(step / PX_PER_BLOCK, dir)
                }
                val wp = pts[k]
                wp.pos.set(pos)
                wp.normal.set(dir).cross(side).normalize()
                wp.arcPx = k * step
            }
            YgannBarnacleColonyDraw.drawTentacle(
                consumer, poseStack, light, pts, cirrusBreaks, phase, CIRRUS_ORIGIN_ARC, length, time, -1.0,
            )
        }
    }

    private fun staggerTicks(id: Int): Double =
        ((it.unimi.dsi.fastutil.HashCommon.mix(id) and Int.MAX_VALUE) % (MORPH_STAGGER_TICKS + 1)).toDouble()

    /** Point test in a barnacle's local frame (relative to [origin]); render thread only. */
    internal class HullTest {
        var level: Level? = null
        var origin: net.minecraft.core.BlockPos = net.minecraft.core.BlockPos.ZERO
        private val cursor = net.minecraft.core.BlockPos.MutableBlockPos()

        fun solid(local: Vector3d): Boolean {
            val l = level ?: return false
            return YgannBarnacleGeometry.hullSolidAt(l, origin.x + local.x, origin.y + local.y, origin.z + local.z, cursor)
        }
    }

    /** Block-local bounds of [walk]'s waypoints and the barnacle's own cell, grown by what the draw adds. */
    private fun localBounds(walk: YgannBarnacleGeometry.Walk): AABB {
        var minX = 0.0; var minY = 0.0; var minZ = 0.0
        var maxX = 1.0; var maxY = 1.0; var maxZ = 1.0
        for (path in walk.paths) for (wp in path.points) {
            val p = wp.pos
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x)
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y)
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z)
        }
        return AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(DRAW_MARGIN)
    }

    // The walk is deterministic, so an unchanged hull gives equal coordinates.
    private fun sameRoute(a: BPath, b: BPath): Boolean {
        if (a.startArc != b.startArc || a.originArc != b.originArc || a.points.size != b.points.size) return false
        for (i in a.points.indices) if (a.points[i].pos != b.points[i].pos) return false
        return true
    }

    /** A limb's drawn shape, on the route's own waypoints. A move to a new route never leaves the hull: each
     *  point first eases onto the nearest spot of the new route (a buried point rises out of the new block,
     *  a far tail closes onto the route's end), then slides along the new route to its own place, slowly,
     *  in heaves, the base first. A sliding point is always on the route, so nothing swings through the air
     *  and nothing passes through a block. */
    internal class MorphPath(var phase: Double, var startArc: Double, var originArc: Double) {
        private var tgtPos = emptyArray<Vector3d>()
        private var tgtNrm = emptyArray<Vector3d>()
        private var tgtArc = DoubleArray(0)
        private var curPos = emptyArray<Vector3d>()
        private var curNrm = emptyArray<Vector3d>()
        // Each point's start on the new route (arc), its offset from there to its old place, and its old normal.
        private var startOnRoute = DoubleArray(0)
        private var offset = emptyArray<Vector3d>()
        private var srcNrm = emptyArray<Vector3d>()
        // Each point's slide speed (times the base speed) and start delay (ticks), for an uneven slide.
        private var pointSpeed = DoubleArray(0)
        private var pointDelay = DoubleArray(0)
        private var moveEnd = 0.0
        private var points: List<BWaypoint> = emptyList()
        /** The target route's segment ends; fixed through a move, so the tube does not re-segment. */
        var breaks = DoubleArray(0)
            private set
        private val routeP = Vector3d()
        private val routeN = Vector3d()
        private val scratch = Vector3d()
        /** Points up to this index are where they were; they do not move. */
        private var split = 0
        private var morphStart = Double.NEGATIVE_INFINITY
        private var moveTicks = 1.0
        private var heaves = 1.0

        fun snapTo(tp: BPath) {
            phase = tp.phase
            originArc = tp.originArc
            startArc = tp.startArc
            setTarget(tp)
            morphStart = Double.NEGATIVE_INFINITY
            update(0.0)
        }

        /** Moves to [tp], starting at [startTime] (it holds its current shape until then). */
        fun retarget(tp: BPath, timeNow: Double, startTime: Double) {
            // Start from the shape on screen now, without the flinch that the last frame added.
            update(timeNow)
            phase = tp.phase
            originArc = tp.originArc
            startArc = tp.startArc
            val oldPos = Array(curPos.size) { Vector3d(curPos[it]) }
            val oldNrm = Array(curNrm.size) { Vector3d(curNrm[it]) }
            val oldArc = DoubleArray(points.size) { points[it].arcPx }
            setTarget(tp)
            split = -1
            for (i in tgtPos.indices) {
                val old = sampleOn(oldPos, oldNrm, oldArc, tgtArc[i], scratch, srcNrm[i])
                startOnRoute[i] = nearestOnTarget(old)
                sampleOn(tgtPos, tgtNrm, tgtArc, startOnRoute[i], routeP, routeN)
                offset[i].set(old).sub(routeP)
                if (split == i - 1 && old.distanceSquared(tgtPos[i]) <= STILL_SQ) split = i
            }
            if (split == tgtPos.size - 1) { snapTo(tp); return }
            val length = tgtArc[tgtArc.size - 1] - (if (split >= 0) tgtArc[split] else 0.0)
            moveTicks = Math.max(MOVE_MIN_TICKS, length / MOVE_PX_PER_TICK)
            heaves = Math.max(1.0, Math.ceil(length / HEAVE_PX))
            roughen(startTime)
            morphStart = startTime
            update(timeNow)
        }

        /** Copies [tp]'s waypoints as the target, and resizes the shape to them. */
        private fun setTarget(tp: BPath) {
            val n = tp.points.size
            if (n != tgtPos.size) {
                tgtPos = Array(n) { Vector3d() }
                tgtNrm = Array(n) { Vector3d() }
                tgtArc = DoubleArray(n)
                curPos = Array(n) { Vector3d() }
                curNrm = Array(n) { Vector3d(0.0, 1.0, 0.0) }
                startOnRoute = DoubleArray(n)
                offset = Array(n) { Vector3d() }
                srcNrm = Array(n) { Vector3d(0.0, 1.0, 0.0) }
                pointSpeed = DoubleArray(n)
                pointDelay = DoubleArray(n)
                points = List(n) { BWaypoint(curPos[it], curNrm[it], 0.0) }
            }
            for (i in 0 until n) {
                val wp = tp.points[i]
                tgtPos[i].set(wp.pos); tgtNrm[i].set(wp.normal); tgtArc[i] = wp.arcPx
            }
            breaks = tp.breaks
        }

        /** Lays out the shape at [timeNow]. */
        fun update(timeNow: Double) {
            val age = timeNow - morphStart
            if (morphStart == Double.NEGATIVE_INFINITY || age >= Math.max(moveEnd, SNAP_TICKS)) {
                morphStart = Double.NEGATIVE_INFINITY
                for (i in curPos.indices) { curPos[i].set(tgtPos[i]); curNrm[i].set(tgtNrm[i]) }
                setArcs()
                return
            }
            val snap = YgannBarnacleColonyDraw.smooth((age / SNAP_TICKS).coerceIn(0.0, 1.0))
            val last = curPos.size - 1
            for (i in curPos.indices) {
                if (i <= split) { curPos[i].set(tgtPos[i]); curNrm[i].set(tgtNrm[i]); continue }
                // 0 at the first point that moves, 1 at the tip: the base slides first.
                val v = (i - split - 1).toDouble() / Math.max(1, last - split - 1)
                val own = ((age - pointDelay[i]) * pointSpeed[i] / moveTicks).coerceIn(0.0, 1.0)
                val q = heave((own * (1.0 + MORPH_LAG) - v * MORPH_LAG).coerceIn(0.0, 1.0))
                sampleOn(tgtPos, tgtNrm, tgtArc, startOnRoute[i] + (tgtArc[i] - startOnRoute[i]) * q, routeP, routeN)
                curPos[i].set(routeP).fma(1.0 - snap, offset[i])
                curNrm[i].set(srcNrm[i]).lerp(routeN, snap)
                if (curNrm[i].lengthSquared() > 1.0e-9) curNrm[i].normalize() else curNrm[i].set(routeN)
            }
            setArcs()
        }

        /** Gives each segment of the route its own slide speed, and a start delay that adds up along the
         *  chain, then blends both onto the points; sets when the slowest point arrives. */
        private fun roughen(startTime: Double) {
            if (breaks.size < 2) {
                pointSpeed.fill(1.0); pointDelay.fill(0.0); moveEnd = moveTicks
                return
            }
            val rng = java.util.Random(java.lang.Double.doubleToLongBits(phase) xor java.lang.Double.doubleToLongBits(startTime))
            val segments = breaks.size - 1
            val speed = DoubleArray(segments) { 1.0 + SPEED_JITTER * (rng.nextDouble() * 2.0 - 1.0) }
            val delay = DoubleArray(segments)
            var acc = 0.0
            for (k in 0 until segments) {
                delay[k] = acc
                acc += CHAIN_DELAY_TICKS * (0.5 + rng.nextDouble())
            }
            moveEnd = 0.0
            for (i in tgtArc.indices) {
                // Blend between the centres of the segments on each side, so speed changes along the limb without kinks.
                var k = 0
                while (k < segments - 1 && tgtArc[i] > (breaks[k + 1] + breaks[Math.min(k + 2, breaks.size - 1)]) * 0.5) k++
                val c0 = (breaks[k] + breaks[k + 1]) * 0.5
                val k1 = Math.min(k + 1, segments - 1)
                val c1 = (breaks[k1] + breaks[k1 + 1]) * 0.5
                val f = if (c1 > c0) ((tgtArc[i] - c0) / (c1 - c0)).coerceIn(0.0, 1.0) else 0.0
                pointSpeed[i] = speed[k] + (speed[k1] - speed[k]) * f
                pointDelay[i] = delay[k] + (delay[k1] - delay[k]) * f
                moveEnd = Math.max(moveEnd, pointDelay[i] + moveTicks / pointSpeed[i])
            }
        }

        /** Eased progress (0..1) that surges once per heave; below a depth of 1 it never backs up. */
        private fun heave(x: Double): Double {
            val s = YgannBarnacleColonyDraw.smooth(x)
            val w = 2.0 * Math.PI * heaves
            return (s + HEAVE_DEPTH * Math.sin(w * s) / w).coerceIn(0.0, 1.0)
        }

        /** True when the shape on screen now has a point inside a block. */
        fun touchesHull(hull: HullTest): Boolean {
            for (i in curPos.indices) if (hull.solid(curPos[i])) return true
            return false
        }

        /** Current centerline as shared waypoints for [YgannBarnacleColonyDraw]. Valid after [update]. */
        fun currentPoints(): List<BWaypoint> = points

        private fun setArcs() {
            for (i in points.indices) points[i].arcPx = tgtArc[i]
        }

        /** The arc on the new route nearest to [p]. */
        private fun nearestOnTarget(p: Vector3d): Double {
            var bestArc = 0.0
            var bestSq = Double.MAX_VALUE
            for (k in 1 until tgtPos.size) {
                val a = tgtPos[k - 1]
                val abx = tgtPos[k].x - a.x; val aby = tgtPos[k].y - a.y; val abz = tgtPos[k].z - a.z
                val len2 = abx * abx + aby * aby + abz * abz
                val t = if (len2 > 1.0e-12) ((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / len2 else 0.0
                val f = t.coerceIn(0.0, 1.0)
                val dx = a.x + abx * f - p.x; val dy = a.y + aby * f - p.y; val dz = a.z + abz * f - p.z
                val d2 = dx * dx + dy * dy + dz * dz
                if (d2 < bestSq) { bestSq = d2; bestArc = tgtArc[k - 1] + (tgtArc[k] - tgtArc[k - 1]) * f }
            }
            return bestArc
        }

        companion object {
            /** The shape ([pos], [nrm] at [arcs]) at [arc], clamped to its ends, into [outP] and [outN]; returns [outP]. */
            private fun sampleOn(
                pos: Array<Vector3d>, nrm: Array<Vector3d>, arcs: DoubleArray, arc: Double, outP: Vector3d, outN: Vector3d,
            ): Vector3d {
                val last = pos.size - 1
                if (last <= 0 || arc <= arcs[0]) { outP.set(pos[0]); outN.set(nrm[0]); return outP }
                for (k in 1..last) {
                    if (arcs[k] >= arc) {
                        val span = arcs[k] - arcs[k - 1]
                        val f = if (span > 1.0e-9) (arc - arcs[k - 1]) / span else 1.0
                        outP.set(pos[k - 1]).lerp(pos[k], f)
                        outN.set(nrm[k - 1]).lerp(nrm[k], f)
                        if (outN.lengthSquared() > 1.0e-9) outN.normalize() else outN.set(nrm[k])
                        return outP
                    }
                }
                outP.set(pos[last]); outN.set(nrm[last])
                return outP
            }
        }
    }

    companion object {
        private const val VALIDATE_TICKS = 4L
        /** Blocks past the waypoints that a drawn limb can reach: tube width with pulse swell and joint
         *  extensions, flinch lift and sway, and the cut ripple. The bulb and cirri stay inside it too. */
        private const val DRAW_MARGIN = 2.0
        private val BODY_BOUNDS = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0).inflate(DRAW_MARGIN)
        /** Cirri: the most on a full-grown bulb; points per cirrus; length (px) from young to full-grown;
         *  how far each leans out from the bulb's axis, and where on the rim (fraction of the bulb's
         *  half-width) it sits; total bend (radians) from young to full-grown; the writhe rate (rad/tick),
         *  fixed, since the phase is game time times the rate and a growing rate would race it;
         *  the wave's phase step per point; the sideways wave's share of the bend; the taper they take
         *  (a colony arc far out, so they are thin); and a seed salt. */
        private const val CIRRI_MAX = 8
        private const val CIRRUS_POINTS = 7
        private const val CIRRUS_MIN_PX = 3.0
        private const val CIRRUS_MAX_PX = 12.0
        private const val CIRRUS_LEAN = 0.6
        private const val CIRRUS_RIM = 0.75
        private const val CIRRUS_BEND_MIN = 0.4
        private const val CIRRUS_BEND_MAX = 2.2
        private const val CIRRUS_RATE = 0.22
        private const val CIRRUS_WAVE = 0.7
        private const val CIRRUS_SIDE_SHARE = 0.6
        private const val CIRRUS_ORIGIN_ARC = 190.0
        private const val CIRRUS_SALT = 0x2545F491
        /** A move: slide speed along the new route (px/tick) and shortest length (ticks); one heave (surge
         *  and slow) per [HEAVE_PX] of limb, [HEAVE_DEPTH] deep; how far behind the base the tip starts, as a
         *  fraction of each point's own slide; and how long (ticks) points take to ease onto the new route. */
        private const val MOVE_PX_PER_TICK = 1.0
        private const val MOVE_MIN_TICKS = 40.0
        private const val HEAVE_PX = 16.0
        private const val HEAVE_DEPTH = 0.75
        private const val MORPH_LAG = 0.4
        private const val SNAP_TICKS = 12.0
        /** Uneven slide: each segment's speed is the base speed ± this fraction, and each segment starts
         *  0.5 to 1.5 times [CHAIN_DELAY_TICKS] after the one before it. */
        private const val SPEED_JITTER = 0.3
        private const val CHAIN_DELAY_TICKS = 1.5
        /** The longest delay (ticks) before a limb starts its move; each limb has its own. */
        private const val MORPH_STAGGER_TICKS = 10
        /** Points nearer than this to their new place (blocks, squared) at the base count as unmoved. */
        private const val STILL_SQ = (0.25 / 16.0) * (0.25 / 16.0)
        private const val STALE_BASE_TICKS = 5L
        private const val MIN_SEVER_PX = 0.5
        private const val PX_PER_BLOCK = 16.0
        /** Flinch motion: rise time (ticks), lift and sway (px), sway rate (rad/tick), travelling-wave
         *  rate along the limb (rad/px), and the arc (px) over which the limb's root eases into the motion. */
        private const val FLINCH_RISE_TICKS = 4.0
        private const val FLINCH_LIFT_PX = 5.0
        private const val FLINCH_SWAY_PX = 3.0
        private const val FLINCH_SWAY_RATE = 0.6
        private const val FLINCH_WAVE_PER_PX = 0.15
        private const val FLINCH_RAMP_PX = 16.0
        /** Cut ripple: peak lift (px), half-width (px), speed toward the core (px/tick), fade-in (ticks),
         *  and the arc (px) over which a limb part that leaves the route lets go of the motion. */
        private const val RIPPLE_LIFT_PX = 4.0
        private const val RIPPLE_WIDTH_PX = 8.0
        private const val RIPPLE_SPEED_PX_PER_TICK = 6.0
        private const val RIPPLE_FADE_IN_TICKS = 2.0
        private const val RIPPLE_ATTACH_PX = 8.0
        private const val RIPPLE_CUTOFF_SQ = 9.0
    }
}
