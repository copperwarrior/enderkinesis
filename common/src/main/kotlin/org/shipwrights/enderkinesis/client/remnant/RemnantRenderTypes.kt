package org.shipwrights.enderkinesis.client.remnant

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Render type and core shader for the Irreverant Remnant's cubes.
 *
 * Vertices use vanilla's entity format: packed light for the lightmap, in-face
 * coordinates for the noise, and the noise seed and opacity in the colour
 * channels. The shader lights them as vanilla lights entities.
 *
 * The shader instance is populated on resource reload, so draws must gate on
 * [isReady]; the supplier below is only consulted at draw time, which is what
 * lets [SURFACE] be built at class-load.
 */
object RemnantRenderTypes {

    private var shaderInstance: ShaderInstance? = null

    fun getShader(): ShaderInstance? = shaderInstance

    val isReady: Boolean get() = shaderInstance != null

    val SURFACE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_irreverant_remnant",
        DefaultVertexFormat.NEW_ENTITY,
        VertexFormat.Mode.QUADS,
        1 shl 18,
        false,
        // The client sorts its boxes itself before upload.
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shaderInstance })
            // Translucent without depth writes, so cubes and skins show through each other; drawn back to front.
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            // By winding, not by normal: the shading normals bend past the face, so a normal test lets far faces
            // show through at the edges.
            .setCullState(RenderStateShard.CULL)

            .createCompositeState(false),
    )

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            // Vanilla builds a shader's file paths by string concat against the
            // minecraft namespace, so a namespaced name cannot be passed here.
            // The wrapper below retries those lookups in our namespace first.
            sink.registerShader(
                ShaderInstance(
                    namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_irreverant_remnant",
                    DefaultVertexFormat.NEW_ENTITY,
                ),
            ) { instance -> shaderInstance = instance }
        }
    }

    /** Wraps a [ResourceProvider] so any `minecraft:`-namespaced lookup is
     *  tried in [namespace] first, falling back to the original. Lets vanilla
     *  shader loading find files placed in our own namespace. */
    private fun namespaced(delegate: ResourceProvider, namespace: String) =
        ResourceProvider { loc ->
            if (loc.namespace == ResourceLocation.DEFAULT_NAMESPACE) {
                val rewritten = ResourceLocation(namespace, loc.path)
                val attempt = delegate.getResource(rewritten)
                if (attempt.isPresent) attempt else delegate.getResource(loc)
            } else {
                delegate.getResource(loc)
            }
        }
}
