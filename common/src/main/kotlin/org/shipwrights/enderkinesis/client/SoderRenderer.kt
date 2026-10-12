package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.EnderkinesisMod

/** "Tribulla" easter-egg renderer. When the captured entity's
 *  display name is "Tribulla", the cage swaps its hex bipyramid for
 *  a soda-bottle model — 6 axis-aligned boxes ported from a
 *  Blockbench export.
 *
 *  Render order is deliberate so depth blends correctly:
 *   1. Body-label texture sides (writes depth) — drawn FIRST so the
 *      translucent orange can blend onto its pixels.
 *   2. Orange outline-gradient pass for base / shoulder / neck and the
 *      body box top + bottom caps — same outlined-glow shape the hex
 *      crystal uses (`emitStrip` from face edge inset to centroid).
 *   3. White cap collar + cap top — translucent + depth-write, same
 *      render-type behaviour as the body label so they integrate
 *      cleanly against terrain and the rest of the bottle.
 *   4. Line outlines on every orange box edge — the second half of
 *      the "outline-gradient" combo (matches the crystal's lines pass).
 *
 *  Coordinate convention: input box dims are taken verbatim from
 *  Blockbench (pixel units, -Y up). Conversion to world space (block
 *  units, +Y up) plus centring happens inside the emit helpers. The
 *  centring offset places the BODY-LABEL BOX'S CENTRE at the body
 *  origin so the captured entity, rendered at its bbox centre, lands
 *  inside the label area. */
object SoderRenderer {

    private val SODER_TEXTURE = EnderkinesisMod.id("textures/entity/soder_wrap.png")
    private val SODER_WRAP_TYPE: RenderType = RenderType.entityTranslucent(SODER_TEXTURE)

    /** Vanilla block-destroy texture stages — overlaid on each box face
     *  when the bottle has taken damage (see [renderCrumble]). Indexed
     *  0..9 by damage stage. */
    private val CRUMBLE_TYPES: List<RenderType> = (0 until 10).map { stage ->
        RenderType.crumbling(ResourceLocation("textures/block/destroy_stage_$stage.png"))
    }

    private const val PX_PER_BLOCK: Float = 16f
    /** Model-pixel offset that puts the BODY-LABEL BOX'S CENTRE at the
     *  body origin. Body box model-Y centre is `-7.5`. Must match
     *  [org.shipwrights.enderkinesis.body.SoderShape]. */
    private const val Y_CENTRE_PX: Float = 7.5f

    // #f46d2d — translucent tinted-glass colour for the orange parts.
    private const val ORANGE_R: Int = 244
    private const val ORANGE_G: Int = 109
    private const val ORANGE_B: Int = 45

    // #f2f3f0 — cap off-white.
    private const val OFFWHITE_R: Int = 242
    private const val OFFWHITE_G: Int = 243
    private const val OFFWHITE_B: Int = 240

    /** Outer face alpha at full fade (translucent ramp). Matches the
     *  crystal's GLOW_OUTER_ALPHA so the bottle reads as the same
     *  "tinted-glass-with-glowing-outline" surface treatment. */
    private const val GLOW_OUTER_ALPHA: Float = 200f
    /** Strip inset fraction toward face centroid for the gradient —
     *  matches GamylanCageRenderer's GLOW_INSET. */
    private const val GLOW_INSET: Float = 0.5f

