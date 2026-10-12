package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.TextureAtlas
import java.util.function.Supplier

/**
 * Custom chunk-mesh RenderType for the Moth Cloth glint overlay. Registered
 * additionally to the vanilla `SOLID` pass — the base wool colour comes from
 * SOLID (which VS2 also renders on ships), and this RenderType layers a
 * voronoi-glint shader on top.
 */
object MothClothRenderType {

    @Volatile
    @JvmField
    var shader: ShaderInstance? = null

    @Volatile
    private var loggedNullShader: Boolean = false

    @JvmStatic
    val SHADER_SUPPLIER: Supplier<ShaderInstance> = Supplier {
        val current = shader
        if (current == null && !loggedNullShader) {
            loggedNullShader = true
            org.slf4j.LoggerFactory.getLogger("MothClothRenderType")
                .warn("Moth Cloth glint shader is null at draw time — falling back to vanilla rendertype_solid. Glint won't appear until resource pack finishes loading.")
        }
        current ?: net.minecraft.client.renderer.GameRenderer.getRendertypeSolidShader()
    }

    @JvmField
    val TYPE: RenderType = RenderType.create(
        "enderkinesis:moth_cloth",
        DefaultVertexFormat.BLOCK,
        VertexFormat.Mode.QUADS,
        2097152,
        false,
        true,
        RenderType.CompositeState.builder()
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setShaderState(RenderStateShard.ShaderStateShard(SHADER_SUPPLIER))
            .setTextureState(
                RenderStateShard.TextureStateShard(
                    TextureAtlas.LOCATION_BLOCKS, false, true,
                ),
            )
            // Additive over the solid pass — glint colour adds to the wool.
            .setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
            // Color-only write mask so overlay quads don't write depth
            // (nothing behind them gets occluded).
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            // The overlay re-emits the same quads the solid wool pass drew,
            // but through a different matrix chain — depth differs by float
            // epsilon, so without polygon offset the LEQUAL test patchily
            // culls the overlay (same fix vanilla uses for crumbling).
            .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
            .createCompositeState(true),
    )
}
