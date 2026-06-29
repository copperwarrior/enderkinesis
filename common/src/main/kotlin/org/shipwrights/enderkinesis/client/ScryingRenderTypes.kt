package org.shipwrights.enderkinesis.client

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
 * Custom RenderType + shader for the scrying-orb vignette. The host emits ONE full-screen
 * quad with POSITION-only vertices; the fragment shader does all the work — radial
 * gradient from the screen centre, edge displaced by 4-octave fBm value-noise so the
 * vignette boundary isn't a perfect circle. Mirrors the [YgannAbyssRenderTypes] pattern.
 *
 * Why a shader and not a procedural mesh: a "vignette" is a continuous radial gradient,
 * and approximating one with mesh strips (vertical fillGradient on top/bottom, horizontal
 * on sides) reads as four orange bars meeting at the corners, not a circular fade — which
 * is exactly what the user flagged. A fragment shader computes the gradient per-pixel in
 * native screen space and lets us perturb the radius with noise the same way the Ygann
 * Madness overlay perturbs its depth band.
 */
object ScryingRenderTypes {

    private var shaderInstance: ShaderInstance? = null

    val VIGNETTE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_scrying_vignette",
        DefaultVertexFormat.POSITION,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shaderInstance })
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            // GUI ortho draw on top of everything that's already been rendered. NO_DEPTH
            // keeps us above all HUD pixels regardless of their z.
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
                    namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_scrying_vignette",
                    DefaultVertexFormat.POSITION,
                )
            ) { instance -> shaderInstance = instance }
        }
    }

    /** Same workaround as [YgannAbyssRenderTypes]: vanilla [ShaderInstance] builds the
     *  resource path as `"shaders/core/" + name + ".json"` and bombs on a namespaced
     *  `name`. Wrap the provider so `minecraft:...` lookups are retried in our namespace
     *  first, lets us keep the shader files under `assets/enderkinesis/shaders/core/`. */
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
