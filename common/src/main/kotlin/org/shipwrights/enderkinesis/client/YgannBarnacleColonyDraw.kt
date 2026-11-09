package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BWaypoint
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity
import org.shipwrights.enderkinesis.util.smoothstep

/**
 * Shared box/tube drawing for the Ygann Barnacle colony — the live block-entity
 * renderer AND the transient death renderer both call these, so the tentacle geometry
 * exists in exactly one place. Pure emit functions: they take a centerline (list of
 * [BWaypoint]), a reveal length, and a wither front, and push quads to a consumer.
 */
object YgannBarnacleColonyDraw {

    val TEXTURE: ResourceLocation = ResourceLocation(EnderkinesisMod.MOD_ID, "textures/block/ygann_barnacle.png")
    val RENDER_TYPE: RenderType = RenderType.entityCutoutNoCull(TEXTURE)
    val PULSE_TYPE: RenderType = RenderType.eyes(TEXTURE)

    /** Segment disintegration — the Ender Dragon's death shader (rendertype_entity_alpha):
     *  DISCARD where the texture's ALPHA < the vertex alpha (a MOVING threshold), then
     *  output the texture colour unlit. The sheet packs it like dragon_exploding does:
     *  the barnacle's own RGB + the noise mask in ALPHA — the real tentacle art crumbles
     *  pixel-by-pixel as the threshold climbs (vanilla cutout's fixed 0.1 gave a solid box). */
    private val DISSOLVE_TEXTURE = ResourceLocation(EnderkinesisMod.MOD_ID, "textures/effect/ygann_dissolve.png")
    val DISSOLVE_TYPE: RenderType = RenderType.dragonExplosionAlpha(DISSOLVE_TEXTURE)

    /** The base tentacle tube (cutout). [witherFront] < 0 = alive; ≥ 0 dissolves every
     *  box the front (from the origin) has passed. [originArc] = colony distance to the
     *  tendril's root, so branches inherit the parent's taper. [breaks] are the route's segment ends. */
    fun drawTentacle(
        consumer: VertexConsumer, poseStack: PoseStack, light: Int,
        points: List<BWaypoint>, breaks: DoubleArray, phase: Double, originArc: Double,
        revealPx: Double, time: Double, witherFront: Double,
    ) {
        if (points.size < 2 || revealPx <= 0.5) return
        forEachSegment(points, breaks, revealPx, originArc, breath(time, phase)) { arc0, arc1, w ->
            // Death: a segment stays at FULL thickness until the front reaches it, then
            // vanishes whole (no thinning) — it winks out with a particle puff.
            val gone = witherFront >= 0.0 && (arc0 + arc1) * 0.5 < witherFront
            // A living limb swells where the pulse passes, so the glow reads as a bulge travelling down it.
            val swell = if (witherFront < 0.0) 1.0 + PULSE_SWELL * pulseAt(originArc + (arc0 + arc1) * 0.5, time) else 1.0
            if (!gone) drawSegmentBox(consumer, poseStack, light, w * swell, arc0, arc1)
        }
    }

    /** Additive #481b4b pulse band travelling out from the origin (colony arc). */
    fun drawTentaclePulse(
        pulseConsumer: VertexConsumer, poseStack: PoseStack,
        points: List<BWaypoint>, breaks: DoubleArray, phase: Double, originArc: Double,
        revealPx: Double, time: Double, witherFront: Double,
    ) {
        if (points.size < 2 || revealPx <= 0.5) return
        // Only segments in the band emit; past it the glow is far below the 0.02 cut, so the limb is skipped.
        val band = pulsePos(time)
        val reveal = Math.min(revealPx, points.last().arcPx)
        if (originArc + reveal < band - PULSE_SKIP_PX || originArc > band + PULSE_SKIP_PX) return
        forEachSegment(points, breaks, revealPx, originArc, breath(time, phase)) { arc0, arc1, w ->
            val mid = (arc0 + arc1) * 0.5
            if (originArc + mid > band + PULSE_SKIP_PX) return
            val gone = witherFront >= 0.0 && mid < witherFront
            val g = pulseAt(originArc + mid, time)
            if (!gone && g > 0.02) {
                val core = Math.pow(g, PULSE_CORE_POWER) * PULSE_CORE_WHITE
                drawSegmentBox(
                    pulseConsumer, poseStack, LightTexture.FULL_BRIGHT, w * (1.0 + PULSE_SWELL * g) * 1.05, arc0, arc1,
                    (PULSE_R * g * PULSE_BRIGHTNESS + core).toInt().coerceAtMost(255),
                    (PULSE_G * g * PULSE_BRIGHTNESS + core).toInt().coerceAtMost(255),
                    (PULSE_B * g * PULSE_BRIGHTNESS + core).toInt().coerceAtMost(255),
                )
            }
        }
    }

