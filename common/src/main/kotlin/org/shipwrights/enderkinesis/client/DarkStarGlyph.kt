package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import org.joml.Matrix4f

/** The Sureibjin sky star, shared by the sky and the Crown of the Dark Star. Writes `TRIANGLES` in `POSITION_COLOR`. */
object DarkStarGlyph {

    private const val CORE_HALF = 0.55f
    /** Kept tight so the grey reads as a trim, not a border. */
    private const val OUTLINE_HALF = 0.65f
    private const val HALO_HALF = 2.8f
    private const val HALO_ALPHA = 180
    /** Darker than white so the trim does not read as a bright bezel against the dim sky. */
    private const val OUTLINE_GREY = 90

    private val HALO_PERIMETER_RU = floatArrayOf(
         1.000f,  0.000f,
         0.707f,  0.707f,
         0.000f,  1.000f,
        -0.707f,  0.707f,
        -1.000f,  0.000f,
        -0.707f, -0.707f,
         0.000f, -1.000f,
         0.707f, -0.707f,
    )

    private val CORNER_RU = floatArrayOf(
        -1f, +1f,
        +1f, +1f,
        +1f, -1f,
        -1f, -1f,
    )

    /** [rX]..[rZ] and [uX]..[uZ] are the unit right and up axes of the star plane. At [scale] 1 the core half-edge is 0.55. */
    @Suppress("LongParameterList") // Scalar axes keep the per-star sky loop free of allocation.
    fun emit(
        out: VertexConsumer,
        matrix: Matrix4f,
        cx: Float, cy: Float, cz: Float,
        rX: Float, rY: Float, rZ: Float,
        uX: Float, uY: Float, uZ: Float,
        scale: Float,
    ) {
        emitHalo(out, matrix, cx, cy, cz, rX, rY, rZ, uX, uY, uZ, scale)
        emitBody(out, matrix, cx, cy, cz, rX, rY, rZ, uX, uY, uZ, scale)
    }

    /** Draw every halo before any body when stars can overlap, or a later halo darkens an earlier outline. */
    @Suppress("LongParameterList")
    fun emitHalo(
        out: VertexConsumer,
        matrix: Matrix4f,
        cx: Float, cy: Float, cz: Float,
        rX: Float, rY: Float, rZ: Float,
        uX: Float, uY: Float, uZ: Float,
        scale: Float,
    ) {
        val halo = HALO_HALF * scale
        for (k in 0 until 8) {
            val k0 = k * 2
            val k1 = ((k + 1) and 7) * 2
            val r0 = HALO_PERIMETER_RU[k0];     val u0 = HALO_PERIMETER_RU[k0 + 1]
            val r1 = HALO_PERIMETER_RU[k1];     val u1 = HALO_PERIMETER_RU[k1 + 1]
            out.vertex(matrix, cx, cy, cz).color(0, 0, 0, HALO_ALPHA).endVertex()
            out.vertex(matrix, cx + (rX * r0 + uX * u0) * halo, cy + (rY * r0 + uY * u0) * halo, cz + (rZ * r0 + uZ * u0) * halo)
                .color(0, 0, 0, 0).endVertex()
            out.vertex(matrix, cx + (rX * r1 + uX * u1) * halo, cy + (rY * r1 + uY * u1) * halo, cz + (rZ * r1 + uZ * u1) * halo)
                .color(0, 0, 0, 0).endVertex()
        }
    }

    @Suppress("LongParameterList")
    fun emitBody(
        out: VertexConsumer,
        matrix: Matrix4f,
        cx: Float, cy: Float, cz: Float,
        rX: Float, rY: Float, rZ: Float,
        uX: Float, uY: Float, uZ: Float,
        scale: Float,
        outlineGrey: Int = OUTLINE_GREY,
    ) {
        // The grey square goes under the black core, so only its rim shows.
        emitSquare(out, matrix, cx, cy, cz, rX, rY, rZ, uX, uY, uZ, OUTLINE_HALF * scale, outlineGrey)
        emitSquare(out, matrix, cx, cy, cz, rX, rY, rZ, uX, uY, uZ, CORE_HALF * scale, 0)
    }

    @Suppress("LongParameterList")
    private fun emitSquare(
        out: VertexConsumer,
        matrix: Matrix4f,
        cx: Float, cy: Float, cz: Float,
        rX: Float, rY: Float, rZ: Float,
        uX: Float, uY: Float, uZ: Float,
        half: Float, grey: Int,
    ) {
        val cR0 = CORNER_RU[0]; val cU0 = CORNER_RU[1]
        val cR1 = CORNER_RU[2]; val cU1 = CORNER_RU[3]
        val cR2 = CORNER_RU[4]; val cU2 = CORNER_RU[5]
        val cR3 = CORNER_RU[6]; val cU3 = CORNER_RU[7]
        val xTL = cx + (rX * cR0 + uX * cU0) * half
        val yTL = cy + (rY * cR0 + uY * cU0) * half
        val zTL = cz + (rZ * cR0 + uZ * cU0) * half
        val xTR = cx + (rX * cR1 + uX * cU1) * half
        val yTR = cy + (rY * cR1 + uY * cU1) * half
        val zTR = cz + (rZ * cR1 + uZ * cU1) * half
        val xBR = cx + (rX * cR2 + uX * cU2) * half
        val yBR = cy + (rY * cR2 + uY * cU2) * half
        val zBR = cz + (rZ * cR2 + uZ * cU2) * half
        val xBL = cx + (rX * cR3 + uX * cU3) * half
        val yBL = cy + (rY * cR3 + uY * cU3) * half
        val zBL = cz + (rZ * cR3 + uZ * cU3) * half
        out.vertex(matrix, xTL, yTL, zTL).color(grey, grey, grey, 255).endVertex()
        out.vertex(matrix, xBL, yBL, zBL).color(grey, grey, grey, 255).endVertex()
        out.vertex(matrix, xBR, yBR, zBR).color(grey, grey, grey, 255).endVertex()
        out.vertex(matrix, xTL, yTL, zTL).color(grey, grey, grey, 255).endVertex()
        out.vertex(matrix, xBR, yBR, zBR).color(grey, grey, grey, 255).endVertex()
        out.vertex(matrix, xTR, yTR, zTR).color(grey, grey, grey, 255).endVertex()
    }
}
