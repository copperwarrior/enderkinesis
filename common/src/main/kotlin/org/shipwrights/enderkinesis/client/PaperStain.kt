package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager.DestFactor
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.item.InkBleed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Colors the page around colored text in soft blobs, with the weights that [InkBleed] gives the text near it. */
object PaperStain {

    private const val MAX_LINES = 14
    private const val GLYPH_HEIGHT = 8

    // The strength of one blob center. About three blobs overlap on the text row.
    private const val MAX_ALPHA = 0x14
    private const val BLOB_SPACING = 5
    private const val BLOB_SIDES = 12
    private const val BLOB_RADIUS_X = 10.0f
    private const val BLOB_RADIUS_Y = 8.0f
    private const val BLOB_RADIUS_RANGE = 3.0f
    private const val BLOB_DRIFT = 2.5f
    private const val SALT_X = 0x6B1
    private const val SALT_Y = 0x2D7
    private const val SALT_RADIUS_X = 0x4F3
    private const val SALT_RADIUS_Y = 0x19E

    private val RIM_X = FloatArray(BLOB_SIDES) { cos(2.0 * PI * it / BLOB_SIDES).toFloat() }
    private val RIM_Y = FloatArray(BLOB_SIDES) { sin(2.0 * PI * it / BLOB_SIDES).toFloat() }
    private const val ALPHA_SHIFT = 24
    private const val CHANNEL_MAX = 0xFF
    private const val OPAQUE = 0xFF shl ALPHA_SHIFT
    private const val NO_CHANGE = -1
    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
    private const val FIELDS = 3

    /**
     * The stains of each line: the line width, then a start x, an end x and an ARGB color for each stain.
     * The x values are relative to the line start.
     */
    @JvmStatic
    fun build(font: Font, lines: List<FormattedCharSequence>): Array<IntArray> =
        Array(minOf(lines.size, MAX_LINES)) { stains(font, lines[it]) }

    private fun stains(font: Font, line: FormattedCharSequence): IntArray {
        val out = IntArrayList()
        out.add(font.width(line))
        var x = 0
        var runStart = 0
        var runColor = 0
        line.accept { _, style, codePoint ->
            val color = stainColor(style)
            if (color != runColor) {
                if (runColor != 0) out.addAll(listOf(runStart, x, runColor))
                runStart = x
                runColor = color
            }
            x += font.width(FormattedCharSequence.codepoint(codePoint, style))
            true
        }
        if (runColor != 0) out.addAll(listOf(runStart, x, runColor))
        return out.toIntArray()
    }

    /** The ARGB stain below a glyph, or 0. A color without a weight is the colored run itself. */
    private fun stainColor(style: Style): Int {
        val color = style.color?.value ?: return 0
        if (!SloppyText.isSloppy(style)) return 0
        val weight = (color ushr InkBleed.WEIGHT_SHIFT).let { if (it == 0) InkBleed.WEIGHT_MAX else it }
        return MAX_ALPHA * weight / InkBleed.WEIGHT_MAX shl ALPHA_SHIFT or (color and InkBleed.RGB_MASK)
    }

    /** [pageX] is the vanilla book image origin. Draw this before the text. */
    @JvmStatic
    fun draw(graphics: GuiGraphics, skin: BookSkin, lines: Array<IntArray>, pageX: Int) {
        if (lines.none { it.size > 1 }) return
        graphics.flush()
        // Drawn directly: a RenderType sets its own blend function.
        val buffer = Tesselator.getInstance().builder
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        val pose = graphics.pose().last().pose()
        for ((row, stains) in lines.withIndex()) {
            if (stains.size == 1) continue
            val y = BookSkin.TEXT_Y + row * BookSkin.LINE_HEIGHT
            val left = skin.lineLeft(pageX + BookSkin.TEXT_X, y, stains[0])
            for (i in 1 until stains.size step FIELDS) {
                var x = left + stains[i]
                // Bounded by the page width: a line is at most 114 px.
                while (x < left + stains[i + 1]) {
                    drawBlob(buffer, pose, x.toFloat(), y + GLYPH_HEIGHT / 2.0f, stains[i + 2])
                    x += BLOB_SPACING
                }
            }
        }
        // Multiply: the page color times the vertex color, and the alpha of the page stays.
        RenderSystem.enableBlend()
        RenderSystem.blendFuncSeparate(SourceFactor.DST_COLOR, DestFactor.ZERO, SourceFactor.ZERO, DestFactor.ONE)
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        BufferUploader.drawWithShader(buffer.end())
        RenderSystem.defaultBlendFunc()
    }

    /** White changes nothing in a multiply blend, so the strength of a stain is its distance from white. */
    private fun multiplyColor(color: Int): Int {
        val strength = color ushr ALPHA_SHIFT
        var out = OPAQUE
        for (shift in CHANNEL_SHIFTS) {
            val channel = color shr shift and CHANNEL_MAX
            out = out or (CHANNEL_MAX - (CHANNEL_MAX - channel) * strength / CHANNEL_MAX shl shift)
        }
        return out
    }

    /** A soft ellipse: [color] in the center and no change on the rim. The hash of the position gives its shape. */
    private fun drawBlob(buffer: VertexConsumer, pose: Matrix4f, x: Float, y: Float, color: Int) {
        val centerX = x + SloppyText.unit(x, y, SALT_X) * BLOB_DRIFT
        val centerY = y + SloppyText.unit(x, y, SALT_Y) * BLOB_DRIFT
        val radiusX = BLOB_RADIUS_X + SloppyText.unit(x, y, SALT_RADIUS_X) * BLOB_RADIUS_RANGE
        val radiusY = BLOB_RADIUS_Y + SloppyText.unit(x, y, SALT_RADIUS_Y) * BLOB_RADIUS_RANGE
        val center = multiplyColor(color)
        for (i in 0 until BLOB_SIDES) {
            val next = (i + 1) % BLOB_SIDES
            // The GUI culls, so the order agrees with GuiGraphics.fill.
            buffer.vertex(pose, centerX, centerY, 0.0f).color(center).endVertex()
            buffer.vertex(pose, centerX, centerY, 0.0f).color(center).endVertex()
            rimVertex(buffer, pose, centerX + RIM_X[next] * radiusX, centerY + RIM_Y[next] * radiusY)
            rimVertex(buffer, pose, centerX + RIM_X[i] * radiusX, centerY + RIM_Y[i] * radiusY)
        }
    }

    private fun rimVertex(buffer: VertexConsumer, pose: Matrix4f, x: Float, y: Float) {
        buffer.vertex(pose, x, y, 0.0f).color(NO_CHANGE).endVertex()
    }
}
