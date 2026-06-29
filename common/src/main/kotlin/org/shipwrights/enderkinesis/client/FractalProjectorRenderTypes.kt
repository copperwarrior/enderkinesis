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
 * Two render types + shaders for the Fractal Projector BER:
 *
 *  - [FRACTAL_SPHERE] (solid) carries the fractal pattern. The BER
 *    rotates this mesh's pose with `Camera.rotation()` so the same
 *    mesh-local hemisphere always faces the player; the fragment
 *    shader samples the fractal at the mesh-local surface normal, so
 *    the visible pattern stays oriented to the camera while the mesh
 *    itself is a real 3D sphere.
 *
 *  - [OVERLAY_SPHERE] (translucent) sits on top — same world position,
 *    NOT rotated with the camera. Its fragment shader paints the
 *    yellow-to-white fresnel glass shell + the holy-number highlight
 *    pinned to a fixed object-space direction. Depth-test on,
 *    depth-write off so the front of the shell alpha-blends over the
 *    fractal sphere and the back fails depth against it.
 *
 * Both meshes share `POSITION_COLOR_NORMAL`.
 */
object FractalProjectorRenderTypes {

    private var fractalShader: ShaderInstance? = null
    private var haloShader: ShaderInstance? = null
    private var overlayShader: ShaderInstance? = null

    // 6-face cubemap captured with the Panoramica mod. Convention matches
    // vanilla `CubeMap.images[i]`: panorama_0 = south, _1 = west, _2 =
    // north, _3 = east, _4 = up, _5 = down. The fragment shader picks the
    // dominant axis of the ray direction and samples the matching face;
    // if the orientation comes out rotated/flipped after testing, the
    // simplest fix is to renumber the files rather than fight the UV
    // signs in the shader.
    private val PANORAMA_SOUTH = ResourceLocation("enderkinesis", "textures/environment/cubemap/panorama_0.png")
    private val PANORAMA_WEST  = ResourceLocation("enderkinesis", "textures/environment/cubemap/panorama_1.png")
    private val PANORAMA_NORTH = ResourceLocation("enderkinesis", "textures/environment/cubemap/panorama_2.png")
    private val PANORAMA_EAST  = ResourceLocation("enderkinesis", "textures/environment/cubemap/panorama_3.png")
    private val PANORAMA_UP    = ResourceLocation("enderkinesis", "textures/environment/cubemap/panorama_4.png")
    private val PANORAMA_DOWN  = ResourceLocation("enderkinesis", "textures/environment/cubemap/panorama_5.png")

    // Vanilla SGA font — same texture MC binds for the `minecraft:alt`
    // font style that `Sga.obfuscate(...)` and TomeSummonOverlay use.
    // Sampled per-letter to draw real Sselith glyph words into the
    // cubemap reflection.
    private val SGA_FONT = ResourceLocation("minecraft", "textures/font/ascii_sga.png")

    val FRACTAL_SPHERE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_fractal_projector_fractal",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.TRIANGLES,
        4096,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { fractalShader })
            // The 6 panorama faces are bound to Sampler0..Sampler5 in the
            // order added below. The fragment shader's `sampleCubemap()`
            // matches this order via the per-axis selection.
            .setTextureState(
                RenderStateShard.MultiTextureStateShard.builder()
                    .add(PANORAMA_SOUTH, false, false)
                    .add(PANORAMA_WEST,  false, false)
                    .add(PANORAMA_NORTH, false, false)
                    .add(PANORAMA_EAST,  false, false)
                    .add(PANORAMA_UP,    false, false)
                    .add(PANORAMA_DOWN,  false, false)
                    .add(SGA_FONT,       false, false)
                    .build()
            )
            .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false)
    )

    /** Additive halo render type for the bloom around the orb. Renders
     *  a larger sphere mesh (HALO_RADIUS = 2.2) per BER emit; the halo
     *  fragment shader discards fragments where the camera ray hits the
     *  inner orb so glow only appears outside the silhouette. Additive
     *  blending so the glow brightens whatever is behind it. No depth
     *  write — distant geometry isn't occluded by the glow. */
    val HALO_SPHERE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_fractal_projector_halo",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.TRIANGLES,
        4096,
        false,
        // sortOnUpload=false. ADDITIVE blending is commutative so back-
        // to-front sorting isn't required, and MC's sorter is quad-
        // oriented — running it on TRIANGLES re-orders verts in a way
        // that breaks the mesh.
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { haloShader })
            .setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            // NO_CULL because the halo shader handles back-fragment discard
            // itself; default back-cull plus mesh winding ambiguity was
            // silently culling the visible side.
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false)
    )

    val OVERLAY_SPHERE: RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_fractal_projector_overlay",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.TRIANGLES,
        4096,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { overlayShader })
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            // Depth WRITE off so successive translucent surfaces composite
            // without one masking the next (vanilla stained-glass pattern).
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false)
    )

    fun isReady(): Boolean = fractalShader != null && overlayShader != null && haloShader != null
    fun getFractalShader(): ShaderInstance? = fractalShader
    fun getHaloShader(): ShaderInstance? = haloShader
    fun getOverlayShader(): ShaderInstance? = overlayShader

    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            val ns = namespaced(provider, EnderkinesisMod.MOD_ID)
            sink.registerShader(
                ShaderInstance(ns, "rendertype_fractal_projector_fractal", DefaultVertexFormat.POSITION_COLOR_NORMAL)
            ) { instance -> fractalShader = instance }
            sink.registerShader(
                ShaderInstance(ns, "rendertype_fractal_projector_halo", DefaultVertexFormat.POSITION_COLOR_NORMAL)
            ) { instance -> haloShader = instance }
            sink.registerShader(
                ShaderInstance(ns, "rendertype_fractal_projector_overlay", DefaultVertexFormat.POSITION_COLOR_NORMAL)
            ) { instance -> overlayShader = instance }
        }
    }

    /** Vanilla [ShaderInstance] builds the resource path as `"shaders/core/" +
     *  name + ".json"` and bombs on a namespaced name, so wrap the provider
     *  to retry `minecraft:` lookups under our namespace first. */
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
