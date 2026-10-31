package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.block.AltarOfIlluminationBlock
import org.shipwrights.enderkinesis.blockentity.AltarOfIlluminationBlockEntity
import org.shipwrights.enderkinesis.blockentity.AltarOfIlluminationBlockEntity.Event
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Inscription
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws what the Altar of Illumination holds: the blank scroll floating on the ink in its
 * basin (the ink itself is the block's fill model), each inscribed glyph as a glowing shard
 * standing in a crown round the basin's rim, and a finished scroll floating over it.
 *
 * The crown has a socket for every glyph a scroll can hold, the first at the front. Each
 * shard stands upright, swaying a little, and drifts slowly about its socket (out and in,
 * along the rim, up and down) on beats of its own, so the crown never moves in step. A soft
 * halo in the glyph's colour breathes behind it. Plain glyphs and the fusions are their own
 * shard items; anything else is the base Mystic Shard tinted for it.
 *
 * What last happened at the altar ([AltarOfIlluminationBlockEntity.Event]) plays out here:
 *
 * - **A fusion (Meld):** the new shard appears beside its partner and slides into it, easing in
 *   and out, as the two fade out and the fused shard fades in; it settles in as a ring of
 *   light spreads from it.
 * - **An annihilation (Overload):** the new shard appears beside its opposite and slides in
 *   until they touch, their light flaring white; they shake harder and harder as it swells,
 *   and burst in a flash and a spray of both colours. The glyphs after it move up a socket.
 * - **A finish:** the glyphs, in order, drop into the ink at the point nearest each, with a
 *   ripple and a splash, as the paper sinks into it; then the scroll rises out of the ink with
 *   a ripple and a splash (the block takes its bottle of ink then) and floats over the altar
 *   until it's taken.
 */
class AltarOfIlluminationRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<AltarOfIlluminationBlockEntity> {

    private val itemRenderer: ItemRenderer = ctx.itemRenderer

    /** Particle bursts already let off, so each goes once: by altar, event and beat. */
    private val burstsDone = LinkedHashSet<Long>()

    override fun render(
        be: AltarOfIlluminationBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val time = (be.level?.gameTime ?: 0L) + partialTick.toDouble()
        val events = be.events
        if (be.hasScroll) renderBlank(be, ps, bufferSource, packedLight)
        renderCrown(be, events, time, ps, bufferSource)
        // Each shard's animation on its own, so several play at once.
        for (ev in events) {
            val age = time - ev.time
            when (ev.kind) {
                Event.Kind.FUSE -> if (age < FUSE_END) meld(be, ev, events, age, time, ps, bufferSource)
                Event.Kind.ANNIHILATE -> if (age < ANNIHILATE_END) overload(be, ev, age, ps, bufferSource)
                Event.Kind.FINISH -> finish(be, ev, age, time, ps, bufferSource, packedLight)
            }
        }
        if (events.none { it.kind == Event.Kind.FINISH } && !be.ready.isEmpty) {
            floating(be.ready, 1.0, 0.0, time * SCROLL_TURN, time, ps, bufferSource, packedLight)
        }
    }

    private fun renderBlank(
        be: AltarOfIlluminationBlockEntity, ps: PoseStack, buffers: MultiBufferSource, light: Int,
    ) {
        val ink = be.blockState.getValue(AltarOfIlluminationBlock.INK)
        ps.pushPose()
        ps.translate(0.5, inkSurface(ink) + FLAT_MODEL_LIFT, 0.5)
        ps.mulPose(Axis.XP.rotationDegrees(90f))
        ps.scale(BLANK_SCALE, BLANK_SCALE, BLANK_SCALE)
        itemRenderer.renderStatic(be.blank, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, ps, buffers, be.level, 0)
        ps.popPose()
    }

    // ---- The crown -----------------------------------------------------------------------------

    /** Where a glyph in socket [slot] (fractional while moving between two) stands at [time]:
     *  its middle, which way it faces, and how far it tips. */
    private class Pose(val pos: Vec3, val facing: Double, val tip: Double)

    /** One socket's drift at [time]: out, along, up, sway, tip. Each socket's beats a little
     *  slower than the last, so none move together. */
    private fun driftAt(j: Int, time: Double): DoubleArray {
        val k = 1 + j * 0.13
        return doubleArrayOf(
            sin(time * 0.017 * k + j * 2.3) * 0.045 + sin(time * 0.041 * k + j) * 0.015,
            sin(time * 0.013 * k + j * 4.1) * 0.06 + sin(time * 0.037 * k + j * 1.7) * 0.015,
            sin(time * 0.021 * k + j * 3.3) * 0.04 + sin(time * 0.053 * k + j) * 0.01,
            sin(time * 0.03 + j) * 0.5,
            sin(time * 0.019 * k + j * 2.9) * 0.12,
        )
    }

    private fun crownPose(slot: Double, time: Double): Pose {
        // Between sockets, the drifts of both, blended, so a glyph moving along never jumps.
        val a = floor(slot).toInt(); val f = slot - a
        val d0 = driftAt(a, time); val d1 = if (f > 0) driftAt(a + 1, time) else d0
        val d = DoubleArray(5) { d0[it] + (d1[it] - d0[it]) * f }
        val socket = -PI / 2 + slot * (2 * PI / Inscription.MAX_GLYPHS)
        val r = CROWN_RADIUS + d[0]
        val angle = socket + d[1] / CROWN_RADIUS
        return Pose(Vec3(0.5 + cos(angle) * r, CROWN_HEIGHT + d[2], 0.5 + sin(angle) * r), -socket + PI / 2 + d[3], d[4])
    }

    /** Where glyph [i] stands now: its socket, or partway there from one further along while an
     *  annihilation before it is still closing the gap (several such add up). */
    private fun slotOf(i: Int, events: List<Event>, time: Double): Double {
        var slot = i.toDouble()
        for (e in events) {
            if (!e.removes || i < e.index) continue
            val age = time - e.time
            if (age < SOCKET_MOVE) slot += 1 - ease(age / SOCKET_MOVE)
        }
        return slot
    }

    /** The crown as it stands, with what the events under way change in it: the glyphs after
     *  an annihilated one moving up a socket, and a glyph appearing (a fusion, or a broken
     *  fusion's other half) held back until its moment. */
    private fun renderCrown(
        be: AltarOfIlluminationBlockEntity, events: List<Event>, time: Double, ps: PoseStack, buffers: MultiBufferSource,
    ) {
        glyphs@ for ((i, element) in be.glyphs.withIndex()) {
            var scale = 1.0
            var alpha = 1.0
            for (e in events) {
                if (e.index != i) continue
                val age = time - e.time
                if (e.kind == Event.Kind.ANNIHILATE && e.after != null && age < ANNIHILATE_END) {
                    if (age < BURST_AT) continue@glyphs
                    alpha = inOutCubic((age - BURST_AT) / 8)
                }
                if (e.kind == Event.Kind.FUSE && age < FUSE_END) {
                    if (age < MELD_SWAP) continue@glyphs
                    scale = 0.85 + 0.15 * outBack((age - MELD_SWAP) / 8)
                }
            }
            val pose = crownPose(slotOf(i, events, time), time)
            glyph(be, element, pose.pos, pose.facing, 0.0, pose.tip, scale, EKItems.glyphTint(element), haloBreath(time, i), HALO_SIZE, ps, buffers, i, alpha)
        }
    }

    private fun haloBreath(time: Double, i: Int): Double = 0.4 + 0.2 * sin(time * 0.07 + i * 1.3)

    // ---- Meld ------------------------------------------------------------------------------------

    private fun meld(
        be: AltarOfIlluminationBlockEntity, ev: Event, events: List<Event>, age: Double, time: Double, ps: PoseStack, buffers: MultiBufferSource,
    ) {
        val shard = ev.shard ?: return; val before = ev.before ?: return; val after = ev.after ?: return
        val at = crownPose(slotOf(ev.index, events, time), time)
        val side = besideOf(at.pos)
        val v = age - HOLD
        if (v < 0) {
            glyph(be, before, at.pos, at.facing, 0.0, at.tip, 1.0, EKItems.glyphTint(before), haloBreath(time, ev.index), HALO_SIZE, ps, buffers, ev.index)
            glyph(be, shard, side, at.facing, 0.0, at.tip, 1.0, EKItems.glyphTint(shard), 0.5, HALO_SIZE, ps, buffers, 7)
            return
        }
        if (v < MELD_SLIDE) {
            // A slide in, easing in and out; over its back half the two fade out as the fused
            // shard fades in, all at full size.
            val inc = lerp(side, at.pos, inOutCubic(v / MELD_SLIDE))
            val mix = inOutCubic((v - 3) / 5)
            glyph(be, before, at.pos, at.facing, 0.0, at.tip, 1.0, EKItems.glyphTint(before), 0.5, HALO_SIZE, ps, buffers, ev.index, 1 - mix)
            glyph(be, shard, inc, at.facing, 0.0, at.tip, 1.0, EKItems.glyphTint(shard), 0.5, HALO_SIZE, ps, buffers, 7, 1 - mix)
            glyph(be, after, at.pos, at.facing, 0.0, at.tip, 1.0, EKItems.glyphTint(after), 0.5, HALO_SIZE, ps, buffers, 8, mix)
            return
        }
        // A ring of light spreading from it, fast, then slowing.
        val q = (v - MELD_SLIDE) / MELD_RING
        if (q < 1) light(at.pos, EKItems.glyphTint(after), 0.9 * (1 - q), 0.06 + 0.8 * outCubic(q), ps, buffers)
    }

    // ---- Overload --------------------------------------------------------------------------------

    private fun overload(be: AltarOfIlluminationBlockEntity, ev: Event, age: Double, ps: PoseStack, buffers: MultiBufferSource) {
        val shard = ev.shard ?: return; val opposite = ev.before ?: return
        // Where the opposite stood when it was hit; the crown moves on without it.
        val start = crownPose(ev.origin.toDouble(), ev.time.toDouble())
        val oppAt = start.pos
        val incAt = besideOf(oppAt)
        val dir = incAt.subtract(oppAt).normalize()
        val contact = oppAt.add(dir.scale(0.05))
        val mid = contact.add(oppAt).scale(0.5)
        val v = age - HOLD
        val incColor = EKItems.glyphTint(shard); val oppColor = EKItems.glyphTint(opposite)
        if (v < OVERLOAD_BURST) {
            var inc = incAt; var opp = oppAt
            var scale = 1.0; var white = 0.0; var haloAlpha = 0.5; var haloSize = HALO_SIZE
            if (v >= 0 && v < OVERLOAD_SLIDE) {
                inc = lerp(incAt, contact, inOutCubic(v / OVERLOAD_SLIDE))
                white = clamp01((v - 4) / 4); haloAlpha = 0.5 + 0.4 * white
            } else if (v >= OVERLOAD_SLIDE) {
                // Shaking harder and faster as their light swells and whitens.
                val s = (v - OVERLOAD_SLIDE) / (OVERLOAD_BURST - OVERLOAD_SLIDE); val k = s * s
                val amp = 0.003 + 0.03 * k
                inc = contact.add(shake(v, amp, 1.5 + 3 * s)); opp = oppAt.add(shake(v, -amp, 1.5 + 3 * s))
                scale = 1 + 0.15 * k; white = 1.0; haloAlpha = 0.5 + 0.45 * k; haloSize = HALO_SIZE * (1 + 1.4 * k).toFloat()
            }
            glyph(be, opposite, opp, start.facing, 0.0, start.tip, scale, mixWhite(oppColor, white), haloAlpha, haloSize, ps, buffers, 8)
            glyph(be, shard, inc, start.facing, 0.0, start.tip, scale, mixWhite(incColor, white), haloAlpha, haloSize, ps, buffers, 7)
            return
        }
        // The burst: a flash, and a spray of both colours.
        val q = (v - OVERLOAD_BURST) / 9
        if (q < 1) {
            once(be, ev, 0) { spray(be, mid, intArrayOf(incColor, oppColor), 26, 0.6, 0.25) }
            val k = sin(PI * min(1.0, q * 1.6))
            light(mid, 0xFFFFFF, k, 0.8 * (0.4 + 0.6 * k), ps, buffers)
        }
    }

    // ---- Finishing -------------------------------------------------------------------------------

    private fun finish(
        be: AltarOfIlluminationBlockEntity, ev: Event, age: Double, time: Double, ps: PoseStack, buffers: MultiBufferSource, light: Int,
    ) {
        val surface = inkSurface(ev.inkBefore)
        val stagger = AltarOfIlluminationBlockEntity.DIVE_STAGGER.toDouble()
        val dive = AltarOfIlluminationBlockEntity.DIVE_TICKS.toDouble()
        for ((i, element) in ev.glyphs.withIndex()) {
            val start = i * stagger
            if (age < start) {
                // Still in the crown, waiting its turn.
                val pose = crownPose(i.toDouble(), time)
                glyph(be, element, pose.pos, pose.facing, 0.0, pose.tip, 1.0, EKItems.glyphTint(element), haloBreath(time, i), HALO_SIZE, ps, buffers, i)
                continue
            }
            val from = crownPose(i.toDouble(), ev.time + start)
            val to = Vec3(from.pos.x.coerceIn(INK_LO, INK_HI), surface + 0.002, from.pos.z.coerceIn(INK_LO, INK_HI))
            val p = (age - start) / dive
            if (p < 1) {
                // Across early, down late: an arc that drops into the ink, nose first.
                val across = outCubic(p); val down = inCubic(p)
                val pos = Vec3(
                    from.pos.x + (to.x - from.pos.x) * across,
                    from.pos.y + (to.y - from.pos.y) * down + 0.06 * sin(PI * p) * (1 - p),
                    from.pos.z + (to.z - from.pos.z) * across,
                )
                glyph(be, element, pos, from.facing + 2 * p, -1.3 * inOutCubic(p), 0.0, 1 - 0.35 * p, EKItems.glyphTint(element), 0.5 * (1 - p), HALO_SIZE, ps, buffers, i)
                continue
            }
            // Its splash: a ripple mostly of the ink's colour, droplets, and a spark of its own.
            val q = (age - start - dive) / 18
            if (q < 1) {
                once(be, ev, 10 + i) { splash(be, to, EKItems.glyphTint(element), 8) }
                ripple(to, 0.02 + 0.2 * outCubic(q), mixColor(INK_RIPPLE, EKItems.glyphTint(element), 0.12), 0.7 * (1 - q), ps, buffers)
                if (q < 0.33) light(to.add(0.0, 0.02, 0.0), EKItems.glyphTint(element), 1 - q * 3, 0.22, ps, buffers)
            }
        }
        val rise = AltarOfIlluminationBlockEntity.riseTicks(ev.glyphs.size).toDouble()
        val centre = Vec3(0.5, surface + 0.003, 0.5)
        if (age < rise) {
            // The paper sinks into the ink as the glyphs go in, lying flat, turning a little.
            if (!ev.paper.isEmpty) {
                val s = inOutCubic(age / (rise - 2))
                val y = surface + FLAT_MODEL_LIFT - (surface - BASIN_FLOOR + 0.03) * s
                ps.pushPose()
                ps.translate(0.5, y, 0.5)
                ps.mulPose(Axis.YP.rotation((0.6 * s).toFloat()))
                ps.mulPose(Axis.XP.rotationDegrees(90f))
                ps.scale(BLANK_SCALE, BLANK_SCALE, BLANK_SCALE)
                itemRenderer.renderStatic(ev.paper, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, ps, buffers, be.level, 0)
                ps.popPose()
            }
            return
        }
        if (be.ready.isEmpty) return
        val v = age - rise
        // Out of the ink, from under it, with a ripple and a splash; upright as it rises; then
        // floating.
        if (v < 22) {
            once(be, ev, 1) { splash(be, centre, INK_DARK, 14) }
            val q = v / 22
            ripple(centre, 0.02 + 0.3 * outCubic(q), INK_RIPPLE, 0.7 * (1 - q), ps, buffers)
            val q2 = v / 16
            if (q2 < 1) ripple(centre, 0.02 + 0.17 * outCubic(q2), INK_RIPPLE, 0.7 * (1 - q2), ps, buffers)
        }
        floating(be.ready, outCubic(v / 24), 90.0 * (1 - inOutCubic(v / 20)), v * SCROLL_TURN, time, ps, buffers, light, BASIN_FLOOR - 0.02, ease(v / 12))
    }

    /** A finished scroll: from [surface] ([up] 0; under the ink, as it starts to rise) to
     *  floating over the altar (1), tipped back [flatDegrees] from upright, turned [turn]
     *  (radians), in a glow [glow] strong. */
    private fun floating(
        stack: ItemStack, up: Double, flatDegrees: Double, turn: Double, time: Double, ps: PoseStack, buffers: MultiBufferSource, light: Int,
        surface: Double = HOVER_HEIGHT, glow: Double = 1.0,
    ) {
        val y = surface + FLAT_MODEL_LIFT + (HOVER_HEIGHT - surface) * up + 0.02 * sin(time * 0.12) * up
        val pos = Vec3(0.5, y, 0.5)
        if (up > 0) light(pos, SCROLL_GLOW, 0.45 * glow * (0.85 + 0.15 * sin(time * 0.1)), 0.5, ps, buffers)
        ps.pushPose()
        ps.translate(pos.x, pos.y, pos.z)
        ps.mulPose(Axis.YP.rotation(turn.toFloat()))
        ps.mulPose(Axis.XP.rotationDegrees(flatDegrees.toFloat()))
        ps.scale(BLANK_SCALE, BLANK_SCALE, BLANK_SCALE)
        itemRenderer.renderStatic(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, ps, buffers, null, 0)
        ps.popPose()
    }

    // ---- Drawing ---------------------------------------------------------------------------------

    /** A glyph's shard at [pos], facing [facing], tipped forward [pitch] and sideways [tip],
     *  [scale] its size and [alpha] how solid, full-bright, with a halo of [haloRgb] behind it. */
    private fun glyph(
        be: AltarOfIlluminationBlockEntity, element: Element, pos: Vec3, facing: Double, pitch: Double, tip: Double, scale: Double,
        haloRgb: Int, haloAlpha: Double, haloSize: Float, ps: PoseStack, buffers: MultiBufferSource, seed: Int, alpha: Double = 1.0,
    ) {
        if (scale <= 0.001 || alpha <= 0.004) return
        ps.pushPose()
        ps.translate(pos.x, pos.y, pos.z)
        halo(ps, buffers, haloRgb, (haloAlpha * min(1.0, scale) * alpha).toFloat(), haloSize * scale.toFloat())
        ps.mulPose(Axis.YP.rotation(facing.toFloat()))
        ps.mulPose(Axis.XP.rotation(pitch.toFloat()))
        ps.mulPose(Axis.ZP.rotation(tip.toFloat()))
        val s = GLYPH_SCALE * scale.toFloat()
        ps.scale(s, s, s)
        // Items draw translucent, so a shard fades by dimming what it draws.
        val into = if (alpha < 0.999) BeamClashes.Dimmed(buffers, alpha.toFloat()) else buffers
        itemRenderer.renderStatic(
            EKItems.glyphStack(element), ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, ps, into, be.level, be.blockPos.asLong().toInt() + seed,
        )
        ps.popPose()
    }

    /** A soft glow of [rgb] at [pos], [size] across. */
    private fun light(pos: Vec3, rgb: Int, alpha: Double, size: Double, ps: PoseStack, buffers: MultiBufferSource) {
        ps.pushPose()
        ps.translate(pos.x, pos.y, pos.z)
        halo(ps, buffers, rgb, alpha.toFloat(), size.toFloat())
        ps.popPose()
    }

    /** A soft glow in [rgb], [alpha] strong and [size] across, here: vanilla's firework flash,
     *  added to the light and facing the camera. Queued for after translucent blocks
     *  ([AltarGlows]), or the altar, which is one, would be drawn over it. */
    private fun halo(ps: PoseStack, @Suppress("UNUSED_PARAMETER") buffers: MultiBufferSource, rgb: Int, alpha: Float, size: Float) {
        if (alpha <= 0.004f || size <= 0f) return
        AltarGlows.glow(ps.last().pose().transformPosition(Vector3f()), rgb, alpha, size)
    }

    /** A flat ring on the ink at [centre], [radius] out, kept inside the basin: where it would
     *  pass the brim it runs along it instead. Queued, as the glows are, for after the ink. */
    private fun ripple(centre: Vec3, radius: Double, rgb: Int, alpha: Double, ps: PoseStack, @Suppress("UNUSED_PARAMETER") buffers: MultiBufferSource) {
        if (alpha <= 0.004) return
        val m = ps.last().pose()
        val inner = (radius - RIPPLE_WIDTH).coerceAtLeast(0.0)
        val y = centre.y.toFloat()
        fun pt(r: Double, ang: Double) = m.transformPosition(Vector3f(
            (centre.x + cos(ang) * r).coerceIn(INK_LO, INK_HI).toFloat(), y, (centre.z + sin(ang) * r).coerceIn(INK_LO, INK_HI).toFloat(),
        ))
        for (k in 0 until RIPPLE_SEGMENTS) {
            val a0 = 2 * PI * k / RIPPLE_SEGMENTS; val a1 = 2 * PI * (k + 1) / RIPPLE_SEGMENTS
            AltarGlows.quad(arrayOf(pt(inner, a0), pt(radius, a0), pt(radius, a1), pt(inner, a1)), rgb, alpha.toFloat())
        }
    }

    /** A spray of [count] coloured specks from [at], out and a little up. */
    private fun spray(be: AltarOfIlluminationBlockEntity, at: Vec3, colors: IntArray, count: Int, speed: Double, up: Double) {
        val level = be.level ?: return
        val random = level.random
        val base = Vec3.atLowerCornerOf(be.blockPos).add(at)
        for (k in 0 until count) {
            val d = Vec3(random.nextDouble() - 0.5, random.nextDouble() * 0.7 + up, random.nextDouble() - 0.5).normalize()
                .scale(speed * (0.5 + random.nextDouble()))
            level.addParticle(dust(colors[k % colors.size]), base.x, base.y, base.z, d.x, d.y, d.z)
        }
    }

    /** Droplets of ink, and a few of [rgb], thrown up from the ink at [at]. */
    private fun splash(be: AltarOfIlluminationBlockEntity, at: Vec3, rgb: Int, count: Int) {
        val level = be.level ?: return
        val random = level.random
        val base = Vec3.atLowerCornerOf(be.blockPos).add(at).add(0.0, 0.01, 0.0)
        for (k in 0 until count) {
            val color = if (k % 3 == 0) rgb else INK_DARK
            level.addParticle(dust(color, 0.6f), base.x, base.y, base.z,
                (random.nextDouble() - 0.5) * 0.15, 0.15 + random.nextDouble() * 0.15, (random.nextDouble() - 0.5) * 0.15)
        }
    }

    private fun dust(rgb: Int, size: Float = 0.8f) = DustParticleOptions(
        Vector3f(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f), size,
    )

    /** Runs [f] once for [beat] of [ev] at this altar. */
    private inline fun once(be: AltarOfIlluminationBlockEntity, ev: Event, beat: Int, f: () -> Unit) {
        val key = (be.blockPos.asLong() * 31 + ev.time) * 64 + ev.origin * 4 + ev.kind.ordinal + beat * 256
        if (!burstsDone.add(key)) return
        if (burstsDone.size > 512) burstsDone.remove(burstsDone.first())
        f()
    }

    // ---- Shapes ----------------------------------------------------------------------------------

    /** Beside [pos] along the rim, where a shard that meets it appears. */
    private fun besideOf(pos: Vec3): Vec3 {
        val rx = pos.x - 0.5; val rz = pos.z - 0.5
        val len = sqrt(rx * rx + rz * rz).coerceAtLeast(1e-6)
        return pos.add(-rz / len * BESIDE, 0.0, rx / len * BESIDE)
    }

    private fun shake(v: Double, k: Double, f: Double) = Vec3(sin(v * 9.1 * f) * k, sin(v * 7.3 * f + 1) * k * 0.6, cos(v * 8.2 * f) * k)

    private fun lerp(a: Vec3, b: Vec3, t: Double) = Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
    private fun clamp01(s: Double) = s.coerceIn(0.0, 1.0)
    private fun ease(s: Double): Double { val t = clamp01(s); return t * t * (3 - 2 * t) }
    private fun inOutCubic(s: Double): Double { val t = clamp01(s); return if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2 }
    private fun outCubic(s: Double): Double = 1 - (1 - clamp01(s)).pow(3)
    private fun inCubic(s: Double): Double = clamp01(s).pow(3)
    /** Settles onto 1 with a little overshoot. */
    private fun outBack(s: Double, over: Double = 1.6): Double { val t = clamp01(s); val c = over + 1; return 1 + c * (t - 1).pow(3) + over * (t - 1).pow(2) }

    private fun mixColor(a: Int, b: Int, t: Double): Int {
        fun ch(x: Int, shift: Int) = (x shr shift) and 0xFF
        fun mix(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).toInt().coerceIn(0, 255)
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun mixWhite(rgb: Int, t: Double) = mixColor(rgb, 0xFFFFFF, t)

    /** The ink's surface with [bottles] in the basin, as the block's fill models are. */
    private fun inkSurface(bottles: Int): Double = BASIN_FLOOR + (BASIN_TOP - BASIN_FLOOR) * bottles / AltarOfIlluminationBlock.MAX_INK

    companion object {
        /** The basin the ink fills, from the model: floor at 14 px, brim at 15.5 px, the ink
         *  inside the brim from 2 to 14 px across. */
        private const val BASIN_FLOOR = 14.0 / 16.0
        private const val BASIN_TOP = 15.5 / 16.0
        private const val INK_LO = 2.0 / 16.0 + 0.04
        private const val INK_HI = 14.0 / 16.0 - 0.04
        private const val FLAT_MODEL_LIFT = 1.0 / 64.0
        private const val BLANK_SCALE = 0.55f
        /** A finished scroll floats this high. */
        private const val HOVER_HEIGHT = BASIN_TOP + 0.55

        /** The crown: its sockets this far from the altar's axis, the shards' middles this high. */
        private const val CROWN_RADIUS = 0.4
        private const val CROWN_HEIGHT = 1.14
        private const val GLYPH_SCALE = 0.22f
        private const val HALO_SIZE = 0.45f
        /** A shard that meets a glyph appears this far beside it along the rim. */
        private const val BESIDE = 0.2

        /** Ticks the new shard stands beside the glyph it meets before anything happens. */
        private const val HOLD = 6.0
        private const val MELD_SLIDE = 8.0
        private const val MELD_RING = 12.0
        private const val MELD_SWAP = HOLD + MELD_SLIDE
        private const val FUSE_END = MELD_SWAP + MELD_RING
        private const val OVERLOAD_SLIDE = 8.0
        private const val OVERLOAD_BURST = 15.0
        private const val BURST_AT = HOLD + OVERLOAD_BURST
        private const val ANNIHILATE_END = BURST_AT + 10
        /** Ticks the glyphs after an annihilated one take to move up a socket. */
        private const val SOCKET_MOVE = 15.0

        private const val RIPPLE_WIDTH = 0.6 / 16.0
        private const val RIPPLE_SEGMENTS = 32
        private const val INK_RIPPLE = 0x2A1F3A
        private const val INK_DARK = 0x2E2240
        private const val SCROLL_GLOW = 0xFFF0C8
        /** A floating scroll turns this far a tick (radians), counted from when it starts to
         *  rise, so it sets off from rest. */
        private const val SCROLL_TURN = 0.03
    }
}
