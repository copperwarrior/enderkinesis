package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod

/** A region of a texture to draw: [texture] from ([u0], [v0]) to ([u1], [v1]). */
class Sprite(val texture: ResourceLocation, val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    fun u(f: Float) = u0 + (u1 - u0) * f
    fun v(f: Float) = v0 + (v1 - v0) * f

    /** The part of this sprite from [fu0], [fv0] to [fu1], [fv1] (fractions of it). */
    fun sub(fu0: Float, fv0: Float, fu1: Float, fv1: Float) = Sprite(texture, u(fu0), v(fv0), u(fu1), v(fv1))
}

/**
 * The art spell geometry is drawn with: vanilla's own particle and block textures, read from
 * their atlases, and the mod's Ygann art. Sprites are looked up once and kept until the next
 * resource reload, when the atlases are rebuilt.
 */
object SpellArt {
    private val cache = HashMap<String, Sprite>()

    private val SEA: ResourceLocation = EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_mass_sea.png")

    /** Called on resource reload: the atlases have been rebuilt. */
    fun invalidate() = cache.clear()

    /** A particle texture, by its name in `textures/particle` (`"flame"`). */
    fun particle(name: String): Sprite = cache.getOrPut("p:$name") { atlas(TextureAtlas.LOCATION_PARTICLES, ResourceLocation(name)) }

    /** A block texture, by its name in `textures/block` (`"packed_ice"`). Animated ones (fire)
     *  animate in the atlas by themselves. */
    fun block(name: String): Sprite = cache.getOrPut("b:$name") { atlas(TextureAtlas.LOCATION_BLOCKS, ResourceLocation("block/$name")) }

    /** Through the texture manager: the particle atlas belongs to the particle engine, not the
     *  model manager, so `Minecraft.getTextureAtlas` doesn't know it. */
    private fun atlas(atlas: ResourceLocation, name: ResourceLocation): Sprite {
        val s = (Minecraft.getInstance().textureManager.getTexture(atlas) as TextureAtlas).getSprite(name)
        return Sprite(atlas, s.u0, s.v0, s.u1, s.v1)
    }

    /** The whole writhing mass tile, for laying the mass over blocks. */
    fun sea(): Sprite = Sprite(SEA, 0f, 0f, 1f, 1f)

    /** Leaf outlines for leaf chips, 5x5 pixels: # leaf, s stem, . clear. */
    val LEAF_MASKS: Array<Array<String>> = arrayOf(
        arrayOf("..#..", ".###.", ".###.", "..#..", "..s.."),
        arrayOf("...##", "..###", ".###.", "s##..", "s...."),
        arrayOf(".##..", "####.", ".####", "..##.", "...s."),
        arrayOf("..#..", ".##..", ".###.", "..##.", "..s.."),
        arrayOf("##...", "###..", ".###.", "..##s", "....."),
        arrayOf(".#.#.", "#####", ".###.", "..#..", "..s.."),
    )
}
