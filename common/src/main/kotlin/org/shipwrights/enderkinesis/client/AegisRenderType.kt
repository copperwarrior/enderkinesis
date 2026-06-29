package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * RenderType for the Staff-of-Aegis face glow.
 *
 * Same shape as [OrbBeamLineRenderType.BEAM_LINE] (additive blend + depth
 * tested, no depth write, no cull) — overlapping strips at face corners
 * brighten so the four edges of each face read as a single luminous outline,
 * and the strip's outer-α=255 → inner-α=0 ramp gives the "outline glowing
 * inward" radial fade.
 */
object AegisRenderType {

    val FACE_GLOW: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_aegis_face_glow",
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
