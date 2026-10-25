package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Render types for spell visuals. Light is drawn additively with
 *  [AegisRenderType.FACE_GLOW]; dark volumes (Gravity, Void) need ordinary blending, which
 *  [SHADE] gives: position-colour, translucent, depth-tested, no depth write, both faces. */
object SpellVfxRenderTypes {
    val SHADE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_spell_shade",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        1024,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )
}