    fun render(poseStack: PoseStack, buffer: MultiBufferSource, fade: Float) {
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()
        val fadeClamp = fade.coerceIn(0f, 1f)
        val texAlpha = (fadeClamp * 255f).toInt().coerceIn(0, 255)
        val faceAlpha = (fadeClamp * GLOW_OUTER_ALPHA).toInt().coerceIn(0, 255)
        val lineAlpha = (fadeClamp * 255f).toInt().coerceIn(0, 255)

        // Emits MUST stay batched by render type. MultiBufferSource's
        // fallback BufferBuilder reformats itself every time getBuffer()
        // is called with a render type different from the last; cached
        // references then point at a reformatted builder. Do all writes
        // to one render type before requesting the next.

        // --- 1. Textured body label (4 side faces of body box) ---
        val tex = buffer.getBuffer(SODER_WRAP_TYPE)
        emitTextureSides(tex, matrix, normal,
            -2.4f, -10.9f, -2.4f, 4.8f, 6.8f, 4.8f,
            texAlpha)

        // --- 2. Outline-gradient faces — orange (base/shoulder/neck +
        //        body box top+bottom) AND off-white caps. Everything
        //        flows through the same FACE_GLOW batch so the inset
        //        glow style is consistent across the whole bottle. ---
        val gradient = buffer.getBuffer(GamylanCageRenderType.FACE_GLOW)
        emitBoxGradient(gradient, matrix,
            -3f, -4f, -3f, 6f, 4f, 6f,
            ORANGE_R, ORANGE_G, ORANGE_B, faceAlpha)
        emitBoxGradient(gradient, matrix,
            -3f, -13f, -3f, 6f, 2f, 6f,
            ORANGE_R, ORANGE_G, ORANGE_B, faceAlpha)
        emitBoxGradient(gradient, matrix,
            -2f, -15f, -2f, 4f, 2f, 4f,
            ORANGE_R, ORANGE_G, ORANGE_B, faceAlpha)
        // Body box top + bottom (sides are textured)
        emitBoxCapsGradient(gradient, matrix,
            -2.4f, -10.9f, -2.4f, 4.8f, 6.8f, 4.8f,
            ORANGE_R, ORANGE_G, ORANGE_B, faceAlpha)
        // Cap collar + cap top — same gradient style, off-white colour.
        emitBoxGradient(gradient, matrix,
            -0.9f, -15.6f, -0.9f, 1.8f, 0.8f, 1.8f,
            OFFWHITE_R, OFFWHITE_G, OFFWHITE_B, faceAlpha)
        emitBoxGradient(gradient, matrix,
            -1.2f, -16.95f, -1.2f, 2.4f, 1.4f, 2.4f,
            OFFWHITE_R, OFFWHITE_G, OFFWHITE_B, faceAlpha)

        // --- 3. Line outlines for every box (all 12 edges each) ---
        val lines = buffer.getBuffer(GamylanCageRenderType.LINES)
        emitBoxLines(lines, matrix, normal,
            -3f, -4f, -3f, 6f, 4f, 6f,
            ORANGE_R, ORANGE_G, ORANGE_B, lineAlpha)
        emitBoxLines(lines, matrix, normal,
            -3f, -13f, -3f, 6f, 2f, 6f,
            ORANGE_R, ORANGE_G, ORANGE_B, lineAlpha)
        emitBoxLines(lines, matrix, normal,
            -2f, -15f, -2f, 4f, 2f, 4f,
            ORANGE_R, ORANGE_G, ORANGE_B, lineAlpha)
        // Body box outline deliberately skipped — the texture is the
        // label, an outlined frame around it would frame the label like
        // a sticker and break the bottle illusion.
        emitBoxLines(lines, matrix, normal,
            -0.9f, -15.6f, -0.9f, 1.8f, 0.8f, 1.8f,
            OFFWHITE_R, OFFWHITE_G, OFFWHITE_B, lineAlpha)
        emitBoxLines(lines, matrix, normal,
            -1.2f, -16.95f, -1.2f, 2.4f, 1.4f, 2.4f,
            OFFWHITE_R, OFFWHITE_G, OFFWHITE_B, lineAlpha)
    }

    /** Six gradient quads (one per box face), each emitted as four
     *  inset strips that fade from full alpha at the edge to zero at
     *  the face centroid — same outlined-glow look as the hex crystal. */
    private fun emitBoxGradient(
        v: VertexConsumer, m: Matrix4f,
        bx: Float, by: Float, bz: Float, sx: Float, sy: Float, sz: Float,
        r: Int, g: Int, b: Int, alpha: Int,
    ) {
        val x0 = bx / PX_PER_BLOCK
        val x1 = (bx + sx) / PX_PER_BLOCK
        val wy0 = (-(by + sy) - Y_CENTRE_PX) / PX_PER_BLOCK
        val wy1 = (-by - Y_CENTRE_PX) / PX_PER_BLOCK
        val z0 = bz / PX_PER_BLOCK
        val z1 = (bz + sz) / PX_PER_BLOCK
        // -Y
        emitQuadGradient(v, m,
            x0, wy0, z0, x1, wy0, z0, x1, wy0, z1, x0, wy0, z1,
            r, g, b, alpha)
        // +Y
        emitQuadGradient(v, m,
            x0, wy1, z1, x1, wy1, z1, x1, wy1, z0, x0, wy1, z0,
            r, g, b, alpha)
        // -Z
        emitQuadGradient(v, m,
            x1, wy0, z0, x0, wy0, z0, x0, wy1, z0, x1, wy1, z0,
            r, g, b, alpha)
        // +Z
        emitQuadGradient(v, m,
            x0, wy0, z1, x1, wy0, z1, x1, wy1, z1, x0, wy1, z1,
            r, g, b, alpha)
        // -X
        emitQuadGradient(v, m,
            x0, wy0, z0, x0, wy0, z1, x0, wy1, z1, x0, wy1, z0,
            r, g, b, alpha)
        // +X
        emitQuadGradient(v, m,
            x1, wy0, z1, x1, wy0, z0, x1, wy1, z0, x1, wy1, z1,
            r, g, b, alpha)
    }

