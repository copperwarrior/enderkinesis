package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import java.util.OptionalDouble
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Render types for the Ga'Mylan cage crystal. Face glow uses a
 *  conventional SRC_ALPHA / ONE_MINUS_SRC_ALPHA translucent blend so
 *  the crystal reads as a tinted glass shell — the thin rim gradient
 *  it produces IS the crystal's outline. LINES is retained for the
 *  Tribulla soda-bottle overlay and the shatter debris. */
object GamylanCageRenderType {

    /** Outline width in pixels for the LINES draws in SoderRenderer /
     *  GamylanCageShatter. Above vanilla's ~2.5 px default so the
     *  edges read against translucent geometry. */
    private const val LINE_WIDTH: Double = 4.0

    /** Additive-blend companion to [FACE_GLOW]. Emits the SAME
     *  trapezoidal rim-strip geometry (so it only lands where the
     *  gradient is, never fills the interior) with a light-modulated
     *  colour that's baked on the client side from the crystal's
     *  world-lightmap value. Under LIGHTNING_TRANSPARENCY
     *  (SRC_ALPHA, ONE) the rim's edge contribution is added on top
     *  of whatever's behind. */
    val FACE_LIT: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_gamylan_cage_face_lit",
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
            .createCompositeState(false),
    )

    val FACE_GLOW: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_gamylan_cage_face_glow",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
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

    val LINES: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_gamylan_cage_lines",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES,
        1536,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(RenderStateShard.LineStateShard(OptionalDouble.of(LINE_WIDTH)))
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            // Main target: this draws after the Fabulous composite, and the item entity target
            // is cleared at the start of the next frame.
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false),
    )
}
