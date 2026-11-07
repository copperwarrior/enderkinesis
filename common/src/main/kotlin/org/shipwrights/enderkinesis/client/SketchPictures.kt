package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import dev.architectury.networking.NetworkManager
import net.minecraft.Util
import net.minecraft.client.renderer.texture.DynamicTexture
import org.shipwrights.enderkinesis.item.PictureReadout
import org.shipwrights.enderkinesis.item.SilverpointNetwork
import org.shipwrights.enderkinesis.item.SilverpointPicture
import org.shipwrights.enderkinesis.item.StrikeReadout

/**
 * View sketches by key, so a `[sketch:picture=...]` tag can find the picture its paper holds. A picture this client
 * does not have is fetched from the server's store.
 */
object SketchPictures {

    private const val LIMIT = 32
    private const val FETCH_RETRY_MS = 5_000L

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

    // Render thread only: when each missing picture was last asked for.
    private val requested = object : LinkedHashMap<String, Long>(LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > LIMIT * 4
    }

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SilverpointNetwork.DATA) { buf, ctx ->
            val (id, picture) = SilverpointNetwork.readData(buf)
            ctx.queue { put(id, picture) }
        }
    }

    fun hold(readout: StrikeReadout) {
        if (readout is PictureReadout) readout.packed?.let { put(readout.key, it) }
    }

    /** Holds [picture] under [key], replacing any texture made before it came. */
    fun put(key: String, picture: ByteArray) {
        if (SilverpointPicture.sideOf(picture) == null) return
        packed[key] = picture
        textures.remove(key)?.close()
        requested.remove(key)
    }

    private fun packedFor(key: String): ByteArray? {
        packed[key]?.let { return it }
        if (!SilverpointPicture.isId(key)) return null
        val now = Util.getMillis()
        if (requested[key]?.let { now - it < FETCH_RETRY_MS } == true) return null
        requested[key] = now
        SilverpointNetwork.sendFetch(key)
        return null
    }

    /** Top row first; the caller owns it. */
    fun image(key: String): NativeImage? {
        val picture = packedFor(key)?.let(SilverpointPicture::unpack) ?: return null
        val side = picture.side
        val image = NativeImage(side, side, false)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val i = y * side + x
                image.setPixelRGBA(x, y, picture.abgr?.get(i) ?: (((picture.alpha[i].toInt() and 0xFF) shl 24) or INK_ABGR))
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