    /** Top + bottom (+Y / -Y) gradient quads only — used for the body
     *  box where the sides are textured. */
    private fun emitBoxCapsGradient(
        v: VertexConsumer, m: Matrix4f,
        bx: Float, by: Float, bz: Float, sx: Float, sy: Float, sz: Float,
        r: Int, g: Int, b: Int, alpha: Int,
    ) {
        val x0 = bx / PX_PER_BLOCK
        val x1 = (bx + sx) / PX_PER_BLOCK
        val wy0 = (-(by + sy) - Y_CENTRE_PX) / PX_PER_BLOCK
        val wy1 = (-by - Y_CENTRE_PX) / PX_PER_BLOCK
        val z0 = bz / PX_PER_BLOCK
        val z1 = (bz + sz) / PX_PER_BLOCK
        emitQuadGradient(v, m,
            x0, wy0, z0, x1, wy0, z0, x1, wy0, z1, x0, wy0, z1,
            r, g, b, alpha)
        emitQuadGradient(v, m,
            x0, wy1, z1, x1, wy1, z1, x1, wy1, z0, x0, wy1, z0,
            r, g, b, alpha)
    }

    private fun emitQuadGradient(
        v: VertexConsumer, m: Matrix4f,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        r: Int, g: Int, b: Int, alpha: Int,
    ) {
        val centroX = (ax + bx + cx + dx) * 0.25f
        val centroY = (ay + by + cy + dy) * 0.25f
        val centroZ = (az + bz + cz + dz) * 0.25f
        emitStrip(v, m, ax, ay, az, bx, by, bz, centroX, centroY, centroZ, r, g, b, alpha)
        emitStrip(v, m, bx, by, bz, cx, cy, cz, centroX, centroY, centroZ, r, g, b, alpha)
        emitStrip(v, m, cx, cy, cz, dx, dy, dz, centroX, centroY, centroZ, r, g, b, alpha)
        emitStrip(v, m, dx, dy, dz, ax, ay, az, centroX, centroY, centroZ, r, g, b, alpha)
    }

    private fun emitStrip(
        v: VertexConsumer, m: Matrix4f,
        oAx: Float, oAy: Float, oAz: Float,
        oBx: Float, oBy: Float, oBz: Float,
        cx: Float, cy: Float, cz: Float,
        r: Int, g: Int, b: Int, alpha: Int,
    ) {
        val iax = oAx + (cx - oAx) * GLOW_INSET
        val iay = oAy + (cy - oAy) * GLOW_INSET
        val iaz = oAz + (cz - oAz) * GLOW_INSET
        val ibx = oBx + (cx - oBx) * GLOW_INSET
        val iby = oBy + (cy - oBy) * GLOW_INSET
        val ibz = oBz + (cz - oBz) * GLOW_INSET
        v.vertex(m, oAx, oAy, oAz).color(r, g, b, alpha).endVertex()
        v.vertex(m, oBx, oBy, oBz).color(r, g, b, alpha).endVertex()
        v.vertex(m, ibx, iby, ibz).color(r, g, b, 0).endVertex()
        v.vertex(m, iax, iay, iaz).color(r, g, b, 0).endVertex()
    }

