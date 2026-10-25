package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.item.ItemDisplayContext
import org.shipwrights.enderkinesis.block.AltarOfIlluminationBlock
import org.shipwrights.enderkinesis.blockentity.AltarOfIlluminationBlockEntity
import org.shipwrights.enderkinesis.registry.EKItems
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws what the Altar of Illumination holds: the blank scroll floating on the ink in its
 * basin (the ink itself is the block's fill model), and each inscribed glyph as a glowing,
 * tumbling shard orbiting above it. Plain glyphs are their shard item; the fusions, which
 * have no shard item, are the base Mystic Shard tinted with the fusion's colour.
 */
class AltarOfIlluminationRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<AltarOfIlluminationBlockEntity> {

    private val itemRenderer: ItemRenderer = ctx.itemRenderer

    override fun render(
        be: AltarOfIlluminationBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val time = (be.level?.gameTime ?: 0L) + partialTick
        if (be.hasScroll) renderBlank(be, ps, bufferSource, packedLight)
        renderGlyphs(be, time, ps, bufferSource)
    }

    private fun renderBlank(
        be: AltarOfIlluminationBlockEntity, ps: PoseStack, buffers: MultiBufferSource, light: Int,
    ) {
        val ink = be.blockState.getValue(AltarOfIlluminationBlock.INK)
        val surface = BASIN_FLOOR + (BASIN_TOP - BASIN_FLOOR) * ink / AltarOfIlluminationBlock.MAX_INK
        ps.pushPose()
        ps.translate(0.5, surface + FLAT_MODEL_LIFT, 0.5)
        ps.mulPose(Axis.XP.rotationDegrees(90f))
        ps.scale(BLANK_SCALE, BLANK_SCALE, BLANK_SCALE)
        itemRenderer.renderStatic(
            be.blank, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, ps, buffers, be.level, 0,
        )
        ps.popPose()
    }

    /** Each glyph orbits the altar's axis, bobbing, while spinning about its own vertical
     *  axis with a slight tilt, so the shard's extruded edges catch the eye. Full-bright, as
     *  the shards are the altar's light. */
    private fun renderGlyphs(be: AltarOfIlluminationBlockEntity, time: Float, ps: PoseStack, buffers: MultiBufferSource) {
        val glyphs = be.glyphs
        for ((i, element) in glyphs.withIndex()) {
            val phase = i * (2.0 * Math.PI / glyphs.size)
            val angle = time * ORBIT_SPEED + phase
            val bob = sin(time * BOB_SPEED + i * 1.7) * BOB_HEIGHT
            ps.pushPose()
            ps.translate(0.5 + cos(angle) * ORBIT_RADIUS, ORBIT_HEIGHT + bob, 0.5 + sin(angle) * ORBIT_RADIUS)
            ps.mulPose(Axis.YP.rotationDegrees(time * SPIN_DEGREES_PER_TICK + i * 47f))
            ps.mulPose(Axis.ZP.rotationDegrees(TILT_DEGREES * sin(time * BOB_SPEED * 0.5f + i).toFloat()))
            ps.scale(GLYPH_SCALE, GLYPH_SCALE, GLYPH_SCALE)
            itemRenderer.renderStatic(
                EKItems.glyphStack(element), ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY, ps, buffers, be.level, be.blockPos.asLong().toInt() + i,
            )
            ps.popPose()
        }
    }

    companion object {
        /** The basin the ink fills, from the model: floor at 14 px, brim at 15.5 px. */
        private const val BASIN_FLOOR = 14.0 / 16.0
        private const val BASIN_TOP = 15.5 / 16.0
        private const val FLAT_MODEL_LIFT = 1.0 / 64.0
        private const val BLANK_SCALE = 0.55f

        private const val ORBIT_RADIUS = 0.55
        private const val ORBIT_HEIGHT = 1.45
        /** Radians per tick: one lap every ~10 s. */
        private const val ORBIT_SPEED = 0.03
        private const val BOB_SPEED = 0.08
        private const val BOB_HEIGHT = 0.06
        private const val SPIN_DEGREES_PER_TICK = 4f
        private const val TILT_DEGREES = 20f
        private const val GLYPH_SCALE = 0.4f
    }
}
