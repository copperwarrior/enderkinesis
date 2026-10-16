package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.Sheets
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.block.VoidHookBlock
import org.shipwrights.enderkinesis.blockentity.VoidHookBlockEntity

class VoidHookRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<VoidHookBlockEntity> {

    private val scratchFacing = Quaternionf()

    override fun render(
        be: VoidHookBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val model = crystalModel ?: return
        val gem = StaffGemGlow.gemOf(model)
        val state = be.blockState

        val now = Util.getMillis()
        val last = be.clientLastRenderMs
        be.clientLastRenderMs = now
        if (last >= 0L) {
            val powerFactor = state.getValue(VoidHookBlock.POWER).toDouble() / VoidHookBlock.MAX_POWER
            be.clientSpinClockMs += (now - last) * powerFactor
        }
        val bob = BOB_AMPLITUDE * Mth.sin((now % BOB_PERIOD_MS).toFloat() / BOB_PERIOD_MS * Mth.TWO_PI)

        ps.pushPose()
        scratchFacing.set(VoidHookBlock.modelRotation(state.getValue(VoidHookBlock.FACING)))
        ps.rotateAround(scratchFacing, 0.5f, 0.5f, 0.5f)
        // Model space, so the bob follows the hook axis in every facing.
        ps.translate(0.0, bob.toDouble(), 0.0)
        gem.spin(GLOW, ps, be.clientSpinClockMs)
        // Queue the halo first: the crystal's request flushes it, so the crystal draws on top.
        gem.queueHalo(GLOW, ps, bufferSource)
        Minecraft.getInstance().blockRenderer.modelRenderer.renderModel(
            ps.last(), bufferSource.getBuffer(Sheets.translucentCullBlockSheet()), state, model,
            1f, 1f, 1f, LightTexture.FULL_BRIGHT, packedOverlay,
        )
        ps.popPose()
    }

    companion object {
        @JvmStatic
        val CRYSTAL_MODEL_LOCATION: ResourceLocation = ResourceLocation("enderkinesis", "block/void_hook_crystal")

        /** Set by the platform bake hook. */
        @Volatile
        @JvmStatic
        var crystalModel: BakedModel? = null

        // The darkest crystal texel, by the same rule as the staff gem halos.
        private val GLOW = StaffGemGlow.Style(0x723E46, StaffGemGlow.Spin.TUMBLE)

        // The tumbling crystal's corners clear the arm above by about 0.54 px.
        private const val BOB_AMPLITUDE = 0.5f / 16f
        private const val BOB_PERIOD_MS = 3000L
    }
}
