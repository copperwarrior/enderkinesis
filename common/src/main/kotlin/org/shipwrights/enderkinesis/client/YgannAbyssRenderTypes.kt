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
 * Custom RenderType and shader registration for the YgannAbyss / Madness portal effect.
 *
 *  ## Why a custom shader
 *
 *  Vanilla [RenderType.endPortal] uses `textureProj()` of a 16×16 noise texture, with
 *  per-layer scaling tuned for a *perspective* projection. In the GUI ortho projection
 *  used by our overlay, projective texturing collapses (`gl_Position.w = 1.0` for every
 *  vertex), so the layers all sample the same 16×16 texture with only ~4–8 tile repeats
 *  across the screen — each source texel becomes a ~100-pixel block on a 1080p display
 *  (the "weirdly blocky" artefact users notice underneath the noise mask).
 *
 *  The replacement shader at `assets/enderkinesis/shaders/core/rendertype_abyss_portal.*`
 *  samples a procedural value-noise starfield in screen-space, sized by `ScreenSize` —
 *  resolution-independent, no projective dependency, no source texture.
 *
 *  ## Shader instance is nullable
 *
 *  [shaderInstance] is populated by [ClientReloadShadersEvent] which fires during the
 *  client resource-pack reload. The supplier passed to [RenderStateShard.ShaderStateShard]
 *  is called lazily at draw time, so the [ABYSS_PORTAL] val can be initialised at class
 *  load time even though the shader hasn't loaded yet. Callers must still gate their draw
 *  on [isReady] — if a frame somehow renders before the first reload completes (e.g. a
 *  resource pack reload race), MC would crash on a null shader.
 *
 *  ## Mod-loader compatibility
 *
 *  Iris/Sodium/OptiFine all leave GUI rendering untouched — their interception points are
 *  world draw types (terrain, entities, sky) and the framebuffer composite. Custom
 *  shaders in our own namespace (`enderkinesis:...`) are not in any shaderpack's target
 *  list, so this shader runs identically regardless of whether a shaderpack is loaded.
 */
object YgannAbyssRenderTypes {

    private var shaderInstance: ShaderInstance? = null

    /** Vanilla's end-portal nebula base texture — bound to `Sampler0` for the shader. */
    private val END_SKY_TEX: ResourceLocation =
        ResourceLocation("textures/environment/end_sky.png")

    /** Vanilla's end-portal star texture — bound to `Sampler1` for the per-layer loop. */
    private val END_PORTAL_TEX: ResourceLocation =
        ResourceLocation("textures/entity/end_portal.png")

    val ABYSS_PORTAL: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_abyss_portal",
        // POSITION only — noise / alpha / blend are all computed per-pixel in the
        // fragment shader from the Progress / NoiseDrift / NoiseSeed uniforms, so
        // the host doesn't need to encode anything per-vertex. One full-screen quad
        // is enough.
        DefaultVertexFormat.POSITION,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shaderInstance })
            // Same texture bindings as vanilla's `RenderType.endPortal()` — slot 0 is the
            // nebula base, slot 1 is the per-layer star pattern. Matches vanilla's
            // [TheEndPortalRenderer] setup exactly so the visual is byte-identical.
            .setTextureState(
                RenderStateShard.MultiTextureStateShard.builder()
                    .add(END_SKY_TEX, false, false)
                    .add(END_PORTAL_TEX, false, false)
                    .build()
            )
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false)
    )

    /** True once the resource manager has loaded the shader sources at least once. */
    fun isReady(): Boolean = shaderInstance != null

    /** The loaded shader instance, for setting custom uniforms (Progress, NoiseDrift,
     *  NoiseSeed, ScreenSize) before draw. Null until [ClientReloadShadersEvent] has
     *  fired at least once — callers must null-check or gate on [isReady]. */
    fun getShader(): ShaderInstance? = shaderInstance

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            // 1.20.1 quirk: vanilla [ShaderInstance] constructs its JSON / vsh / fsh paths
            // as `new ResourceLocation("shaders/core/" + name + ".json")`, with no support
            // for a namespaced `name`. Passing `enderkinesis:rendertype_abyss_portal` yields
            // `"shaders/core/enderkinesis:rendertype_abyss_portal.json"`; the ResourceLocation
            // parser splits at the first `:` and decides namespace = `"shaders/core/enderkinesis"`,
            // which contains `/` and blows up with `Non [a-z0-9_.-] character in namespace`.
            //
            // Workaround: pass the name path-only, and wrap the resource provider so any
            // `minecraft:...` lookup is retried in our namespace first. This catches the
            // JSON, vsh, and fsh lookups (all path-only inside ShaderInstance) without
            // forcing us to dump files into vanilla's namespace.
            sink.registerShader(
                ShaderInstance(
                    namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_abyss_portal",
                    DefaultVertexFormat.POSITION,
                )
            ) { instance -> shaderInstance = instance }
        }
    }

    /** Wraps a [ResourceProvider] so any `minecraft:`-namespaced lookup is tried in
     *  [namespace] first, falling back to the original lookup if absent. Lets vanilla
     *  shader-loading code (which hard-codes the `minecraft:` namespace via string
     *  concat) load files placed in our mod's namespace. */
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
