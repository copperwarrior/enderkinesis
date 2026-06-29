package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.block.OrbOfScryingBlock
import org.shipwrights.enderkinesis.block.ScryingCarpet
import org.shipwrights.enderkinesis.blockentity.OrbOfScryingBlockEntity

/**
 * Block-entity renderer for the Orb of Scrying. Same on-screen footprint as the unbound
 * Orb of Linking — inner orb shell + pulsing haze on top of the static pedestal — except:
 *
 *  - The texture is `block/scrying_orb` instead of `block/link_orb` / `block/orb_transmitter`
 *    / `block/orb_reciever`. The scrying orb has no role split; it's always one look.
 *  - The haze is ALWAYS on (scrying orbs are always "active"); the Orb of Linking gates its
 *    haze on `ORB_ROLE != UNBOUND`.
 *  - No held-item path — scrying orbs don't carry filters or books.
 *
 * Inner-shell and haze geometry / UV sampling / facing rotation are copied verbatim from
 * [OrbOfLinkingHazeRenderer] so the two BERs render identical shapes. If either side's
 * geometry drifts, the artist's Blockbench coords are the source of truth.
 */
class OrbOfScryingHazeRenderer(
    @Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<OrbOfScryingBlockEntity> {

    override fun render(
        be: OrbOfScryingBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val state = be.blockState
        val level = be.level ?: return

        // Covered orbs draw entirely from the wool block model — skip the inner shell + haze.
        if (state.getValue(OrbOfScryingBlock.CARPET) != ScryingCarpet.NONE) return

        val sprite = Minecraft.getInstance()
            .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(SCRYING_TEXTURE)
        val texSpanU = sprite.u1 - sprite.u0
        val texSpanV = sprite.v1 - sprite.v0

        // Facing rotation around block centre. Matches MC's BlockModelRotation: vertex is
        // `Ry · Rx · v`, and PoseStack right-multiplies so mulPose(Y) must come before
        // mulPose(X). Same order the [OrbOfLinkingHazeRenderer] uses.
        val facing = state.getValue(OrbOfScryingBlock.FACING)
        val (xRotDeg, yRotDeg) = facingRotation(facing)
        ps.pushPose()
        ps.translate(0.5, 0.5, 0.5)
        if (yRotDeg != 0) ps.mulPose(Axis.YP.rotationDegrees(yRotDeg.toFloat()))
        if (xRotDeg != 0) ps.mulPose(Axis.XP.rotationDegrees(xRotDeg.toFloat()))
        ps.translate(-0.5, -0.5, -0.5)

        val matrix = ps.last().pose()
        val normal = ps.last().normal()
        val builder = bufferSource.getBuffer(
            RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)
        )

        // Inner orb shell — texture's per-pixel alpha controls translucency; vertex alpha 1.0.
        renderInnerOrb(
            builder, matrix, normal,
            sprite.u0, sprite.v0, texSpanU, texSpanV,
            ORB_ALPHA, packedLight, packedOverlay,
        )

        // Haze — fixed alpha for the scrying orb (no breathing pulse). The linking orb
        // breathes its haze to signal an active SEND/RECEIVE role transition; the scrying
        // orb has no role split, so a steady haze reads as "always on" without animation.
        val hazeAlpha = STATIC_HAZE_ALPHA
        renderHaze(
            builder, matrix, normal,
            sprite.u0, sprite.v0, texSpanU, texSpanV,
            hazeAlpha, packedLight, packedOverlay,
        )

        ps.popPose()
    }

    /** 8×8×8 orb shell. Geometry + UV taken verbatim from the Orb-of-Linking Blockbench
     *  source so the scrying orb stays in lock-step visually. */
    private fun renderInnerOrb(
        builder: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        u0: Float, v0: Float, spanU: Float, spanV: Float,
        alpha: Float, packedLight: Int, packedOverlay: Int,
    ) {
        val x0 = 4f / 16f; val y0 = 3f / 16f; val z0 = 4f / 16f
        val x1 = 12f / 16f; val y1 = 11f / 16f; val z1 = 12f / 16f
        quadFace(builder, matrix, normal,
            x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,
            u0 + spanU * 5f / 16f, v0 + spanV * 4f / 16f,
            u0 + spanU * 7f / 16f, v0 + spanV * 6f / 16f,
            0f, -1f, 0f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x0, y1, z1,  x1, y1, z1,  x1, y1, z0,  x0, y1, z0,
            u0 + spanU * 5f / 16f, v0 + spanV * 2f / 16f,
            u0 + spanU * 7f / 16f, v0 + spanV * 4f / 16f,
            0f, 1f, 0f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x1, y0, z0,  x0, y0, z0,  x0, y1, z0,  x1, y1, z0,
            u0 + spanU * 3f / 16f, v0 + spanV * 4f / 16f,
            u0 + spanU * 5f / 16f, v0 + spanV * 6f / 16f,
            0f, 0f, -1f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,
            u0 + spanU * 3f / 16f, v0 + spanV * 0f / 16f,
            u0 + spanU * 5f / 16f, v0 + spanV * 2f / 16f,
            0f, 0f, 1f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x1, y0, z1,  x1, y0, z0,  x1, y1, z0,  x1, y1, z1,
            u0 + spanU * 5f / 16f, v0 + spanV * 0f / 16f,
            u0 + spanU * 7f / 16f, v0 + spanV * 2f / 16f,
            1f, 0f, 0f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0,
            u0 + spanU * 3f / 16f, v0 + spanV * 2f / 16f,
            u0 + spanU * 5f / 16f, v0 + spanV * 4f / 16f,
            -1f, 0f, 0f, alpha, packedLight, packedOverlay)
    }

    /** 10×10×10 haze cube. All six faces sample the same haze patch on the texture. */
    private fun renderHaze(
        builder: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        u0: Float, v0: Float, spanU: Float, spanV: Float,
        alpha: Float, packedLight: Int, packedOverlay: Int,
    ) {
        val x0 = 3f / 16f; val y0 = 2f / 16f; val z0 = 3f / 16f
        val x1 = 13f / 16f; val y1 = 12f / 16f; val z1 = 13f / 16f
        val uMin = u0 + spanU * HAZE_U_MIN / 16f
        val uMax = u0 + spanU * HAZE_U_MAX / 16f
        val vMin = v0 + spanV * HAZE_V_MIN / 16f
        val vMax = v0 + spanV * HAZE_V_MAX / 16f
        quadFace(builder, matrix, normal,
            x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,
            uMin, vMin, uMax, vMax, 0f, -1f, 0f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x0, y1, z1,  x1, y1, z1,  x1, y1, z0,  x0, y1, z0,
            uMin, vMin, uMax, vMax, 0f, 1f, 0f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x1, y0, z0,  x0, y0, z0,  x0, y1, z0,  x1, y1, z0,
            uMin, vMin, uMax, vMax, 0f, 0f, -1f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,
            uMin, vMin, uMax, vMax, 0f, 0f, 1f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x1, y0, z1,  x1, y0, z0,  x1, y1, z0,  x1, y1, z1,
            uMin, vMin, uMax, vMax, 1f, 0f, 0f, alpha, packedLight, packedOverlay)
        quadFace(builder, matrix, normal,
            x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0,
            uMin, vMin, uMax, vMax, -1f, 0f, 0f, alpha, packedLight, packedOverlay)
    }

    private fun quadFace(
        builder: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        uMin: Float, vMin: Float, uMax: Float, vMax: Float,
        nx: Float, ny: Float, nz: Float,
        alpha: Float, packedLight: Int, packedOverlay: Int,
    ) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        v(builder, matrix, normal, x1, y1, z1, uMin, vMax, a, packedLight, packedOverlay, nx, ny, nz)
        v(builder, matrix, normal, x2, y2, z2, uMax, vMax, a, packedLight, packedOverlay, nx, ny, nz)
        v(builder, matrix, normal, x3, y3, z3, uMax, vMin, a, packedLight, packedOverlay, nx, ny, nz)
        v(builder, matrix, normal, x4, y4, z4, uMin, vMin, a, packedLight, packedOverlay, nx, ny, nz)
    }

    private fun v(
        builder: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        x: Float, y: Float, z: Float,
        u: Float, vCoord: Float,
        a: Int, packedLight: Int, packedOverlay: Int,
        nx: Float, ny: Float, nz: Float,
    ) {
        builder.vertex(matrix, x, y, z)
            .color(255, 255, 255, a)
            .uv(u, vCoord)
            .overlayCoords(packedOverlay)
            .uv2(packedLight)
            .normal(normal, nx, ny, nz)
            .endVertex()
    }

    /** Mirror of [OrbOfLinkingHazeRenderer.facingRotation]: the same Euler degrees the
     *  blockstate JSON applies to the static pedestal model for each facing. */
    private fun facingRotation(facing: Direction): Pair<Int, Int> = when (facing) {
        Direction.DOWN -> 0 to 0
        Direction.UP -> 180 to 0
        Direction.NORTH -> 90 to 0
        Direction.SOUTH -> 270 to 0
        Direction.EAST -> 90 to 270
        Direction.WEST -> 90 to 90
    }

    companion object {
        private val SCRYING_TEXTURE = ResourceLocation("enderkinesis", "block/scrying_orb")

        private const val ORB_ALPHA = 1.0f

        private const val HAZE_U_MIN = 7f
        private const val HAZE_U_MAX = 10.5f
        private const val HAZE_V_MIN = 0f
        private const val HAZE_V_MAX = 3.5f

        /** Static haze alpha for the scrying orb. Picked at roughly the average of the
         *  linking orb's pulse range (BASE_ALPHA + PULSE_AMP/2 ≈ 0.775 in the source BER)
         *  so the visual weight matches an active linking orb at mid-pulse, just without
         *  the breathing animation. */
        private const val STATIC_HAZE_ALPHA: Float = 0.78f
    }
}
