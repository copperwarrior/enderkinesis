package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.util.FastColor
import org.joml.Matrix4f
import kotlin.math.floor
import org.shipwrights.enderkinesis.util.smoothstep

/** Straight GUI lines drawn as pencil strokes: a slow wobble, pressure in width and tone, overshoot and lifted ends. */
object SketchStroke {
    private const val SEGMENT = 4f
    private const val WOBBLE = 0.6f
    private const val WOBBLE_SPAN = 40f
    private const val WIDTH_MIN = 0.55f
    private const val WIDTH_MAX = 1.1f
    private const val OVERSHOOT_MIN = 2f
    private const val OVERSHOOT_MAX = 7f
    private const val LIFT = 8f
    private const val PRESSURE_SPAN = 23f

    /** Draws a closed rectangle as four strokes, each a little past its corners. */
    fun rectangle(graphics: GuiGraphics, left: Float, top: Float, right: Float, bottom: Float, colour: Int, alpha: Float) {
        // The shade fills are batched; draw them first so the strokes go over them.
        graphics.flush()
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        // Strokes wind both ways, by edge direction.
        RenderSystem.disableCull()
        val matrix = graphics.pose().last().pose()
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        stroke(builder, matrix, left, top, right, top, colour, alpha, 1)
        stroke(builder, matrix, right, top, right, bottom, colour, alpha, 2)
        stroke(builder, matrix, right, bottom, left, bottom, colour, alpha, 3)
        stroke(builder, matrix, left, bottom, left, top, colour, alpha, 4)
        BufferUploader.drawWithShader(builder.end())
        RenderSystem.enableCull()
        RenderSystem.disableBlend()
    }

    /** One axis-aligned stroke from ([x0], [y0]) to ([x1], [y1]). */
    @Suppress("LongParameterList") // Two points, a colour and a seed.
    private fun stroke(
        builder: BufferBuilder, matrix: Matrix4f, x0: Float, y0: Float, x1: Float, y1: Float, colour: Int, alpha: Float, seed: Int,
    ) {
        val horizontal = y0 == y1
        val from = if (horizontal) x0 else y0
        val to = if (horizontal) x1 else y1
        val across = if (horizontal) y0 else x0
        val dir = if (to >= from) 1f else -1f
        val start = from - dir * lerp(OVERSHOOT_MIN, OVERSHOOT_MAX, hash(seed, 0))
        val end = to + dir * lerp(OVERSHOOT_MIN, OVERSHOOT_MAX, hash(seed, 1))
        val length = (end - start) * dir
        val steps = maxOf(1, (length / SEGMENT).toInt())
        val r = FastColor.ARGB32.red(colour)
        val g = FastColor.ARGB32.green(colour)
        val b = FastColor.ARGB32.blue(colour)
        for (i in 0 until steps) {
            val t0 = i / steps.toFloat()
            val t1 = (i + 1) / steps.toFloat()
            val a0 = start + dir * length * t0
            val a1 = start + dir * length * t1
            quadPoint(builder, matrix, horizontal, a0, across, t0, length, seed, r, g, b, alpha, -1f)
            quadPoint(builder, matrix, horizontal, a0, across, t0, length, seed, r, g, b, alpha, 1f)
            quadPoint(builder, matrix, horizontal, a1, across, t1, length, seed, r, g, b, alpha, 1f)
            quadPoint(builder, matrix, horizontal, a1, across, t1, length, seed, r, g, b, alpha, -1f)
        }
    }

    @Suppress("LongParameterList") // One vertex of a stroke quad, with the stroke's state.
    private fun quadPoint(
        builder: BufferBuilder, matrix: Matrix4f, horizontal: Boolean, along: Float, across: Float, t: Float, length: Float,
        seed: Int, r: Int, g: Int, b: Int, alpha: Float, side: Float,
    ) {
        val s = t * length
        val pressure = noise(s / PRESSURE_SPAN, seed + 17)
        // The pencil lands and lifts: the ends thin and fade.
        val lift = minOf(smoothstep(s / LIFT), smoothstep((length - s) / LIFT))
        val width = lerp(WIDTH_MIN, WIDTH_MAX, pressure) * (0.4f + 0.6f * lift)
        val offset = (noise(s / WOBBLE_SPAN, seed) - 0.5f) * 2f * WOBBLE + side * width / 2f
        val a = (alpha * (0.55f + 0.45f * pressure) * lift * 255f).toInt().coerceIn(0, 255)
        val x = if (horizontal) along else across + offset
        val y = if (horizontal) across + offset else along
        builder.vertex(matrix, x, y, 0f).color(r, g, b, a).endVertex()
    }

    /** Smooth value noise in 0..1. */
    private fun noise(x: Float, seed: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        return lerp(hash(seed, i), hash(seed, i + 1), f * f * (3f - 2f * f))
    }

    private fun hash(seed: Int, i: Int): Float {
        var h = seed * 374761393 + i * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFFFF) / 0xFFFFFF.toFloat()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
