package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import org.shipwrights.enderkinesis.EnderkinesisMod

object FusionRenderTypes {

    private const val DEPTH_SAMPLER = "SceneDepth"

    private var shaderInstance: ShaderInstance? = null

    private val BUBBLE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_fusion_bubble",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shaderInstance })
            .setTransparencyState(RenderStateShard.LIGHTNING_TRANSPARENCY)
            // The shader does the depth occlusion, so the contact glow shows on both sides of the contact.
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderResources(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_fusion_bubble",
                    DefaultVertexFormat.POSITION_COLOR,
                )
            ) { instance -> shaderInstance = instance }
        }
    }

    /** The bubble render type, or null when its shader is not loaded. */
    fun bubble(): RenderType? {
        val shader = shaderInstance ?: return null
        shader.setSampler(DEPTH_SAMPLER, Minecraft.getInstance().mainRenderTarget.depthTextureId)
        return BUBBLE
    }
}
