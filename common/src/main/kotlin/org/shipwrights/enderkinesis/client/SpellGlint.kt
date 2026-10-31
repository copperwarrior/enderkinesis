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
 * The glint on an imbued item: vanilla's enchantment glint, scrolled the same way, but in the
 * spell's colour (`rendertype_spell_glint`), in place of vanilla's purple. Drawn over an ordinary
 * item's model by `ItemRendererSpellGlintMixin`; over everything that draws its glint through
 * vanilla's glint buffers instead (a shield or trident in the hand, armour worn, a thrown
 * trident while its spell waits in it) by swapping those buffers for these ([over]).
 *
 * As vanilla's: [GLINT] for items, [ENTITY_GLINT] for entity models (a shield, a trident), and
 * [ARMOR_GLINT] for armour worn, each with its own scale of the glint texture.
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

    /** Every one of these, each needing a buffer of its own, as vanilla's glints have. */
    @JvmField
    val TYPES: List<RenderType> = listOf(GLINT, ENTITY_GLINT, ARMOR_GLINT)

    fun registerShader(instance: ShaderInstance) {
        shader = instance
    }

    // ---- Each imbue's colour, worked out once ------------------------------------------------------

    /** The imbue tags last seen, by identity, and their colours: an item's tag is the same object
     *  frame after frame, so its spell needn't be read again each time it's drawn. */
    private const val CACHE = 64
    private val cachedTags = arrayOfNulls<CompoundTag>(CACHE)
    private val cachedColours = IntArray(CACHE)

    /** The colour of [stack]'s imbue's glint, or null if it isn't imbued (or the shader isn't
     *  loaded yet). */
    @JvmStatic
    fun colourOf(stack: ItemStack): Int? {
        if (shader == null) return null
        val tag = stack.tag ?: return null
        if (!tag.contains(Imbues.NBT, Tag.TAG_COMPOUND.toInt())) return null
        val imbue = tag.getCompound(Imbues.NBT)
        val slot = Math.floorMod(System.identityHashCode(imbue), CACHE)
        if (cachedTags[slot] === imbue) return cachedColours[slot]
        val rgb = Imbues.of(stack)?.spell?.markColour() ?: return null
        cachedTags[slot] = imbue
        cachedColours[slot] = rgb
        return rgb
    }

    /** The glint's buffer, every vertex in [rgb]. */
    @JvmStatic
    fun buffer(buffers: MultiBufferSource, rgb: Int): VertexConsumer = Tinted(buffers.getBuffer(GLINT), rgb)

    /** [buffers], but drawing vanilla's glints as this one in [rgb] (null: [buffers] as it is). */
    @JvmStatic
    fun over(buffers: MultiBufferSource, rgb: Int?): MultiBufferSource = if (rgb == null) buffers else Swapped(buffers, rgb)

    /** [buffers], but drawing [stack]'s glint as its imbue's, if it's imbued. */
    @JvmStatic
    fun over(buffers: MultiBufferSource, stack: ItemStack): MultiBufferSource = over(buffers, colourOf(stack))

    /** Hands out this glint, tinted, wherever vanilla's is asked for. */
    private class Swapped(private val inner: MultiBufferSource, private val rgb: Int) : MultiBufferSource {
        override fun getBuffer(type: RenderType): VertexConsumer = when {
            type === RenderType.glint() || type === RenderType.glintDirect() || type === RenderType.glintTranslucent() -> Tinted(inner.getBuffer(GLINT), rgb)
            type === RenderType.entityGlint() || type === RenderType.entityGlintDirect() -> Tinted(inner.getBuffer(ENTITY_GLINT), rgb)
            type === RenderType.armorGlint() || type === RenderType.armorEntityGlint() -> Tinted(inner.getBuffer(ARMOR_GLINT), rgb)
            else -> inner.getBuffer(type)
        }
    }

    /** Passes every vertex on, but in one colour. */
    private class Tinted(private val inner: VertexConsumer, rgb: Int) : VertexConsumer {
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
