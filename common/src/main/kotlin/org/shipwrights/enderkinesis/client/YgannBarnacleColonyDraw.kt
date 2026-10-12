package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BWaypoint
import org.shipwrights.enderkinesis.blockentity.YgannBarnacleBlockEntity

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
     *  tendril's root, so branches inherit the parent's taper. */
    fun drawTentacle(
        consumer: VertexConsumer, poseStack: PoseStack, light: Int,
        points: List<BWaypoint>, phase: Double, originArc: Double,
        revealPx: Double, time: Double, witherFront: Double,
    ) {
        if (points.size < 2 || revealPx <= 0.5) return
        val reveal = Math.min(revealPx, points.last().arcPx)
        val breath = 1.0 + BREATH_AMPLITUDE * (0.5 + 0.5 * Math.sin(time * BREATH_RATE + phase))
        var arc0 = 0.0
        var prevW = Double.MAX_VALUE
        while (arc0 < reveal - 1.0e-4) {
            val arc1 = Math.min(arc0 + SEGMENT_PX, reveal)
            val mid = (arc0 + arc1) * 0.5
            val structural = Math.min(radiusAt(mid, reveal, originArc) / 16.0 * breath, prevW)
            prevW = structural
            // Death: a segment stays at FULL thickness until the front reaches it, then
            // vanishes whole (no thinning) — it winks out with a particle puff.
            val gone = witherFront >= 0.0 && mid < witherFront
            if (!gone && structural > 1.0e-4) {
                sampleSegment(points, arc0, arc1)
                drawSegmentBox(consumer, poseStack, light, structural, arc0, arc1)
            }
            arc0 = arc1
            if (arc1 >= reveal) break
        }
    }

    /** Additive #481b4b pulse band travelling out from the origin (colony arc). */
    fun drawTentaclePulse(
        pulseConsumer: VertexConsumer, poseStack: PoseStack,
        points: List<BWaypoint>, phase: Double, originArc: Double,
        revealPx: Double, time: Double, witherFront: Double,
    ) {
        if (points.size < 2 || revealPx <= 0.5) return
        val reveal = Math.min(revealPx, points.last().arcPx)
        val breath = 1.0 + BREATH_AMPLITUDE * (0.5 + 0.5 * Math.sin(time * BREATH_RATE + phase))
        val pulsePos = ((time % PULSE_PERIOD_TICKS) / PULSE_PERIOD_TICKS) * PULSE_TRAVEL_PX
        var arc0 = 0.0
        var prevW = Double.MAX_VALUE
        while (arc0 < reveal - 1.0e-4) {
            val arc1 = Math.min(arc0 + SEGMENT_PX, reveal)
            val mid = (arc0 + arc1) * 0.5
            val structural = Math.min(radiusAt(mid, reveal, originArc) / 16.0 * breath, prevW)
            prevW = structural
            val gone = witherFront >= 0.0 && mid < witherFront
            val w = structural
            val d = Math.abs((originArc + mid) - pulsePos)
            val g = if (d < PULSE_WIDTH_PX) smooth(1.0 - d / PULSE_WIDTH_PX) else 0.0
            if (!gone && w > 1.0e-4 && g > 0.02) {
                sampleSegment(points, arc0, arc1)
                val core = Math.pow(g, PULSE_CORE_POWER) * PULSE_CORE_WHITE
                drawSegmentBox(
                    pulseConsumer, poseStack, LightTexture.FULL_BRIGHT, w * 1.05, arc0, arc1,
                    (PULSE_R * g * PULSE_BRIGHTNESS + core).toInt().coerceAtMost(255),
                    (PULSE_G * g * PULSE_BRIGHTNESS + core).toInt().coerceAtMost(255),
                    (PULSE_B * g * PULSE_BRIGHTNESS + core).toInt().coerceAtMost(255),
                )
            }
            arc0 = arc1
            if (arc1 >= reveal) break
        }
    }

    /** Death disintegration: on segments just AHEAD of the [witherFront] (within
     *  [bandPx]), overlay the dragon-death crackle with alpha ramping to full right at
     *  the front — each segment flares and dissolves the instant before it's cut. */
    fun drawTentacleDissolve(
        consumer: VertexConsumer, poseStack: PoseStack, light: Int,
        points: List<BWaypoint>, phase: Double, originArc: Double,
        revealPx: Double, time: Double, witherFront: Double, bandPx: Double,
    ) {
        if (witherFront < 0.0 || points.size < 2 || revealPx <= 0.5) return
        val reveal = Math.min(revealPx, points.last().arcPx)
        val breath = 1.0 + BREATH_AMPLITUDE * (0.5 + 0.5 * Math.sin(time * BREATH_RATE + phase))
        var arc0 = 0.0
        var prevW = Double.MAX_VALUE
        while (arc0 < reveal - 1.0e-4) {
            val arc1 = Math.min(arc0 + SEGMENT_PX, reveal)
            val mid = (arc0 + arc1) * 0.5
            val structural = Math.min(radiusAt(mid, reveal, originArc) / 16.0 * breath, prevW)
            prevW = structural
            // Crumble band [witherFront, witherFront+band]: vertex alpha is the discard
            // threshold — 0 at the far edge (intact) climbing to 1 at the front (gone),
            // so each segment eats away pixel-by-pixel as the front sweeps into it.
            val p = (mid - witherFront) / bandPx
            if (p in 0.0..1.0 && structural > 1.0e-4) {
                sampleSegment(points, arc0, arc1)
                val threshold = ((1.0 - p) * 255.0).toInt().coerceIn(0, 255)
                drawSegmentBox(consumer, poseStack, light, structural, arc0, arc1, 255, 255, 255, threshold)
            }
            arc0 = arc1
            if (arc1 >= reveal) break
        }
    }

    /** The central node cube, scaled by [sizeLevel] (0..1), proud of the [facing] face. */
    fun drawBody(
        consumer: VertexConsumer, poseStack: PoseStack, light: Int,
        planeOrigin: Vector3d, n: Vector3d, facing: Direction,
        sizeLevel: Double, pulse: Double, twitch: Double,
    ) {
        val (t1, t2) = tangentsOf(facing)
        val baseR = (YgannBarnacleBlockEntity.BODY_MIN_R_PX +
            (YgannBarnacleBlockEntity.BODY_MAX_R_PX - YgannBarnacleBlockEntity.BODY_MIN_R_PX) * sizeLevel) / 16.0 * pulse
        val height = (YgannBarnacleBlockEntity.BODY_MIN_H_PX +
            (YgannBarnacleBlockEntity.BODY_MAX_H_PX - YgannBarnacleBlockEntity.BODY_MIN_H_PX) * sizeLevel) / 16.0 *
            (0.92 + 0.08 * pulse)
        val centre = Vector3d(planeOrigin).add(Vector3d(t1).mul(twitch))
        val c000 = corner(centre, t1, t2, n, -baseR, -baseR, 0.0)
        val c100 = corner(centre, t1, t2, n, baseR, -baseR, 0.0)
        val c110 = corner(centre, t1, t2, n, baseR, baseR, 0.0)
        val c010 = corner(centre, t1, t2, n, -baseR, baseR, 0.0)
        val c001 = corner(centre, t1, t2, n, -baseR, -baseR, height)
        val c101 = corner(centre, t1, t2, n, baseR, -baseR, height)
        val c111 = corner(centre, t1, t2, n, baseR, baseR, height)
        val c011 = corner(centre, t1, t2, n, -baseR, baseR, height)
        val uw = (baseR * 2.0 * 16.0).toFloat().coerceAtMost(1f)
        val uh = (height * 16.0).toFloat().coerceAtMost(1f)
        quad(consumer, poseStack, light, c001, c101, c111, c011, 0f, 0f, uw, uw)   // top
        quad(consumer, poseStack, light, c000, c100, c101, c001, 0f, 0f, uw, uh)   // -t2
        quad(consumer, poseStack, light, c110, c010, c011, c111, 0f, 0f, uw, uh)   // +t2
        quad(consumer, poseStack, light, c100, c110, c111, c101, 0f, 0f, uw, uh)   // +t1
        quad(consumer, poseStack, light, c010, c000, c001, c011, 0f, 0f, uw, uh)   // -t1
    }

    fun tangentsOf(facing: Direction): Pair<Vector3d, Vector3d> {
        val n = Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
        val ref = if (Math.abs(n.y) > 0.5) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
        val t1 = Vector3d(ref).cross(n).normalize()
        val t2 = Vector3d(n).cross(t1).normalize()
        return Pair(t1, t2)
    }

    fun smooth(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    // Scratch for the segment draw, which runs for each tendril segment in each frame.
    // Render thread only.
    private val segAPos = Vector3d()
    private val segANrm = Vector3d()
    private val segBPos = Vector3d()
    private val segBNrm = Vector3d()
    private val boxDir = Vector3d()
    private val boxN = Vector3d()
    private val boxSide = Vector3d()
    private val boxC0 = Vector3d()
    private val boxC1 = Vector3d()
    private val boxCorners = Array(8) { Vector3d() }
    private val quadE1 = Vector3d()
    private val quadE2 = Vector3d()

    /** Writes the two ends of the segment [arc0]..[arc1] into the `seg` scratch vectors. */
    private fun sampleSegment(pts: List<BWaypoint>, arc0: Double, arc1: Double) {
        sampleAt(pts, Math.max(0.0, arc0 - SEGMENT_OVERLAP), segAPos, segANrm)
        sampleAt(pts, arc1, segBPos, segBNrm)
    }

    /** Interpolated position and normal at arc length [arc]. */
    private fun sampleAt(pts: List<BWaypoint>, arc: Double, outPos: Vector3d, outNrm: Vector3d) {
        if (arc <= 0.0) {
            outPos.set(pts[0].pos); outNrm.set(pts[0].normal)
            return
        }
        for (i in 1 until pts.size) {
            val b = pts[i]
            if (b.arcPx >= arc) {
                val a = pts[i - 1]
                val f = ((arc - a.arcPx) / (b.arcPx - a.arcPx)).coerceIn(0.0, 1.0)
                a.pos.lerp(b.pos, f, outPos)
                a.normal.lerp(b.normal, f, outNrm)
                if (outNrm.lengthSquared() > 1.0e-9) outNrm.normalize() else outNrm.set(b.normal)
                return
            }
        }
        val last = pts.size - 1
        outPos.set(pts[last].pos); outNrm.set(pts[last].normal)
    }

    private fun radiusAt(arcPx: Double, revealPx: Double, originArc: Double): Double {
        val t = ((originArc + arcPx) / COLONY_TAPER_LEN_PX).coerceIn(0.0, 1.0)
        val structural = Math.max(TENDRIL_MIN_R_PX, 2.4 * (1.0 - t * t * TENDRIL_TAPER))
        val fromTip = revealPx - arcPx
        val pinch = if (fromTip < TIP_ZONE_PX) (fromTip / TIP_ZONE_PX).coerceIn(0.0, 1.0) else 1.0
        return structural * pinch
    }

    /** Draws the box between the two ends that [sampleSegment] wrote. */
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
        val c0 = segAPos.fma(w, n, boxC0)
        val c1 = segBPos.fma(w, n, boxC1)
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

    private fun corner(
        centre: Vector3d, t1: Vector3d, t2: Vector3d, n: Vector3d, a: Double, b: Double, h: Double,
    ): Vector3d = Vector3d(centre)
        .add(Vector3d(t1).mul(a)).add(Vector3d(t2).mul(b)).add(Vector3d(n).mul(h))

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
    private const val SEGMENT_PX = 10.0
    private const val SEGMENT_OVERLAP = 2.0
    private const val BREATH_AMPLITUDE = 0.06
    private const val BREATH_RATE = 0.08
    private const val TIP_ZONE_PX = 8.0
    private const val COLONY_TAPER_LEN_PX = 220.0
    private const val TENDRIL_TAPER = 0.55
    private const val TENDRIL_MIN_R_PX = 0.85
    private const val PULSE_R = 72
    private const val PULSE_G = 27
    private const val PULSE_B = 75
    private const val PULSE_PERIOD_TICKS = 40.0
    private const val PULSE_TRAVEL_PX = 280.0
    private const val PULSE_WIDTH_PX = 22.0
    private const val PULSE_BRIGHTNESS = 2.6
    private const val PULSE_CORE_WHITE = 190.0
    private const val PULSE_CORE_POWER = 3.0
}
