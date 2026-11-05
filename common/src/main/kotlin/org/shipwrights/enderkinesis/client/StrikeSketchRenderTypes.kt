package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.mixin.CompositeRenderTypeAccessor
import org.shipwrights.enderkinesis.mixin.CompositeStateAccessor
import org.shipwrights.enderkinesis.mixin.EmptyTextureStateShardInvoker
import java.util.function.Supplier

/** Writes view-space face normals, for the sketch's inner edges. NEW_ENTITY so block and BEWLR quads both fit. */
object StrikeSketchRenderTypes {

    private const val BUFFER_SIZE = 256
    private const val WHITE = -1

    @Volatile
    @JvmField
    var normalsShader: ShaderInstance? = null

    private val NORMALS_SHADER: Supplier<ShaderInstance> = Supplier { normalsShader }

    // Render thread only; one normals type for each texture.
    private val byTexture = HashMap<ResourceLocation, RenderType>()

    /** A 1×1 white texture, for draws with no texture of their own. */
    val white: ResourceLocation by lazy {
        val image = NativeImage(1, 1, false).apply { setPixelRGBA(0, 0, WHITE) }
        Minecraft.getInstance().textureManager.register("enderkinesis_sketch_white", DynamicTexture(image))
    }

    /**
     * Every draw as normals, in the texture of its own render type so that clear pixels drop out. A draw in another
     * vertex format, such as sign text or lines, has elements that would not fit, and no face to give.
     */
    fun normalsSource(buffers: MultiBufferSource): MultiBufferSource = MultiBufferSource { type ->
        if (type.format() == DefaultVertexFormat.NEW_ENTITY) buffers.getBuffer(normals(textureOf(type) ?: white)) else Discard
    }

    private fun textureOf(type: RenderType): ResourceLocation? = runCatching {
        val state = (type as CompositeRenderTypeAccessor).`enderkinesis$state`()
        ((state as CompositeStateAccessor).`enderkinesis$textureState`() as EmptyTextureStateShardInvoker)
            .`enderkinesis$cutoutTexture`().orElse(null)
    }.getOrNull()

    private fun normals(texture: ResourceLocation): RenderType = byTexture.getOrPut(texture) {
        RenderType.create(
            "enderkinesis:strike_normals",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            BUFFER_SIZE,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.ShaderStateShard(NORMALS_SHADER))
                .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
                .createCompositeState(false),
        )
    }

    private object Discard : VertexConsumer {
        override fun vertex(x: Double, y: Double, z: Double) = this
        override fun color(r: Int, g: Int, b: Int, a: Int) = this
        override fun uv(u: Float, v: Float) = this
        override fun overlayCoords(u: Int, v: Int) = this
        override fun uv2(u: Int, v: Int) = this
        override fun normal(x: Float, y: Float, z: Float) = this
        override fun endVertex() = Unit
        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) = Unit
        override fun unsetDefaultColor() = Unit
    }
}
