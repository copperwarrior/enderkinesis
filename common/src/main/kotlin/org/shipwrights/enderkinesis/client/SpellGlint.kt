package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.spell.Imbues

/**
 * `ItemRendererSpellGlintMixin` draws ordinary items. Shields, tridents and worn armour draw through
 * vanilla glint buffers, so [over] swaps those buffers for these types.
 */
object SpellGlint {
    private var shader: ShaderInstance? = null

    private fun glintType(name: String, texture: ResourceLocation, texturing: RenderStateShard.TexturingStateShard, armour: Boolean): RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_$name",
        DefaultVertexFormat.POSITION_COLOR_TEX,
        VertexFormat.Mode.QUADS,
        256,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.ShaderStateShard { shader })
            .setTextureState(RenderStateShard.TextureStateShard(texture, true, false))
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .setDepthTestState(RenderStateShard.EQUAL_DEPTH_TEST)
            .setTransparencyState(RenderStateShard.GLINT_TRANSPARENCY)
            .setTexturingState(texturing)
            .apply { if (armour) setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING) }
            .createCompositeState(false),
    )

    @JvmField
    val GLINT: RenderType = glintType("spell_glint", ItemRenderer.ENCHANTED_GLINT_ITEM, RenderStateShard.GLINT_TEXTURING, armour = false)

    @JvmField
    val ENTITY_GLINT: RenderType = glintType("spell_glint_entity", ItemRenderer.ENCHANTED_GLINT_ENTITY, RenderStateShard.ENTITY_GLINT_TEXTURING, armour = false)

    @JvmField
    val ARMOR_GLINT: RenderType = glintType("spell_glint_armor", ItemRenderer.ENCHANTED_GLINT_ENTITY, RenderStateShard.ENTITY_GLINT_TEXTURING, armour = true)

    /** Each type needs a buffer of its own, as the vanilla glints do. */
    @JvmField
    val TYPES: List<RenderType> = listOf(GLINT, ENTITY_GLINT, ARMOR_GLINT)

    fun registerShader(instance: ShaderInstance) {
        shader = instance
    }

    // An item tag is the same object each frame, so the cache keys on tag identity.
    private const val CACHE = 64
    private val cachedTags = arrayOfNulls<CompoundTag>(CACHE)
    private val cachedColours = IntArray(CACHE)

    const val NONE = -1

    /** Returns 0xRRGGBB, or [NONE] when the stack has no imbue or the shader is not loaded. */
    @JvmStatic
    fun colourOf(stack: ItemStack): Int {
        if (shader == null) return NONE
        val tag = stack.tag ?: return NONE
        if (!tag.contains(Imbues.NBT, Tag.TAG_COMPOUND.toInt())) return NONE
        val imbue = tag.getCompound(Imbues.NBT)
        val slot = Math.floorMod(System.identityHashCode(imbue), CACHE)
        if (cachedTags[slot] === imbue) return cachedColours[slot]
        val rgb = (Imbues.of(stack)?.spell?.markColour() ?: return NONE) and 0xFFFFFF
        cachedTags[slot] = imbue
        cachedColours[slot] = rgb
        return rgb
    }

    // Render thread only. The wrappers have no state, so the last one is reused for the same target and colour.
    private var lastTinted: Tinted? = null
    private var lastSwapped: Swapped? = null

    private fun tinted(inner: VertexConsumer, rgb: Int): Tinted {
        lastTinted?.let { if (it.inner === inner && it.rgb == rgb) return it }
        return Tinted(inner, rgb).also { lastTinted = it }
    }

    @JvmStatic
    fun buffer(buffers: MultiBufferSource, rgb: Int): VertexConsumer = tinted(buffers.getBuffer(GLINT), rgb)

    @JvmStatic
    fun over(buffers: MultiBufferSource, rgb: Int?): MultiBufferSource = if (rgb == null) buffers else overColour(buffers, rgb)

    @JvmStatic
    fun over(buffers: MultiBufferSource, stack: ItemStack): MultiBufferSource {
        val rgb = colourOf(stack)
        return if (rgb == NONE) buffers else overColour(buffers, rgb)
    }

    private fun overColour(buffers: MultiBufferSource, rgb: Int): MultiBufferSource {
        lastSwapped?.let { if (it.inner === buffers && it.rgb == rgb) return it }
        return Swapped(buffers, rgb).also { lastSwapped = it }
    }

    private class Swapped(val inner: MultiBufferSource, val rgb: Int) : MultiBufferSource {
        override fun getBuffer(type: RenderType): VertexConsumer = when {
            type === RenderType.glint() || type === RenderType.glintDirect() || type === RenderType.glintTranslucent() -> tinted(inner.getBuffer(GLINT), rgb)
            type === RenderType.entityGlint() || type === RenderType.entityGlintDirect() -> tinted(inner.getBuffer(ENTITY_GLINT), rgb)
            type === RenderType.armorGlint() || type === RenderType.armorEntityGlint() -> tinted(inner.getBuffer(ARMOR_GLINT), rgb)
            else -> inner.getBuffer(type)
        }
    }

    private class Tinted(val inner: VertexConsumer, val rgb: Int) : VertexConsumer {
        private val r = (rgb shr 16) and 0xFF
        private val g = (rgb shr 8) and 0xFF
        private val b = rgb and 0xFF

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer { inner.vertex(x, y, z); return this }
        override fun color(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer { inner.color(r, g, b, 255); return this }
        override fun uv(u: Float, v: Float): VertexConsumer { inner.uv(u, v); return this }
        override fun overlayCoords(u: Int, v: Int): VertexConsumer { inner.overlayCoords(u, v); return this }
        override fun uv2(u: Int, v: Int): VertexConsumer { inner.uv2(u, v); return this }
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer { inner.normal(x, y, z); return this }
        override fun endVertex() = inner.endVertex()
        override fun defaultColor(red: Int, green: Int, blue: Int, alpha: Int) = inner.defaultColor(r, g, b, 255)
        override fun unsetDefaultColor() = inner.unsetDefaultColor()
    }
}