    /** Line outline for every edge of a box (12 lines). */
    private fun emitBoxLines(
        v: VertexConsumer, m: Matrix4f, n: Matrix3f,
        bx: Float, by: Float, bz: Float, sx: Float, sy: Float, sz: Float,
        r: Int, g: Int, b: Int, alpha: Int,
    ) {
        val x0 = bx / PX_PER_BLOCK
        val x1 = (bx + sx) / PX_PER_BLOCK
        val wy0 = (-(by + sy) - Y_CENTRE_PX) / PX_PER_BLOCK
        val wy1 = (-by - Y_CENTRE_PX) / PX_PER_BLOCK
        val z0 = bz / PX_PER_BLOCK
        val z1 = (bz + sz) / PX_PER_BLOCK
        // Bottom rectangle (4 edges)
        emitLine(v, m, n, x0, wy0, z0, x1, wy0, z0, r, g, b, alpha)
        emitLine(v, m, n, x1, wy0, z0, x1, wy0, z1, r, g, b, alpha)
        emitLine(v, m, n, x1, wy0, z1, x0, wy0, z1, r, g, b, alpha)
        emitLine(v, m, n, x0, wy0, z1, x0, wy0, z0, r, g, b, alpha)
        // Top rectangle (4 edges)
        emitLine(v, m, n, x0, wy1, z0, x1, wy1, z0, r, g, b, alpha)
        emitLine(v, m, n, x1, wy1, z0, x1, wy1, z1, r, g, b, alpha)
        emitLine(v, m, n, x1, wy1, z1, x0, wy1, z1, r, g, b, alpha)
        emitLine(v, m, n, x0, wy1, z1, x0, wy1, z0, r, g, b, alpha)
        // Vertical edges (4)
        emitLine(v, m, n, x0, wy0, z0, x0, wy1, z0, r, g, b, alpha)
        emitLine(v, m, n, x1, wy0, z0, x1, wy1, z0, r, g, b, alpha)
        emitLine(v, m, n, x1, wy0, z1, x1, wy1, z1, r, g, b, alpha)
        emitLine(v, m, n, x0, wy0, z1, x0, wy1, z1, r, g, b, alpha)
    }

    private fun emitLine(
        v: VertexConsumer, m: Matrix4f, n: Matrix3f,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        r: Int, g: Int, b: Int, alpha: Int,
    ) {
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val invLen = 1f / kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        val nx = dx * invLen; val ny = dy * invLen; val nz = dz * invLen
        v.vertex(m, ax, ay, az).color(r, g, b, alpha).normal(n, nx, ny, nz).endVertex()
        v.vertex(m, bx, by, bz).color(r, g, b, alpha).normal(n, nx, ny, nz).endVertex()
    }

    /** Wrap [SODER_TEXTURE] around the 4 side faces of the body box.
     *  The texture appears TWICE going around the bottle. U coordinates
     *  are intentionally `high → low → low → high` on each face so the
     *  brand label reads left-to-right when viewed from outside (the
     *  obvious `low → high` mapping had it mirrored — outside-the-face
     *  viewers see +Z as their LEFT, not their right, in MC's
     *  coordinate system). */
    private fun emitTextureSides(
        v: VertexConsumer, m: Matrix4f, n: Matrix3f,
        bx: Float, by: Float, bz: Float, sx: Float, sy: Float, sz: Float,
        alpha: Int,
    ) {
        val x0 = bx / PX_PER_BLOCK
        val x1 = (bx + sx) / PX_PER_BLOCK
        val wy0 = (-(by + sy) - Y_CENTRE_PX) / PX_PER_BLOCK
        val wy1 = (-by - Y_CENTRE_PX) / PX_PER_BLOCK
        val z0 = bz / PX_PER_BLOCK
        val z1 = (bz + sz) / PX_PER_BLOCK

        val u0 = 0.00f; val u1 = 0.50f; val u2 = 1.00f

        // East (+X)
        emitTexQuad(v, m, n,
            x1, wy0, z0, x1, wy0, z1, x1, wy1, z1, x1, wy1, z0,
            u1, 1f, u0, 1f, u0, 0f, u1, 0f,
            1f, 0f, 0f, alpha)
        // South (+Z)
        emitTexQuad(v, m, n,
            x1, wy0, z1, x0, wy0, z1, x0, wy1, z1, x1, wy1, z1,
            u2, 1f, u1, 1f, u1, 0f, u2, 0f,
            0f, 0f, 1f, alpha)
        // West (-X) — second copy starts (u 0..0.5)
        emitTexQuad(v, m, n,
            x0, wy0, z1, x0, wy0, z0, x0, wy1, z0, x0, wy1, z1,
            u1, 1f, u0, 1f, u0, 0f, u1, 0f,
            -1f, 0f, 0f, alpha)
        // North (-Z)
        emitTexQuad(v, m, n,
            x0, wy0, z0, x1, wy0, z0, x1, wy1, z0, x0, wy1, z0,
            u2, 1f, u1, 1f, u1, 0f, u2, 0f,
            0f, 0f, -1f, alpha)
    }

