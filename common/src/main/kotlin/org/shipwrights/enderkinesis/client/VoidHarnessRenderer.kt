package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.block.VoidHarnessBlock
import org.shipwrights.enderkinesis.blockentity.VoidHarnessBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Renders a two-cube crystal inside the Void Harness's stained-glass shell.
 *
 *  - **Outer cube** = an [EKBlocks.ANCRITE_GRATE] block state, rendered as the larger of the two
 *    concentric cubes. The grate's cutout holes show the inner cube through it.
 *  - **Inner cube** = an [EKBlocks.CREPUSCULITE_BLOCK] block state, smaller and counter-rotating
 *    against the outer, nested inside the grate.
 *
 * Both are drawn through {@link net.minecraft.client.renderer.block.BlockRenderDispatcher#renderSingleBlock},
 * so they pick up their own block-atlas textures and render types automatically — no end-crystal
 * model parts and no custom UV mapping involved.
 *
 * State-dependent behaviour:
 *  - **Unpowered**: rotation frozen at whatever value it last reached. Crystal lit by ambient
 *    block light only — reads as a dark static pair of cubes.
 *  - **Powered**: rotates at `power/15 * BASE_ROT` degrees per tick, with the diagonal-tilt
 *    counter-rotation idiom from vanilla EndCrystalRenderer. `packedLight` is forced toward
 *    `FULL_BRIGHT` so the crystal visibly glows through the stained-glass shell.
 *
 * Note: vertical bobbing was removed by request — the crystal sits at a fixed centre regardless
 * of power level.
 */
class VoidHarnessRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<VoidHarnessBlockEntity> {

    override fun render(
        be: VoidHarnessBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level ?: return
        val power = be.blockState.getValue(VoidHarnessBlock.POWER)

        // Rotation phase: advance by `BASE_ROT · powerFactor · dt` each frame so a power change
        // only changes the *rate*, not the phase (no visual snap). First render initialises
        // without advancing (`clientLastRenderTime = -1`).
        val powerFactor = power / 15.0
        val now = level.gameTime + partialTick.toDouble()
        val last = be.clientLastRenderTime
        val dt = if (last < 0.0) 0.0 else (now - last).coerceAtLeast(0.0)
        be.clientLastRenderTime = now
        if (power > 0) {
            be.clientRotationPhase = (be.clientRotationPhase + dt * BASE_ROT_DEG_PER_TICK * powerFactor) % 360.0
        }

        val rotation = be.clientRotationPhase.toFloat()

        // BlockLight scales with power so the crystal glows proportionally.
        val light = if (power > 0) LightTexture.pack(power, 15) else packedLight

        val br = Minecraft.getInstance().blockRenderer
        val outerState = EKBlocks.ANCRITE_GRATE.get().defaultBlockState()
        val innerState = EKBlocks.CREPUSCULITE_BLOCK.get().defaultBlockState()
        val rotRadians = Math.toRadians(rotation.toDouble()).toFloat()

        // Outer (ancrite grate): rotates around Y, then tilts on the (sin45, 0, sin45) diagonal.
        ps.pushPose()
        ps.translate(0.5, 0.5, 0.5)
        ps.mulPose(Quaternionf().rotationY(rotRadians))
        ps.mulPose(Quaternionf().setAngleAxis(DIAG_TILT_RAD, SIN_45, 0f, SIN_45))
        ps.scale(OUTER_CUBE_SIZE, OUTER_CUBE_SIZE, OUTER_CUBE_SIZE)
        // `renderSingleBlock` draws the block model in the (0,0,0)–(1,1,1) cube; shift so the
        // cube is centred on the origin (and thus on the host block's centre).
        ps.translate(-0.5, -0.5, -0.5)
        br.renderSingleBlock(outerState, ps, bufferSource, light, packedOverlay)
        ps.popPose()

        // Inner (crepusculite): the *same* outer rotation/tilt PLUS an additional tilt+rotation
        // — the compound-rotation idiom that makes the inner cube counter-rotate visually
        // against the outer one (mirrors vanilla EndCrystalRenderer's nested behaviour).
        ps.pushPose()
        ps.translate(0.5, 0.5, 0.5)
        ps.mulPose(Quaternionf().rotationY(rotRadians))
        ps.mulPose(Quaternionf().setAngleAxis(DIAG_TILT_RAD, SIN_45, 0f, SIN_45))
        ps.mulPose(Quaternionf().setAngleAxis(DIAG_TILT_RAD, SIN_45, 0f, SIN_45))
        ps.mulPose(Quaternionf().rotationY(rotRadians))
        ps.scale(INNER_CUBE_SIZE, INNER_CUBE_SIZE, INNER_CUBE_SIZE)
        ps.translate(-0.5, -0.5, -0.5)
        br.renderSingleBlock(innerState, ps, bufferSource, light, packedOverlay)
        ps.popPose()
    }

    companion object {
        private val SIN_45 = Math.sin(Math.PI / 4.0).toFloat()
        private val DIAG_TILT_RAD = (Math.PI / 4.0).toFloat()
        /** Outer cube side length, in host-block units. 0.6 = comfortably inside the stained-
         *  glass shell (block size 1.0) with margin for the diagonal-tilt rotation. */
        private const val OUTER_CUBE_SIZE = 0.6f
        /** Inner cube side length, in host-block units. Same `0.875` ratio vanilla
         *  EndCrystalRenderer uses between its `glass` and `cube` parts. */
        private const val INNER_CUBE_SIZE = OUTER_CUBE_SIZE * 0.875f
        /** Rotation rate at full redstone power (degrees / tick). Scaled by `power/15`. */
        private const val BASE_ROT_DEG_PER_TICK = 3.0
    }
}