    /** Death disintegration: on segments just AHEAD of the [witherFront] (within
     *  [bandPx]), overlay the dragon-death crackle with alpha ramping to full right at
     *  the front — each segment flares and dissolves the instant before it's cut. */
    fun drawTentacleDissolve(
        consumer: VertexConsumer, poseStack: PoseStack, light: Int,
        points: List<BWaypoint>, breaks: DoubleArray, phase: Double, originArc: Double,
        revealPx: Double, time: Double, witherFront: Double, bandPx: Double,
    ) {
        if (witherFront < 0.0 || points.size < 2 || revealPx <= 0.5) return
        forEachSegment(points, breaks, revealPx, originArc, breath(time, phase)) { arc0, arc1, w ->
            // Crumble band [witherFront, witherFront+band]: vertex alpha is the discard
            // threshold — 0 at the far edge (intact) climbing to 1 at the front (gone),
            // so each segment eats away pixel-by-pixel as the front sweeps into it.
            val p = ((arc0 + arc1) * 0.5 - witherFront) / bandPx
            if (p in 0.0..1.0) {
                val threshold = ((1.0 - p) * 255.0).toInt().coerceIn(0, 255)
                drawSegmentBox(consumer, poseStack, light, w, arc0, arc1, 255, 255, 255, threshold)
            }
        }
    }

    /** The pulse's strength (0..1) at colony arc [colonyArc]: a band that travels out from the core. */
    private fun pulseAt(colonyArc: Double, time: Double): Double {
        val d = Math.abs(colonyArc - pulsePos(time))
        return if (d < PULSE_WIDTH_PX) smoothstep(1.0 - d / PULSE_WIDTH_PX) else 0.0
    }

    private fun pulsePos(time: Double): Double = ((time % PULSE_PERIOD_TICKS) / PULSE_PERIOD_TICKS) * PULSE_TRAVEL_PX

    private fun breath(time: Double, phase: Double): Double =
        1.0 + BREATH_AMPLITUDE * (0.5 + 0.5 * Math.sin(time * BREATH_RATE + phase))

    /** Calls [action] with (arc0, arc1, half-width) for each revealed segment, after writing its ends and
     *  its end extensions into the `seg` scratch. Each end reaches past its joint by as much as the bend
     *  there opens on the outside, so neighbouring boxes close the gap; a straight joint gets none, so
     *  overlapping faces do not fight. */
    private inline fun forEachSegment(
        points: List<BWaypoint>, breaks: DoubleArray, revealPx: Double, originArc: Double, breath: Double,
        action: (Double, Double, Double) -> Unit,
    ) {
        val reveal = Math.min(revealPx, points.last().arcPx)
        var prevW = Double.MAX_VALUE
        var havePrev = false
        // The last segment's far end and look-ahead samples are this segment's ends; reuse them.
        var haveC = false
        val last = breaks.size - 1
        cursor.reset(points)
        for (k in 0 until last) {
            val arc0 = breaks[k]
            if (arc0 >= reveal - 1.0e-4) break
            val arc1 = Math.min(breaks[k + 1], reveal)
            val w = Math.min(radiusAt((arc0 + arc1) * 0.5, reveal, originArc) / 16.0 * breath, prevW)
            prevW = w
            if (k == 0) {
                cursor.sampleInto(arc0, segAPos, segANrm)
                cursor.sampleInto(arc1, segBPos, segBNrm)
            } else {
                val freePos = segAPos; val freeNrm = segANrm
                segAPos = segBPos; segANrm = segBNrm
                if (haveC) {
                    segBPos = segCPos; segBNrm = segCNrm
                    segCPos = freePos; segCNrm = freeNrm
                } else {
                    segBPos = freePos; segBNrm = freeNrm
                    cursor.sampleInto(arc1, segBPos, segBNrm)
                }
            }
            haveC = false
            segBPos.sub(segAPos, segDir)
            if (segDir.lengthSquared() < 1.0e-12) continue
            segDir.normalize()
            val hasNext = k + 1 < last && breaks[k + 1] < reveal - 1.0e-4
            if (hasNext) {
                cursor.sampleInto(Math.min(breaks[k + 2], reveal), segCPos, segCNrm)
                haveC = true
                segCPos.sub(segBPos, segNextDir)
                if (segNextDir.lengthSquared() > 1.0e-12) segNextDir.normalize() else segNextDir.set(segDir)
            }
            segExt0 = if (havePrev) 2.0 * w * tanHalf(segPrevDir, segDir) else 0.0
            segExt1 = if (hasNext) 2.0 * w * tanHalf(segDir, segNextDir) else 0.0
            segPrevDir.set(segDir)
            havePrev = true
            if (w > 1.0e-4) action(arc0, arc1, w)
        }
    }

