package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Additive (LIGHTNING_TRANSPARENCY = SRC_ALPHA + ONE) so overlapping passes brighten —
 * produces the halo+core "glowing thread" bloom. Depth tested but not written so the two
 * beam layers self-blend. Cull disabled because the camera-facing ribbon's normal flips.
 */
object OrbBeamLineRenderType {

    val BEAM_LINE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_orb_beam_line",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false)
    )

    /** Output-state shard that swaps the active framebuffer to [ConcealmentShipFB]
     *  on setup and restores [net.minecraft.client.renderer.RenderTarget] main on
     *  clear. Used by [BEAM_LINE_CONCEALED] so cloaked-side beams / lattice ocean
     *  land in the side FB the refraction shader samples — not the main FB. */
    private val SHIP_FB_OUTPUT: RenderStateShard.OutputStateShard =
        RenderStateShard.OutputStateShard(
            "${EnderkinesisMod.MOD_ID}_ship_fb_target",
            { ConcealmentShipFB.bindForDraw() },
            { net.minecraft.client.Minecraft.getInstance().mainRenderTarget.bindWrite(false) },
        )

    /** Same composite state as [BEAM_LINE] but routed to [ConcealmentShipFB]. Used by
     *  [OrbBeamLineRenderer.renderConcealed] and
     *  [CrepusculiteLatticeMeshRenderer.renderConcealed] so cloaked-side geometry
     *  composites through the concealment refraction shader instead of landing on
     *  main FB additively over the world (which read as "too light" against the
     *  exposed sky behind a cloaked ship). */
    val BEAM_LINE_CONCEALED: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_orb_beam_line_concealed",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(SHIP_FB_OUTPUT)
            .createCompositeState(false)
    )
}
