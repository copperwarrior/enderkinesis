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
}
