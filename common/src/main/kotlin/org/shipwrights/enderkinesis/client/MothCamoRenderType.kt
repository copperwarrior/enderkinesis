package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.world.inventory.InventoryMenu
import org.shipwrights.enderkinesis.entity.SselithMothEntity
import java.util.function.Supplier

/**
 * Camouflage RenderType for landed Sselith Moths: the perched block's
 * texture (Sampler0 = block atlas) is planar-projected onto the moth,
 * masked by the moth texture's alpha (Sampler1). Drawn per entity by
 * [SselithMothCamoLayer], which sets the projection uniforms and flushes
 * the batch so each moth gets its own block sprite.
 *
 * No overlay state on purpose: vanilla's hurt-flash overlay binds texture
 * unit 1, which would clobber the moth alpha mask.
 */
object MothCamoRenderType {

    @Volatile
    @JvmField
    var shader: ShaderInstance? = null

    @JvmStatic
    val SHADER_SUPPLIER: Supplier<ShaderInstance> = Supplier { shader }

    @JvmField
    val TYPE: RenderType = RenderType.create(
        "enderkinesis:moth_camo",
        DefaultVertexFormat.NEW_ENTITY,
        VertexFormat.Mode.QUADS,
        4096,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard(SHADER_SUPPLIER))
            .setTextureState(
                RenderStateShard.MultiTextureStateShard.builder()
                    .add(InventoryMenu.BLOCK_ATLAS, false, false)
                    .add(SselithMothEntity.TEXTURE, false, false)
                    .build(),
            )
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setLightmapState(RenderStateShard.LIGHTMAP)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(true),
    )
}
