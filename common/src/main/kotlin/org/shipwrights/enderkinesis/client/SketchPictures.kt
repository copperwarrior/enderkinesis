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
    // By bytes, not count, so a wall of many sketches stays held and is not fetched again and again.
    private const val PACKED_BUDGET = 16L * 1024 * 1024
    private const val TEXTURE_BUDGET = 64L * 1024 * 1024
    private const val MAX_PARTIAL = 8

    // strike_sketch.fsh INK.
    private const val INK_ABGR = 0x004A4545

    // Render thread only.
    private val packed = LinkedHashMap<String, ByteArray>(LIMIT, 0.75f, true)
    private var packedBytes = 0L
    private val textures = LinkedHashMap<String, DynamicTexture>(LIMIT, 0.75f, true)
    private var textureBytes = 0L
    // Pictures still arriving in parts, by id.
    private val partial = LinkedHashMap<String, Array<ByteArray?>>()

    // Render thread only: when each missing picture was last asked for.
    private val requested = object : LinkedHashMap<String, Long>(LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > LIMIT * 4
    }

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SilverpointNetwork.DATA) { buf, ctx ->
            val part = SilverpointNetwork.readData(buf) ?: return@registerReceiver
            ctx.queue { receivePart(part) }
        }
    }

    fun hold(readout: StrikeReadout) {
        if (readout is PictureReadout) readout.packed?.let { put(readout.key, it) }
    }

    private fun receivePart(part: SilverpointNetwork.DataPart) {
        var parts = partial[part.id]
        if (parts == null || parts.size != part.total) {
            parts = arrayOfNulls(part.total)
            partial[part.id] = parts
            if (partial.size > MAX_PARTIAL) partial.remove(partial.keys.first())
        }
        parts[part.index] = part.bytes
        if (parts.any { it == null }) return
        partial.remove(part.id)
        put(part.id, SilverpointNetwork.join(parts))
    }

    /** Holds [picture] under [key], replacing any texture made before it came. */
    fun put(key: String, picture: ByteArray) {
        if (SilverpointPicture.sideOf(picture) == null) return
        packed.put(key, picture)?.let { packedBytes -= it.size }
        packedBytes += picture.size
        while (packedBytes > PACKED_BUDGET && packed.size > 1) {
            val eldest = packed.entries.first()
            packedBytes -= eldest.value.size
            packed.remove(eldest.key)
        }
        removeTexture(key)
        requested.remove(key)
    }

    private fun removeTexture(key: String) {
        val texture = textures.remove(key) ?: return
        textureBytes -= textureSize(texture)
        texture.close()
    }

    private fun textureSize(texture: DynamicTexture): Long {
        val pixels = texture.pixels ?: return 0L
        return pixels.width.toLong() * pixels.height * 4
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
        textureBytes += textureSize(texture)
        while (textureBytes > TEXTURE_BUDGET && textures.size > 1) removeTexture(textures.keys.first())
        return texture.id
    }
}
