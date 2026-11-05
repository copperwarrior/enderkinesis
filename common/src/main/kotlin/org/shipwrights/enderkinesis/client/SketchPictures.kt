package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
import org.shipwrights.enderkinesis.item.PictureReadout
import org.shipwrights.enderkinesis.item.SilverpointPicture
import org.shipwrights.enderkinesis.item.StrikeReadout

/** View sketches by key, so a `[sketch:picture=...]` tag can find the picture its paper holds. */
object SketchPictures {

    private const val LIMIT = 32

    // strike_sketch.fsh INK.
    private const val INK_ABGR = 0x004A4545

    // Render thread only.
    private val packed = object : LinkedHashMap<String, ByteArray>(LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > LIMIT
    }
    private val textures = object : LinkedHashMap<String, DynamicTexture>(LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DynamicTexture>): Boolean {
            if (size <= LIMIT) return false
            eldest.value.close()
            return true
        }
    }

    fun hold(readout: StrikeReadout) {
        if (readout is PictureReadout) packed[readout.key] = readout.packed
    }

    /** Top row first; the caller owns it. */
    fun image(key: String): NativeImage? {
        val picture = packed[key]?.let(SilverpointPicture::unpack) ?: return null
        val side = picture.side
        val image = NativeImage(side, side, false)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val alpha = picture.alpha[y * side + x].toInt() and 0xFF
                image.setPixelRGBA(x, y, (alpha shl 24) or INK_ABGR)
            }
        }
        return image
    }

    fun textureId(key: String): Int? {
        textures[key]?.let { return it.id }
        val image = image(key) ?: return null
        val texture = DynamicTexture(image)
        texture.setFilter(true, false)
        textures[key] = texture
        return texture.id
    }
}
