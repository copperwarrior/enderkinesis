package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod

class Sprite(val texture: ResourceLocation, val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    fun u(f: Float) = u0 + (u1 - u0) * f
    fun v(f: Float) = v0 + (v1 - v0) * f

    /** The arguments are fractions of this sprite, not atlas UVs. */
    fun sub(fu0: Float, fv0: Float, fu1: Float, fv1: Float) = Sprite(texture, u(fu0), v(fv0), u(fu1), v(fv1))
}

object SpellArt {
    private val particles = HashMap<String, Sprite>()
    private val blocks = HashMap<String, Sprite>()

    private const val GLYPHS = 26
    private const val FRAMES = 8
    private val GLYPH_NAMES = Array(GLYPHS) { "sga_" + ('a' + it) }
    private val SPARK_NAMES = Array(FRAMES) { "spark_$it" }
    private val GLITTER_NAMES = Array(FRAMES) { "glitter_$it" }
    private val GENERIC_NAMES = Array(FRAMES) { "generic_$it" }

    private val SEA: ResourceLocation = EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_mass_sea.png")

    /** Call on resource reload, because the atlases change and the cached sprites become wrong. */
    fun invalidate() {
        particles.clear()
        blocks.clear()
    }

    /** [name] is a path in `textures/particle`, for example `"flame"`. */
    fun particle(name: String): Sprite = particles.getOrPut(name) { atlas(PARTICLE_ATLAS, ResourceLocation(name)) }

    fun glyph(i: Int): Sprite = particle(GLYPH_NAMES[Math.floorMod(i, GLYPHS)])

    fun spark(n: Int): Sprite = particle(SPARK_NAMES[Math.floorMod(n, FRAMES)])
    fun glitter(n: Int): Sprite = particle(GLITTER_NAMES[Math.floorMod(n, FRAMES)])
    fun generic(n: Int): Sprite = particle(GENERIC_NAMES[Math.floorMod(n, FRAMES)])

    /** [name] is a path in `textures/block`, for example `"packed_ice"`. */
    fun block(name: String): Sprite = blocks.getOrPut(name) { atlas(BLOCK_ATLAS, ResourceLocation("block/$name")) }

    // The particle atlas belongs to the particle engine, so `Minecraft.getTextureAtlas` cannot find it.
    private fun atlas(atlas: ResourceLocation, name: ResourceLocation): Sprite {
        val s = (Minecraft.getInstance().textureManager.getTexture(atlas) as TextureAtlas).getSprite(name)
        return Sprite(atlas, s.u0, s.v0, s.u1, s.v1)
    }

    fun sea(): Sprite = Sprite(SEA, 0f, 0f, 1f, 1f)

    /** 5x5 pixels: # leaf, s stem, . clear. */
    val LEAF_MASKS: Array<Array<String>> = arrayOf(
        arrayOf("..#..", ".###.", ".###.", "..#..", "..s.."),
        arrayOf("...##", "..###", ".###.", "s##..", "s...."),
        arrayOf(".##..", "####.", ".####", "..##.", "...s."),
        arrayOf("..#..", ".##..", ".###.", "..##.", "..s.."),
        arrayOf("##...", "###..", ".###.", "..##s", "....."),
        arrayOf(".#.#.", "#####", ".###.", "..#..", "..s.."),
    )
}