    /** tan of half the angle between unit vectors [a] and [b], at most 1 (a right angle). */
    private fun tanHalf(a: Vector3d, b: Vector3d): Double {
        val c = a.dot(b).coerceIn(-1.0, 1.0)
        return Math.min(1.0, Math.sqrt((1.0 - c) / (1.0 + c + 1.0e-9)))
    }

    /** The central node cube, scaled by [sizeLevel] (0..1), proud of the [facing] face. */
    fun drawBody(
        consumer: VertexConsumer, poseStack: PoseStack, light: Int, facing: Direction,
        sizeLevel: Double, pulse: Double, twitch: Double,
    ) {
        val frame = YgannBarnacleGeometry.frameOf(facing)
        val t1 = frame.t1
        val t2 = frame.t2
        val n = frame.n
        val baseR = (YgannBarnacleBlockEntity.BODY_MIN_R_PX +
            (YgannBarnacleBlockEntity.BODY_MAX_R_PX - YgannBarnacleBlockEntity.BODY_MIN_R_PX) * sizeLevel) / 16.0 * pulse
        val height = (YgannBarnacleBlockEntity.BODY_MIN_H_PX +
            (YgannBarnacleBlockEntity.BODY_MAX_H_PX - YgannBarnacleBlockEntity.BODY_MIN_H_PX) * sizeLevel) / 16.0 *
            (0.92 + 0.08 * pulse)
        val centre = bodyCentre.set(frame.planeOrigin).add(t1.x() * twitch, t1.y() * twitch, t1.z() * twitch)
        val c000 = corner(0, centre, t1, t2, n, -baseR, -baseR, 0.0)
        val c100 = corner(1, centre, t1, t2, n, baseR, -baseR, 0.0)
        val c110 = corner(2, centre, t1, t2, n, baseR, baseR, 0.0)
        val c010 = corner(3, centre, t1, t2, n, -baseR, baseR, 0.0)
        val c001 = corner(4, centre, t1, t2, n, -baseR, -baseR, height)
        val c101 = corner(5, centre, t1, t2, n, baseR, -baseR, height)
        val c111 = corner(6, centre, t1, t2, n, baseR, baseR, height)
        val c011 = corner(7, centre, t1, t2, n, -baseR, baseR, height)
        val uw = (baseR * 2.0 * 16.0).toFloat().coerceAtMost(1f)
        val uh = (height * 16.0).toFloat().coerceAtMost(1f)
        quad(consumer, poseStack, light, c001, c101, c111, c011, 0f, 0f, uw, uw)   // top
        quad(consumer, poseStack, light, c000, c100, c101, c001, 0f, 0f, uw, uh)   // -t2
        quad(consumer, poseStack, light, c110, c010, c011, c111, 0f, 0f, uw, uh)   // +t2
        quad(consumer, poseStack, light, c100, c110, c111, c101, 0f, 0f, uw, uh)   // +t1
        quad(consumer, poseStack, light, c010, c000, c001, c011, 0f, 0f, uw, uh)   // -t1
    }

