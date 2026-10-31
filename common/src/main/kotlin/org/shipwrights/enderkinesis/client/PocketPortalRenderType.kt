package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * The end portal on an Abyssal Pocket's walls: vanilla's end portal look, but lit and faded
 * per vertex (shader `rendertype_pocket_portal`). The vertex colour's rgb is the light on
 * the face (light level, ambient occlusion, face shade), its alpha how much portal shows.
 *
 * Loaded the same way as [YgannAbyssRenderTypes]; draw only once [isReady].
 */
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
            // As vanilla's end portal: nebula in slot 0, star layers in slot 1.
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
                    YgannAbyssRenderTypes.namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_pocket_portal",
                    DefaultVertexFormat.POSITION_COLOR,
                )
            ) { instance -> shaderInstance = instance }
        }
    }
}
