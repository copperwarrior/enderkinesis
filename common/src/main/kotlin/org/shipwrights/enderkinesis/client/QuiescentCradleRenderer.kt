package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.core.Direction
import org.joml.Matrix4f
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Vector3fc
import org.shipwrights.enderkinesis.block.QuiescentCradleBlock
import org.shipwrights.enderkinesis.block.QuiescentCradleFrame
import org.shipwrights.enderkinesis.blockentity.QuiescentCradleBlockEntity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class QuiescentCradleRenderer(
    @Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<QuiescentCradleBlockEntity> {

    // Render thread only.
    private val lerped = DoubleArray(QuiescentCradleFrame.RINGS)
    private val frameD = Quaterniond()
    private val frameF = Quaternionf()
    private val anchor = Vector3d()
    private val coords = FloatArray(3)
    private val normal = FloatArray(3)
    private val cubeCentre = FloatArray(3)
    private val bandLayouts = arrayOfNulls<QuiescentCradleGlyphs.CircleLayout>(QuiescentCradleFrame.RINGS)
    private val tangent = Vector3f()
    private val up = Vector3f()
    private val radial = Vector3f()
    private val centre = Vector3f()
    private val spinAxis = Vector3f()
    private val spin = Quaternionf()

    override fun render(
        cradle: QuiescentCradleBlockEntity, partialTick: Float, poseStack: PoseStack,
        buffers: MultiBufferSource, packedLight: Int, packedOverlay: Int,
    ) {
        val view = cradle.view ?: return
        val state = cradle.blockState
        if (state.block !is QuiescentCradleBlock) return
        val facing = state.getValue(QuiescentCradleBlock.FACING)
        for (ring in lerped.indices) {
            lerped[ring] = view.prevAngles[ring] +
                QuiescentCradleFrame.wrap(view.angles[ring] - view.prevAngles[ring]) * partialTick
        }
        drawOrrery(view, facing, poseStack, buffers, packedLight)
        if (SpellVfxRenderTypes.isReady()) {
            val time = (((cradle.level?.gameTime ?: 0L) % TIME_WRAP_TICKS) + partialTick) / TICKS_PER_SECOND
            drawCircles(cradle, view, facing, time, partialTick, poseStack, buffers)
        }
    }

    private fun ringFrame(ring: Int, facing: Direction): Quaternionf {
        QuiescentCradleFrame.ringFrame(ring, facing, lerped, frameD)
        return frameF.set(frameD.x.toFloat(), frameD.y.toFloat(), frameD.z.toFloat(), frameD.w.toFloat())
    }

    private fun drawOrrery(
        view: QuiescentCradleView, facing: Direction, poseStack: PoseStack, buffers: MultiBufferSource, light: Int,
    ) {
        QuiescentCradleFrame.orreryCentre(facing, anchor)
        // One render type at a time: fetching the next one ends the batch of the last.
        val iron = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE))
        for (ring in 0 until QuiescentCradleFrame.RINGS) {
            pushRing(poseStack, ring, facing)
            val radius = QuiescentCradleFrame.ORRERY_RADII[ring].toFloat()
            band(iron, poseStack.last(), ring, radius, light)
            if (ring == view.hoverRing || ring == view.dragRing) {
                knob(iron, poseStack.last(), ring, radius, view.knobAngle.toFloat(), light)
            }
            if (ring == QuiescentCradleFrame.ROLL) {
                // The ship marker resets the gimbal on a click; it lightens a little under the crosshair.
                cubeCentre.fill(0f)
                val lift = max(if (view.markerHover) MARKER_HOVER_LIFT else 0f, view.markerFlash * MARKER_FLASH_LIFT)
                cube(iron, poseStack.last(), MARKER_HALF, towardWhite(MARKER_RGB, lift), light)
            }
            poseStack.popPose()
        }
        if (SpellVfxRenderTypes.isReady()) {
            val marks = buffers.getBuffer(SpellVfxRenderTypes.sprite(QuiescentCradleGlyphs.TEXTURE))
            val glyphs = QuiescentCradleGlyphs.glyphs()
            for (ring in 0 until QuiescentCradleFrame.RINGS) {
                // The colour is the feedback: faint carvings at rest, lit in the ring colour under the crosshair or turning.
                val active = if (ring == view.hoverRing || ring == view.dragRing) 1f else view.glow[ring]
                val rgb = shade(COLOURS[ring], CARVED_LEVEL + (1f - CARVED_LEVEL) * min(1f, active))
                pushRing(poseStack, ring, facing)
                bandGlyphs(marks, poseStack.last().pose(), ring, bandLayout(ring, glyphs), rgb)
                poseStack.popPose()
            }
        }

    }

    /** Pushes a pose at the orrery centre in the frame of [ring]; the caller pops it. */
    private fun pushRing(poseStack: PoseStack, ring: Int, facing: Direction) {
        poseStack.pushPose()
        poseStack.translate(anchor.x, anchor.y, anchor.z)
        poseStack.mulPose(ringFrame(ring, facing))
    }

    /** A short open cylinder of dark iron, lit by the world like a block. */
    private fun band(buffer: VertexConsumer, pose: PoseStack.Pose, ring: Int, radius: Float, light: Int) {
        for (segment in 0 until RING_SEGMENTS) {
            val a0 = (2.0 * PI * segment / RING_SEGMENTS).toFloat()
            val a1 = (2.0 * PI * (segment + 1) / RING_SEGMENTS).toFloat()
            bandVertex(buffer, pose, ring, radius, a0, -BAND_HALF_WIDTH, light)
            bandVertex(buffer, pose, ring, radius, a1, -BAND_HALF_WIDTH, light)
            bandVertex(buffer, pose, ring, radius, a1, BAND_HALF_WIDTH, light)
            bandVertex(buffer, pose, ring, radius, a0, BAND_HALF_WIDTH, light)
        }
    }

    private fun bandVertex(buffer: VertexConsumer, pose: PoseStack.Pose, ring: Int, radius: Float, angle: Float, axial: Float, light: Int) {
        val c = cos(angle)
        val s = sin(angle)
        coords[QuiescentCradleFrame.planeU(ring)] = radius * c
        coords[QuiescentCradleFrame.planeV(ring)] = radius * s
        coords[ring] = axial
        normal[QuiescentCradleFrame.planeU(ring)] = c
        normal[QuiescentCradleFrame.planeV(ring)] = s
        normal[ring] = 0f
        solidVertex(buffer, pose, IRON_RGB, light)
    }

    /** The grip knob: a small cube on the band where the player took hold; it sits in the ring frame, so it turns with it. */
    private fun knob(buffer: VertexConsumer, pose: PoseStack.Pose, ring: Int, radius: Float, angle: Float, light: Int) {
        cubeCentre.fill(0f)
        cubeCentre[QuiescentCradleFrame.planeU(ring)] = radius * cos(angle)
        cubeCentre[QuiescentCradleFrame.planeV(ring)] = radius * sin(angle)
        cube(buffer, pose, KNOB_HALF, COLOURS[ring], light)
    }

    /** A lit cube about [cubeCentre] with half sizes [half], in the current ring frame. */
    private fun cube(buffer: VertexConsumer, pose: PoseStack.Pose, half: FloatArray, rgb: Int, light: Int) {
        for (index in BOX_FACES.indices) {
            val face = BOX_FACES[index]
            normal.fill(0f)
            normal[index / 2] = if (index % 2 == 0) -1f else 1f
            for (corner in face) {
                for (a in 0 until 3) coords[a] = cubeCentre[a] + if (corner shr a and 1 == 0) -half[a] else half[a]
                solidVertex(buffer, pose, rgb, light)
            }
        }
    }

    /** One vertex of [coords] with [normal], in the entity format: lit and shaded like a block face. */
    private fun solidVertex(buffer: VertexConsumer, pose: PoseStack.Pose, rgb: Int, light: Int) {
        buffer.vertex(pose.pose(), coords[0], coords[1], coords[2])
            .color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 0xFF)
            .uv(HALF_UV, HALF_UV).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
            .normal(pose.normal(), normal[0], normal[1], normal[2]).endVertex()
    }

    private fun bandLayout(ring: Int, glyphs: Array<QuiescentCradleGlyphs.Glyph>): QuiescentCradleGlyphs.CircleLayout {
        val current = bandLayouts[ring]
        if (current != null && current.glyphs === glyphs) return current
        val radius = QuiescentCradleFrame.ORRERY_RADII[ring]
        return QuiescentCradleGlyphs.layout(radius, SEEDS[ring], glyphs, BAND_FONT_PX).also { bandLayouts[ring] = it }
    }

    /** The big circles' words in miniature, on both faces of the band, letter height about the band width. */
    private fun bandGlyphs(buffer: VertexConsumer, matrix: Matrix4f, ring: Int, layout: QuiescentCradleGlyphs.CircleLayout, rgb: Int) {
        val axes = PLANE_AXES[ring]
        val px = BAND_FONT_PX.toFloat()
        for (side in BAND_SIDES) {
            val radius = layout.radius.toFloat() + side
            for (n in 0 until layout.count) {
                val glyph = layout.glyphs[layout.letters[n]]
                val a = layout.angles[n]
                radial.set(axes[0]).mul(cos(a)).fma(sin(a), axes[1])
                tangent.set(axes[1]).mul(cos(a)).fma(-sin(a), axes[0])
                up.set(axes[2])
                centre.set(radial).mul(radius).fma(glyph.centreY * px, up)
                letter(buffer, matrix, glyph, px, rgb)
            }
        }
    }

    private fun drawCircles(
        cradle: QuiescentCradleBlockEntity, view: QuiescentCradleView, facing: Direction,
        time: Float, partialTick: Float, poseStack: PoseStack, buffers: MultiBufferSource,
    ) {
        val glyphs = QuiescentCradleGlyphs.glyphs()
        val buffer = buffers.getBuffer(SpellVfxRenderTypes.glow(QuiescentCradleGlyphs.TEXTURE))
        val pitchRadius = shown(view, QuiescentCradleFrame.PITCH, partialTick)
        QuiescentCradleFrame.holdCentre(facing, pitchRadius, anchor)
        val open = view.prevOpen + (view.open - view.prevOpen) * partialTick
        val loose = view.prevLooseness + (view.looseness - view.prevLooseness) * partialTick
        val turning = cradle.holding && !cradle.locked
        for (ring in 0 until QuiescentCradleFrame.RINGS) {
            val layout = layoutFor(view, ring, QuiescentCradleFrame.circleRadius(ring, cradle.circleSphere), glyphs)
            val radius = shown(view, ring, partialTick)
            // The pitch circle stays seated in the block; the inner two open, but stay inside it.
            val opened = if (ring == QuiescentCradleFrame.PITCH) 1.0 else min(open.toDouble(), (pitchRadius - 1.0) / radius)
            val glow = view.glow[ring] + if (turning) TURNING_GLOW else 0f
            val level = when {
                !cradle.holding -> RELEASED_LEVEL
                cradle.locked -> LOCKED_LEVEL + glow * LOCKED_GLOW_GAIN
                else -> TURNING_LEVEL + glow
            }
            val letterLevel = max(MIN_LETTER_LEVEL, level * LETTER_LEVEL)
            val blend = view.prevBlend[ring] + (view.blend[ring] - view.prevBlend[ring]) * partialTick
            poseStack.pushPose()
            poseStack.translate(anchor.x, anchor.y, anchor.z)
            poseStack.mulPose(ringFrame(ring, facing))
            drawScaled(buffer, poseStack, ring, layout, (radius * opened / layout.radius).toFloat(), time, letterLevel * blend, loose)
            view.oldLayouts[ring]?.let {
                drawScaled(buffer, poseStack, ring, it, (radius * opened / it.radius).toFloat(), time, letterLevel * (1f - blend), loose)
            }
            poseStack.popPose()
        }
    }

    private fun shown(view: QuiescentCradleView, ring: Int, partialTick: Float): Double =
        view.prevShownRadius[ring] + (view.shownRadius[ring] - view.prevShownRadius[ring]) * partialTick

    /** Draws [layout] scaled by [scale], which eases its radius to the shown one; the letters scale with it, as in the demo. */
    @Suppress("LongParameterList") // One circle's draw state.
    private fun drawScaled(
        buffer: VertexConsumer, poseStack: PoseStack, ring: Int, layout: QuiescentCradleGlyphs.CircleLayout,
        scale: Float, time: Float, level: Float, loose: Float,
    ) {
        if (level <= MIN_DRAWN_LEVEL) return
        poseStack.pushPose()
        poseStack.scale(scale, scale, scale)
        drawLetters(buffer, poseStack.last().pose(), ring, layout, time, level, loose)
        poseStack.popPose()
    }

    private fun layoutFor(
        view: QuiescentCradleView, ring: Int, radius: Double, glyphs: Array<QuiescentCradleGlyphs.Glyph>,
    ): QuiescentCradleGlyphs.CircleLayout {
        val current = view.layouts[ring]
        if (current != null && current.radius == radius && current.glyphs === glyphs) return current
        if (current != null && current.radius != radius) {
            view.oldLayouts[ring] = current
            view.startBlend(ring)
        }
        return QuiescentCradleGlyphs.layout(radius, SEEDS[ring], glyphs).also { view.layouts[ring] = it }
    }

    private fun drawLetters(
        buffer: VertexConsumer, matrix: Matrix4f, ring: Int, layout: QuiescentCradleGlyphs.CircleLayout,
        time: Float, level: Float, loose: Float,
    ) {
        val px = QuiescentCradleGlyphs.FONT_PX.toFloat()
        val axes = PLANE_AXES[ring]
        val radius = layout.radius.toFloat()
        val rgb = COLOURS[ring]
        for (n in 0 until layout.count) {
            val glyph = layout.glyphs[layout.letters[n]]
            val l = n * LOOSE_VALUES
            val r = layout.loose
            // Released letters wander along the circle, out, up and down, and slowly tumble.
            val a = layout.angles[n] + loose * (r[l] * 0.06f + sin(time * 0.3f + r[l + 1] * 6f) * 0.03f)
            val out = loose * (r[l + 2] * 1.4f + sin(time * 0.5f + r[l + 3] * 5f) * 0.35f)
            val lift = loose * (r[l + 4] * 1.6f + sin(time * 0.4f + r[l + 5] * 4f) * 0.4f)
            val wobble = sin(time * 2f + n * 1.3f) * 0.6f * px * (1f + loose * 3f)
            val ca = cos(a)
            val sa = sin(a)
            radial.set(axes[0]).mul(ca).fma(sa, axes[1])
            tangent.set(axes[1]).mul(ca).fma(-sa, axes[0])
            up.set(axes[2])
            centre.set(radial).mul(radius + out).fma(lift + wobble + glyph.centreY * px, up)
            if (loose > LOOSE_EPSILON) {
                spinAxis.set(tangent).mul(r[l + 1]).fma(r[l + 3], up).fma(r[l + 5], radial)
                if (spinAxis.lengthSquared() > LOOSE_EPSILON) {
                    spin.rotationAxis(loose * (r[l] * 1.2f + time * 0.25f * r[l + 2]), spinAxis.normalize())
                    spin.transform(tangent)
                    spin.transform(up)
                }
            }
            val wave = WAVE_FLOOR + WAVE_PEAK * max(0f, cos(a - time * WAVE_SPEED)).pow(WAVE_SHARPNESS)
            letter(buffer, matrix, glyph, px, shade(rgb, min(MAX_LEVEL, level * wave)))
        }
    }

    /** One letter quad about [centre], wide along [tangent], tall along [up]. Read from outside, it runs with the angle. */
    private fun letter(buffer: VertexConsumer, matrix: Matrix4f, glyph: QuiescentCradleGlyphs.Glyph, px: Float, rgb: Int) {
        val hw = glyph.width * px * 0.5f
        val hh = glyph.height * px * 0.5f
        letterVertex(buffer, matrix, -hw, -hh, glyph.u0, glyph.v1, rgb)
        letterVertex(buffer, matrix, hw, -hh, glyph.u1, glyph.v1, rgb)
        letterVertex(buffer, matrix, hw, hh, glyph.u1, glyph.v0, rgb)
        letterVertex(buffer, matrix, -hw, hh, glyph.u0, glyph.v0, rgb)
    }

    private fun letterVertex(buffer: VertexConsumer, matrix: Matrix4f, along: Float, across: Float, u: Float, v: Float, rgb: Int) {
        buffer.vertex(
            matrix,
            centre.x + tangent.x * along + up.x * across,
            centre.y + tangent.y * along + up.y * across,
            centre.z + tangent.z * along + up.z * across,
        ).uv(u, v).color((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, 0xFF)
            .uv2(LightTexture.FULL_BRIGHT).endVertex()
    }

    override fun shouldRenderOffScreen(cradle: QuiescentCradleBlockEntity): Boolean = true

    override fun getViewDistance(): Int = VIEW_DISTANCE

    private companion object {
        const val TIME_WRAP_TICKS = 24_000L
        const val TICKS_PER_SECOND = 20f
        const val VIEW_DISTANCE = 256

        /** Pitch gold, yaw blue, roll violet. */
        val COLOURS = intArrayOf(0xF0C968, 0x7FB0FF, 0xC0A6FF)
        val SEEDS = intArrayOf(2, 1, 3)

        /** Per ring: the in-plane axes u and v, then the ring axis, as in [QuiescentCradleFrame.planeU]. */
        val PLANE_AXES: Array<Array<Vector3fc>> = Array(QuiescentCradleFrame.RINGS) { ring ->
            arrayOf(
                unit(QuiescentCradleFrame.planeU(ring)),
                unit(QuiescentCradleFrame.planeV(ring)),
                unit(ring),
            )
        }

        fun unit(index: Int): Vector3fc = Vector3f(
            if (index == 0) 1f else 0f, if (index == 1) 1f else 0f, if (index == 2) 1f else 0f,
        )

        val WHITE = ResourceLocation("minecraft", "textures/misc/white.png")
        const val HALF_UV = 0.5f
        const val RING_SEGMENTS = 48
        const val IRON_RGB = 0x3A3A40

        /** Band width 0.026 block; a 7-pixel letter of [BAND_FONT_PX] is about 75 % of it, so iron shows at both edges. */
        const val BAND_HALF_WIDTH = 0.013f
        const val BAND_FONT_PX = 0.0028

        /** The glyphs sit just off both faces of the band, so they do not flicker on it. */
        val BAND_SIDES = floatArrayOf(0.0008f, -0.0008f)

        /** Glyph colour at rest, as a part of the ring colour. */
        const val CARVED_LEVEL = 0.3f
        val KNOB_HALF = floatArrayOf(0.014f, 0.014f, 0.014f)

        val MARKER_HALF = FloatArray(3) { QuiescentCradleFrame.MARKER_HALF[it].toFloat() }
        const val MARKER_RGB = 0xC89AD8

        /** How far the marker goes toward white: under the crosshair, and just after a reset. */
        const val MARKER_HOVER_LIFT = 0.2f
        const val MARKER_FLASH_LIFT = 0.45f

        /** [rgb] moved [amount] of the way to white. */
        fun towardWhite(rgb: Int, amount: Float): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return ((r + ((255 - r) * amount).toInt()) shl 16) or ((g + ((255 - g) * amount).toInt()) shl 8) or
                (b + ((255 - b) * amount).toInt())
        }

        /** Corner indexes (bit 0 x, bit 1 y, bit 2 z) of the six faces. */
        val BOX_FACES = arrayOf(
            intArrayOf(0, 2, 6, 4), intArrayOf(1, 5, 7, 3),
            intArrayOf(0, 4, 5, 1), intArrayOf(2, 3, 7, 6),
            intArrayOf(0, 1, 3, 2), intArrayOf(4, 6, 7, 5),
        )

        // Brightness, from the approved demo.
        const val RELEASED_LEVEL = 0.35f
        const val LOCKED_LEVEL = 1.1f
        const val LOCKED_GLOW_GAIN = 0.6f
        const val TURNING_LEVEL = 1.5f
        const val TURNING_GLOW = 0.25f
        const val LETTER_LEVEL = 0.8f
        const val MIN_LETTER_LEVEL = 0.3f
        const val MAX_LEVEL = 1.6f
        const val MIN_DRAWN_LEVEL = 0.004f
        const val WAVE_FLOOR = 0.4f
        const val WAVE_PEAK = 0.75f
        const val WAVE_SPEED = 1.2f
        const val WAVE_SHARPNESS = 6f

        const val LOOSE_VALUES = 6
        const val LOOSE_EPSILON = 0.001f

        /** [rgb] times [level], each channel clamped. */
        fun shade(rgb: Int, level: Float): Int {
            val r = min(255, (((rgb shr 16) and 0xFF) * level).toInt())
            val g = min(255, (((rgb shr 8) and 0xFF) * level).toInt())
            val b = min(255, ((rgb and 0xFF) * level).toInt())
            return (r shl 16) or (g shl 8) or b
        }
    }
}
