package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import org.shipwrights.enderkinesis.EnderkinesisMod
import java.util.function.Function
import java.util.function.Supplier

/**
 * A skin carved into the animated wogor log: `wood_statue.fsh` multiplies each bark pixel by the skin pixel's
 * luminance. The bark comes from the block atlas, so it animates with the logs and needs no baked texture.
 */
object WoodStatueRenderType {

    @Volatile
    @JvmField
    var shader: ShaderInstance? = null

    private val SHADER_SUPPLIER: Supplier<ShaderInstance> = Supplier { shader?.also { it.setWogorLogRect() } }

    // No overlay state: it binds texture unit 1, which holds the block atlas here.
    private val BY_SKIN: Function<ResourceLocation, RenderType> = Util.memoize { skin: ResourceLocation ->
        RenderType.create(
            "enderkinesis:wood_statue",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            BUFFER_SIZE,
            true,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.ShaderStateShard(SHADER_SUPPLIER))
                .setTextureState(
                    RenderStateShard.MultiTextureStateShard.builder()
                        .add(skin, false, false)
                        .add(InventoryMenu.BLOCK_ATLAS, false, false)
                        .build(),
                )
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setCullState(RenderStateShard.NO_CULL)
                .createCompositeState(true),
        )
    }

    fun of(skin: ResourceLocation): RenderType = BY_SKIN.apply(skin)

    private const val BUFFER_SIZE = 256
}

private val WOGOR_LOG_SPRITE = EnderkinesisMod.id("block/wogor_log_animated")

/** Sets `LogRect` to the animated wogor log sprite. It changes only on a resource reload, so read it at setup. */
fun ShaderInstance.setWogorLogRect() {
    val sprite = Minecraft.getInstance().modelManager.getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(WOGOR_LOG_SPRITE)
    safeGetUniform("LogRect").set(sprite.u0, sprite.v0, sprite.u1, sprite.v1)
}
