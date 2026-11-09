package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * RenderType + shader wrapper for the tome-summon vignette. Structurally
 * identical to [ScryingRenderTypes] — POSITION-only full-screen quad,
 * shader does the work — but lives in its own object so the tome-summon
 * overlay can evolve (different uniforms, blend modes, render targets…)
 * without touching the scrying overlay's render pipeline.
 *
 * The shader has an extra `Intensity` uniform (0..1) that scales both the
 * boundary warble amplitude and the final alpha; the host
 * ([TomeSummonOverlay]) drives it from the summon-phase envelope.
 */
object TomeSummonRenderTypes {

    private var shaderInstance: ShaderInstance? = null

    val VIGNETTE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_tome_summon_vignette",
        DefaultVertexFormat.POSITION,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shaderInstance })
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false)
    )

    fun isReady(): Boolean = shaderInstance != null
    fun getShader(): ShaderInstance? = shaderInstance

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    provider.preferringModNamespace(),
                    "rendertype_tome_summon_vignette",
                    DefaultVertexFormat.POSITION,
                )
            ) { instance -> shaderInstance = instance }
        }
    }
}
