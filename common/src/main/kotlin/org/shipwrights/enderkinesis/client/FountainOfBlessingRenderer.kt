package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemDisplayContext
import org.shipwrights.enderkinesis.blockentity.FountainOfBlessingBlockEntity

/**
 * The offered item floating in the fountain. The boiling dome ([FountainOfBlessingDome]) and the
 * glowing upwelling ([FountainOfBlessingVolume]) draw later in the frame, queued from here.
 */
class FountainOfBlessingRenderer(
    @Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<FountainOfBlessingBlockEntity> {

    // Render thread only.
    private val lightPos = BlockPos.MutableBlockPos()

    override fun render(
        be: FountainOfBlessingBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level ?: return
        val t = ((level.gameTime % TIME_WRAP) + partialTick) / 20f
        val pos = be.blockPos
        val surface = (be.surfaceY - pos.y).toFloat()
        val charge = be.charge(level, partialTick)
        val burst = burst(be)
        val vigour = 1f + charge + 2f * burst
        FountainOfBlessingVolume.queue(
            pos.x + 0.5, be.springY, pos.z + 0.5, (be.surfaceY - be.springY).toFloat(), charge, burst, be.risePhase(partialTick),
        )

        lightPos.set(pos.x, Math.floor(be.surfaceY).toInt(), pos.z)
        val light = LevelRenderer.getLightColor(level, lightPos)
        if (surface > 0.5f) FountainOfBlessingDome.queue(pos, surface, t, vigour, light)
        floatItem(be, ps, bufferSource, light, surface, t, charge, partialTick)
    }

    /** 1 when the charge completes, fading over [BURST_MILLIS]. */
    private fun burst(be: FountainOfBlessingBlockEntity): Float {
        if (be.blessStartMillis == FountainOfBlessingBlockEntity.NOT_BLESSED) return 0f
        val age = (Util.getMillis() - be.blessStartMillis).toFloat() / BURST_MILLIS
        return if (age < 0f || age > 1f) 0f else (1f - age) * (1f - age)
    }

    /** The offered item bobs in the boil, turning faster and lifting as it charges. */
    private fun floatItem(
        be: FountainOfBlessingBlockEntity, ps: PoseStack, bufferSource: MultiBufferSource,
        light: Int, surface: Float, t: Float, charge: Float, partialTick: Float,
    ) {
        val stack = be.stored
        if (stack.isEmpty) return
        ps.pushPose()
        ps.translate(0.5f, surface + ITEM_LIFT + charge * ITEM_CHARGE_LIFT + 0.08f * sin(t * 2.6f), 0.5f)
        ps.mulPose(Axis.YP.rotation(be.spinAngle(partialTick)))
        ps.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)
        Minecraft.getInstance().itemRenderer.renderStatic(
            stack, ItemDisplayContext.GROUND, light, OverlayTexture.NO_OVERLAY, ps, bufferSource, be.level, be.blockPos.asLong().toInt(),
        )
        ps.popPose()
    }

    private fun sin(x: Float): Float = Math.sin(x.toDouble()).toFloat()

    override fun shouldRenderOffScreen(be: FountainOfBlessingBlockEntity): Boolean = true

    private companion object {
        const val TIME_WRAP = 24000L * 20L
        const val BURST_MILLIS = 1500f

        const val ITEM_LIFT = 0.45f
        const val ITEM_CHARGE_LIFT = 0.5f
        const val ITEM_SCALE = 0.9f
    }
}
