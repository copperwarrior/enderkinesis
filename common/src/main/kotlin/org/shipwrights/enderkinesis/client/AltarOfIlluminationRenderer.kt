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
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.block.AltarOfIlluminationBlock
import org.shipwrights.enderkinesis.blockentity.AltarOfIlluminationBlockEntity
import org.shipwrights.enderkinesis.blockentity.AltarOfIlluminationBlockEntity.Event
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Inscription
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AltarOfIlluminationRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<AltarOfIlluminationBlockEntity> {

    private val itemRenderer: ItemRenderer = ctx.itemRenderer

    // Keys of emitted bursts. Render runs each frame, but each burst must play one time.
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

    private class Pose(val pos: Vec3, val facing: Double, val tip: Double)

    /** Returns out, along, up, sway, tip. Each socket uses slower beats than the last, so no two move together. */
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
        // Blend the drifts of both sockets, so a moving glyph does not jump.
        val a = floor(slot).toInt(); val f = slot - a
        val d0 = driftAt(a, time); val d1 = if (f > 0) driftAt(a + 1, time) else d0
        val d = DoubleArray(5) { d0[it] + (d1[it] - d0[it]) * f }
        val socket = -PI / 2 + slot * (2 * PI / Inscription.MAX_GLYPHS)
        val r = CROWN_RADIUS + d[0]
        val angle = socket + d[1] / CROWN_RADIUS
        return Pose(Vec3(0.5 + cos(angle) * r, CROWN_HEIGHT + d[2], 0.5 + sin(angle) * r), -socket + PI / 2 + d[3], d[4])
    }

    /** Fractional while an earlier annihilation closes the gap; several such gaps add up. */
    private fun slotOf(i: Int, events: List<Event>, time: Double): Double {
        var slot = i.toDouble()
        for (e in events) {
            if (!e.removes || i < e.index) continue
            val age = time - e.time
            if (age < SOCKET_MOVE) slot += 1 - ease(age / SOCKET_MOVE)
        }
        return slot
    }

    /** Hides a glyph that an event adds until the event reaches that moment. */
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
            val inc = lerp(side, at.pos, inOutCubic(v / MELD_SLIDE))
            val mix = inOutCubic((v - 3) / 5)
            glyph(be, before, at.pos, at.facing, 0.0, at.tip, 1.0, EKItems.glyphTint(before), 0.5, HALO_SIZE, ps, buffers, ev.index, 1 - mix)
            glyph(be, shard, inc, at.facing, 0.0, at.tip, 1.0, EKItems.glyphTint(shard), 0.5, HALO_SIZE, ps, buffers, 7, 1 - mix)
            glyph(be, after, at.pos, at.facing, 0.0, at.tip, 1.0, EKItems.glyphTint(after), 0.5, HALO_SIZE, ps, buffers, 8, mix)
            return
        }
        val q = (v - MELD_SLIDE) / MELD_RING
        if (q < 1) light(at.pos, EKItems.glyphTint(after), 0.9 * (1 - q), 0.06 + 0.8 * outCubic(q), ps, buffers)
    }

    private fun overload(be: AltarOfIlluminationBlockEntity, ev: Event, age: Double, ps: PoseStack, buffers: MultiBufferSource) {
        val shard = ev.shard ?: return; val opposite = ev.before ?: return
        // Pose at hit time, because the crown moves on without the opposite glyph.
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
                val s = (v - OVERLOAD_SLIDE) / (OVERLOAD_BURST - OVERLOAD_SLIDE); val k = s * s
                val amp = 0.003 + 0.03 * k
                inc = contact.add(shake(v, amp, 1.5 + 3 * s)); opp = oppAt.add(shake(v, -amp, 1.5 + 3 * s))
                scale = 1 + 0.15 * k; white = 1.0; haloAlpha = 0.5 + 0.45 * k; haloSize = HALO_SIZE * (1 + 1.4 * k).toFloat()
            }
            glyph(be, opposite, opp, start.facing, 0.0, start.tip, scale, mixWhite(oppColor, white), haloAlpha, haloSize, ps, buffers, 8)
            glyph(be, shard, inc, start.facing, 0.0, start.tip, scale, mixWhite(incColor, white), haloAlpha, haloSize, ps, buffers, 7)
            return
        }
        val q = (v - OVERLOAD_BURST) / 9
        if (q < 1) {
            once(be, ev, 0) { spray(be, mid, intArrayOf(incColor, oppColor), 26, 0.6, 0.25) }
            val k = sin(PI * min(1.0, q * 1.6))
            light(mid, 0xFFFFFF, k, 0.8 * (0.4 + 0.6 * k), ps, buffers)
        }
    }

    private fun finish(
        be: AltarOfIlluminationBlockEntity, ev: Event, age: Double, time: Double, ps: PoseStack, buffers: MultiBufferSource, light: Int,
    ) {
        val surface = inkSurface(ev.inkBefore)
        for ((i, element) in ev.glyphs.withIndex()) diveGlyph(be, ev, i, element, age, time, surface, ps, buffers)
        val rise = AltarOfIlluminationBlockEntity.riseTicks(ev.glyphs.size).toDouble()
        val centre = Vec3(0.5, surface + 0.003, 0.5)
        if (age < rise) {
            if (!ev.paper.isEmpty) sinkPaper(be, ev, age, rise, surface, ps, buffers, light)
            return
        }
        if (be.ready.isEmpty) return
        val v = age - rise
        if (v < 22) {
            once(be, ev, 1) {
                splash(be, centre, INK_DARK, 14)
                playLocal(be, EKSounds.ALTAR_OF_ILLUMINATION_RISE.get())
            }
            val q = v / 22
            ripple(centre, 0.02 + 0.3 * outCubic(q), INK_RIPPLE, 0.7 * (1 - q), ps, buffers)
            val q2 = v / 16
            if (q2 < 1) ripple(centre, 0.02 + 0.17 * outCubic(q2), INK_RIPPLE, 0.7 * (1 - q2), ps, buffers)
        }
        floating(be.ready, outCubic(v / 24), 90.0 * (1 - inOutCubic(v / 20)), v * SCROLL_TURN, time, ps, buffers, light, BASIN_FLOOR - 0.02, ease(v / 12))
    }

    private fun diveGlyph(
        be: AltarOfIlluminationBlockEntity, ev: Event, i: Int, element: Element, age: Double, time: Double, surface: Double,
        ps: PoseStack, buffers: MultiBufferSource,
    ) {
        val start = i * AltarOfIlluminationBlockEntity.DIVE_STAGGER.toDouble()
        val dive = AltarOfIlluminationBlockEntity.DIVE_TICKS.toDouble()
        if (age < start) {
            val pose = crownPose(i.toDouble(), time)
            glyph(be, element, pose.pos, pose.facing, 0.0, pose.tip, 1.0, EKItems.glyphTint(element), haloBreath(time, i), HALO_SIZE, ps, buffers, i)
            return
        }
        val from = crownPose(i.toDouble(), ev.time + start)
        val to = Vec3(from.pos.x.coerceIn(INK_LO, INK_HI), surface + 0.002, from.pos.z.coerceIn(INK_LO, INK_HI))
        val p = (age - start) / dive
        if (p < 1) {
            once(be, ev, 20 + i) { playLocal(be, EKSounds.ALTAR_OF_ILLUMINATION_GLYPH_DIVE.get()) }
            val across = outCubic(p); val down = inCubic(p)
            val pos = Vec3(
                from.pos.x + (to.x - from.pos.x) * across,
                from.pos.y + (to.y - from.pos.y) * down + 0.06 * sin(PI * p) * (1 - p),
                from.pos.z + (to.z - from.pos.z) * across,
            )
            glyph(be, element, pos, from.facing + 2 * p, -1.3 * inOutCubic(p), 0.0, 1 - 0.35 * p, EKItems.glyphTint(element), 0.5 * (1 - p), HALO_SIZE, ps, buffers, i)
            return
        }
        val q = (age - start - dive) / 18
        if (q < 1) {
            once(be, ev, 10 + i) { splash(be, to, EKItems.glyphTint(element), 8) }
            ripple(to, 0.02 + 0.2 * outCubic(q), mixColor(INK_RIPPLE, EKItems.glyphTint(element), 0.12), 0.7 * (1 - q), ps, buffers)
            if (q < 0.33) light(to.add(0.0, 0.02, 0.0), EKItems.glyphTint(element), 1 - q * 3, 0.22, ps, buffers)
        }
    }

    private fun sinkPaper(
        be: AltarOfIlluminationBlockEntity, ev: Event, age: Double, rise: Double, surface: Double,
        ps: PoseStack, buffers: MultiBufferSource, light: Int,
    ) {
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

    /** [up] goes from 0 at [surface] to 1 at the hover height. [turn] is in radians. */
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

    private fun light(pos: Vec3, rgb: Int, alpha: Double, size: Double, ps: PoseStack, buffers: MultiBufferSource) {
        ps.pushPose()
        ps.translate(pos.x, pos.y, pos.z)
        halo(ps, buffers, rgb, alpha.toFloat(), size.toFloat())
        ps.popPose()
    }

    // Queue for [AltarGlows], because the translucent altar draws over a glow drawn here.
    // UNUSED_PARAMETER: all draw helpers take buffers, but the queue draws later.
    private fun halo(ps: PoseStack, @Suppress("UNUSED_PARAMETER") buffers: MultiBufferSource, rgb: Int, alpha: Float, size: Float) {
        if (alpha <= 0.004f || size <= 0f) return
        AltarGlows.glow(ps.last().pose().transformPosition(Vector3f()), rgb, alpha, size)
    }

    // Clamped inside the basin. Queued for [AltarGlows], as [halo] is.
    // UNUSED_PARAMETER: all draw helpers take buffers, but the queue draws later.
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

    private fun playLocal(be: AltarOfIlluminationBlockEntity, sound: SoundEvent) {
        val pos = be.blockPos
        be.level?.playLocalSound(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, sound, SoundSource.BLOCKS, 1f, 1f, false)
    }

    private fun dust(rgb: Int, size: Float = 0.8f) = DustParticleOptions(
        Vector3f(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f), size,
    )

    private inline fun once(be: AltarOfIlluminationBlockEntity, ev: Event, beat: Int, f: () -> Unit) {
        val key = (be.blockPos.asLong() * 31 + ev.time) * 64 + ev.origin * 4 + ev.kind.ordinal + beat * 256
        if (!burstsDone.add(key)) return
        if (burstsDone.size > 512) burstsDone.remove(burstsDone.first())
        f()
    }

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
    private fun outBack(s: Double, over: Double = 1.6): Double { val t = clamp01(s); val c = over + 1; return 1 + c * (t - 1).pow(3) + over * (t - 1).pow(2) }

    private fun mixColor(a: Int, b: Int, t: Double): Int {
        fun ch(x: Int, shift: Int) = (x shr shift) and 0xFF
        fun mix(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).toInt().coerceIn(0, 255)
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun mixWhite(rgb: Int, t: Double) = mixColor(rgb, 0xFFFFFF, t)

    /** Must match the block's fill models. */
    private fun inkSurface(bottles: Int): Double = BASIN_FLOOR + (BASIN_TOP - BASIN_FLOOR) * bottles / AltarOfIlluminationBlock.MAX_INK

    companion object {
        // From the block model: floor at 14 px, brim at 15.5 px, ink from 2 to 14 px across.
        private const val BASIN_FLOOR = 14.0 / 16.0
        private const val BASIN_TOP = 15.5 / 16.0
        private const val INK_LO = 2.0 / 16.0 + 0.04
        private const val INK_HI = 14.0 / 16.0 - 0.04
        private const val FLAT_MODEL_LIFT = 1.0 / 64.0
        private const val BLANK_SCALE = 0.55f
        private const val HOVER_HEIGHT = BASIN_TOP + 0.55

        private const val CROWN_RADIUS = 0.4
        private const val CROWN_HEIGHT = 1.14
        private const val GLYPH_SCALE = 0.22f
        private const val HALO_SIZE = 0.45f
        private const val BESIDE = 0.2

        private const val HOLD = 6.0
        private const val MELD_SLIDE = 8.0
        private const val MELD_RING = 12.0
        private const val MELD_SWAP = HOLD + MELD_SLIDE
        private const val FUSE_END = MELD_SWAP + MELD_RING
        private const val OVERLOAD_SLIDE = 8.0
        private const val OVERLOAD_BURST = 15.0
        private const val BURST_AT = HOLD + OVERLOAD_BURST
        private const val ANNIHILATE_END = BURST_AT + 10
        private const val SOCKET_MOVE = 15.0

        private const val RIPPLE_WIDTH = 0.6 / 16.0
        private const val RIPPLE_SEGMENTS = 32
        private const val INK_RIPPLE = 0x2A1F3A
        private const val INK_DARK = 0x2E2240
        private const val SCROLL_GLOW = 0xFFF0C8
        // Radians per tick, counted from the start of the rise, so the scroll starts from rest.
        private const val SCROLL_TURN = 0.03
    }
}