    // Scratch for the segment draw, which runs for each tendril segment in each frame.
    // Render thread only. The three end samples rotate between segments, so they are vars.
    private val cursor = YgannBarnacleGeometry.PathCursor(keepTinyNormal = false)
    private var segAPos = Vector3d()
    private var segANrm = Vector3d()
    private var segBPos = Vector3d()
    private var segBNrm = Vector3d()
    private var segCPos = Vector3d()
    private var segCNrm = Vector3d()
    private val segDir = Vector3d()
    private val segPrevDir = Vector3d()
    private val segNextDir = Vector3d()
    private var segExt0 = 0.0
    private var segExt1 = 0.0
    private val boxDir = Vector3d()
    private val boxN = Vector3d()
    private val boxSide = Vector3d()
    private val boxC0 = Vector3d()
    private val boxC1 = Vector3d()
    private val boxCorners = Array(8) { Vector3d() }
    private val quadE1 = Vector3d()
    private val quadE2 = Vector3d()
    private val bodyCentre = Vector3d()
    private val bodyCorners = Array(8) { Vector3d() }

    /** The drawn tube's half-width (px) at [arcPx] on a limb revealed to [revealPx], before the breath. */
    fun radiusAt(arcPx: Double, revealPx: Double, originArc: Double): Double {
        val t = ((originArc + arcPx) / COLONY_TAPER_LEN_PX).coerceIn(0.0, 1.0)
        val structural = Math.max(TENDRIL_MIN_R_PX, 2.4 * (1.0 - t * t * TENDRIL_TAPER))
        val fromTip = revealPx - arcPx
        val pinch = if (fromTip < TIP_ZONE_PX) (fromTip / TIP_ZONE_PX).coerceIn(0.0, 1.0) else 1.0
        return structural * pinch
    }

    /** Draws the box between the two ends that [forEachSegment] wrote, reaching past them by its extensions. */
    @Suppress("LongParameterList")
    private fun drawSegmentBox(
        consumer: VertexConsumer, poseStack: PoseStack, light: Int, w: Double,
        arc0: Double, arc1: Double, cr: Int = 255, cg: Int = 255, cb: Int = 255, ca: Int = 255,
    ) {
        val dir = segBPos.sub(segAPos, boxDir)
        if (dir.lengthSquared() < 1.0e-12) return
        dir.normalize()
        val n = segANrm.add(segBNrm, boxN)
        if (n.lengthSquared() < 1.0e-9) n.set(segANrm) else n.normalize()
        val side = dir.cross(n, boxSide)
        if (side.lengthSquared() < 1.0e-9) side.set(1.0, 0.0, 0.0) else side.normalize()
        side.cross(dir, n).normalize()
        val c0 = segAPos.fma(w, n, boxC0).fma(-segExt0, dir)
        val c1 = segBPos.fma(w, n, boxC1).fma(segExt1, dir)
        val a00 = boxCorner(0, c0, side, n, -w, -w); val a10 = boxCorner(1, c0, side, n, w, -w)
        val a11 = boxCorner(2, c0, side, n, w, w); val a01 = boxCorner(3, c0, side, n, -w, w)
        val b00 = boxCorner(4, c1, side, n, -w, -w); val b10 = boxCorner(5, c1, side, n, w, -w)
        val b11 = boxCorner(6, c1, side, n, w, w); val b01 = boxCorner(7, c1, side, n, -w, w)
        val u0 = ((arc0 % 16.0) / 16.0).toFloat()
        val u1 = (u0 + (arc1 - arc0) / 16.0).toFloat()
        val ww = (w * 2.0 * 16.0).toFloat().coerceAtMost(1f)
        quad(consumer, poseStack, light, a01, a11, b11, b01, u0, 0f, u1, ww, cr, cg, cb, ca)
        quad(consumer, poseStack, light, a10, a00, b00, b10, u0, 0f, u1, ww, cr, cg, cb, ca)
        quad(consumer, poseStack, light, a11, a10, b10, b11, u0, 0f, u1, ww, cr, cg, cb, ca)
        quad(consumer, poseStack, light, a00, a01, b01, b00, u0, 0f, u1, ww, cr, cg, cb, ca)
        quad(consumer, poseStack, light, a00, a10, a11, a01, 0f, 0f, ww, ww, cr, cg, cb, ca)
        quad(consumer, poseStack, light, b10, b00, b01, b11, 0f, 0f, ww, ww, cr, cg, cb, ca)
    }

