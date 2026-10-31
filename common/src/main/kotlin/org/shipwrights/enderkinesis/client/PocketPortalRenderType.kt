package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod

// Vertex rgb is the face light (level, AO, shade); alpha is how much portal shows. Draw only after [isReady].
object PocketPortalRenderType {

    private var shaderInstance: ShaderInstance? = null

    val POCKET_PORTAL: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_pocket_portal",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        2048,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shaderInstance })
            // As the vanilla end portal: nebula in slot 0, star layers in slot 1.
            .setTextureState(
                RenderStateShard.MultiTextureStateShard.builder()
                    .add(ResourceLocation("textures/environment/end_sky.png"), false, false)
                    .add(ResourceLocation("textures/entity/end_portal.png"), false, false)
                    .build()
            )
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false)
    )

    fun isReady(): Boolean = shaderInstance != null

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_pocket_portal",
                    DefaultVertexFormat.POSITION_COLOR,
                )
            ) { instance -> shaderInstance = instance }
        }
    }
}