    /** Block-break overlay over every face of every box. Picks the
     *  destroy_stage_N texture matching the current damage stage and
     *  maps `(0,0)–(1,1)` UVs on each face — vanilla's crumbling
     *  shader handles the "darken underneath" multiply blend. */
    fun renderCrumble(poseStack: PoseStack, buffer: MultiBufferSource, stage: Int) {
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()
        val v = buffer.getBuffer(CRUMBLE_TYPES[stage.coerceIn(0, 9)])
        emitBoxCrumble(v, matrix, normal, -3f, -4f, -3f, 6f, 4f, 6f)             // base
        emitBoxCrumble(v, matrix, normal, -2.4f, -10.9f, -2.4f, 4.8f, 6.8f, 4.8f) // body
        emitBoxCrumble(v, matrix, normal, -3f, -13f, -3f, 6f, 2f, 6f)            // shoulder
        emitBoxCrumble(v, matrix, normal, -2f, -15f, -2f, 4f, 2f, 4f)            // neck
        emitBoxCrumble(v, matrix, normal, -0.9f, -15.6f, -0.9f, 1.8f, 0.8f, 1.8f) // cap collar
        emitBoxCrumble(v, matrix, normal, -1.2f, -16.95f, -1.2f, 2.4f, 1.4f, 2.4f) // cap top
    }

    private fun emitBoxCrumble(
        v: VertexConsumer, m: Matrix4f, n: Matrix3f,
        bx: Float, by: Float, bz: Float, sx: Float, sy: Float, sz: Float,
    ) {
        val x0 = bx / PX_PER_BLOCK
        val x1 = (bx + sx) / PX_PER_BLOCK
        val wy0 = (-(by + sy) - Y_CENTRE_PX) / PX_PER_BLOCK
        val wy1 = (-by - Y_CENTRE_PX) / PX_PER_BLOCK
        val z0 = bz / PX_PER_BLOCK
        val z1 = (bz + sz) / PX_PER_BLOCK
        // -Y
        emitCrumbleQuad(v, m, n, x0, wy0, z0, x1, wy0, z0, x1, wy0, z1, x0, wy0, z1, 0f, -1f, 0f)
        // +Y
        emitCrumbleQuad(v, m, n, x0, wy1, z1, x1, wy1, z1, x1, wy1, z0, x0, wy1, z0, 0f, 1f, 0f)
        // -Z
        emitCrumbleQuad(v, m, n, x1, wy0, z0, x0, wy0, z0, x0, wy1, z0, x1, wy1, z0, 0f, 0f, -1f)
        // +Z
        emitCrumbleQuad(v, m, n, x0, wy0, z1, x1, wy0, z1, x1, wy1, z1, x0, wy1, z1, 0f, 0f, 1f)
        // -X
        emitCrumbleQuad(v, m, n, x0, wy0, z0, x0, wy0, z1, x0, wy1, z1, x0, wy1, z0, -1f, 0f, 0f)
        // +X
        emitCrumbleQuad(v, m, n, x1, wy0, z1, x1, wy0, z0, x1, wy1, z0, x1, wy1, z1, 1f, 0f, 0f)
    }

    private fun emitCrumbleQuad(
        v: VertexConsumer, m: Matrix4f, n: Matrix3f,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        nx: Float, ny: Float, nz: Float,
    ) {
        // BLOCK vertex format: position → color → uv → uv2 → normal.
        v.vertex(m, ax, ay, az).color(255, 255, 255, 255).uv(0f, 0f).uv2(0xF000F0).normal(n, nx, ny, nz).endVertex()
        v.vertex(m, bx, by, bz).color(255, 255, 255, 255).uv(1f, 0f).uv2(0xF000F0).normal(n, nx, ny, nz).endVertex()
        v.vertex(m, cx, cy, cz).color(255, 255, 255, 255).uv(1f, 1f).uv2(0xF000F0).normal(n, nx, ny, nz).endVertex()
        v.vertex(m, dx, dy, dz).color(255, 255, 255, 255).uv(0f, 1f).uv2(0xF000F0).normal(n, nx, ny, nz).endVertex()
    }

    private fun emitTexQuad(
        v: VertexConsumer, m: Matrix4f, n: Matrix3f,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        au: Float, av: Float, bu: Float, bv: Float,
        cu: Float, cv: Float, du: Float, dv: Float,
        nx: Float, ny: Float, nz: Float,
        alpha: Int,
    ) {
        v.vertex(m, ax, ay, az).color(255, 255, 255, alpha).uv(au, av)
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(n, nx, ny, nz).endVertex()
        v.vertex(m, bx, by, bz).color(255, 255, 255, alpha).uv(bu, bv)
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(n, nx, ny, nz).endVertex()
        v.vertex(m, cx, cy, cz).color(255, 255, 255, alpha).uv(cu, cv)
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(n, nx, ny, nz).endVertex()
        v.vertex(m, dx, dy, dz).color(255, 255, 255, alpha).uv(du, dv)
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(0xF000F0).normal(n, nx, ny, nz).endVertex()
    }
}