    private fun boxCorner(slot: Int, c: Vector3d, side: Vector3d, n: Vector3d, s: Double, h: Double): Vector3d =
        c.fma(s, side, boxCorners[slot]).fma(h, n)

    @Suppress("LongParameterList")
    private fun corner(
        slot: Int, centre: Vector3d, t1: Vector3dc, t2: Vector3dc, n: Vector3dc, a: Double, b: Double, h: Double,
    ): Vector3d = bodyCorners[slot].set(centre)
        .add(t1.x() * a, t1.y() * a, t1.z() * a).add(t2.x() * b, t2.y() * b, t2.z() * b).add(n.x() * h, n.y() * h, n.z() * h)

    @Suppress("LongParameterList")
    private fun quad(
        consumer: VertexConsumer, poseStack: PoseStack, light: Int,
        a: Vector3d, b: Vector3d, c: Vector3d, d: Vector3d,
        u0: Float, v0: Float, u1: Float, v1: Float,
        cr: Int = 255, cg: Int = 255, cb: Int = 255, ca: Int = 255,
    ) {
        val pose = poseStack.last()
        val m = pose.pose()
        val nm = pose.normal()
        val e1 = b.sub(a, quadE1)
        val e2 = d.sub(a, quadE2)
        val fn = e1.cross(e2)
        if (fn.lengthSquared() < 1.0e-12) return
        fn.normalize()
        val nx = fn.x.toFloat(); val ny = fn.y.toFloat(); val nz = fn.z.toFloat()
        consumer.vertex(m, a.x.toFloat(), a.y.toFloat(), a.z.toFloat()).color(cr, cg, cb, ca)
            .uv(u0, v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(nm, nx, ny, nz).endVertex()
        consumer.vertex(m, b.x.toFloat(), b.y.toFloat(), b.z.toFloat()).color(cr, cg, cb, ca)
            .uv(u1, v0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(nm, nx, ny, nz).endVertex()
        consumer.vertex(m, c.x.toFloat(), c.y.toFloat(), c.z.toFloat()).color(cr, cg, cb, ca)
            .uv(u1, v1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(nm, nx, ny, nz).endVertex()
        consumer.vertex(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).color(cr, cg, cb, ca)
            .uv(u0, v1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(nm, nx, ny, nz).endVertex()
    }

    // Tuning (shared by live + death renders).
    private const val BREATH_AMPLITUDE = 0.06
    private const val BREATH_RATE = 0.08
    private const val TIP_ZONE_PX = 8.0
    private const val COLONY_TAPER_LEN_PX = 220.0
    private const val TENDRIL_TAPER = 0.55
    private const val TENDRIL_MIN_R_PX = 0.85
    private const val PULSE_R = 72
    private const val PULSE_G = 27
    private const val PULSE_B = 75
    private val PULSE_PERIOD_TICKS = YgannBarnacleBlockEntity.PULSE_PERIOD_TICKS.toDouble()
    private const val PULSE_TRAVEL_PX = 280.0
    private const val PULSE_WIDTH_PX = 22.0
    /** Past this distance (px) from the band centre the glow is below 0.006, under the 0.02 emit cut. */
    private const val PULSE_SKIP_PX = PULSE_WIDTH_PX + 1.0
    private const val PULSE_BRIGHTNESS = 4.2
    private const val PULSE_CORE_WHITE = 255.0
    private const val PULSE_CORE_POWER = 3.0
    /** How much thicker a limb is at the pulse's peak (fraction of its width). */
    private const val PULSE_SWELL = 0.35
}
