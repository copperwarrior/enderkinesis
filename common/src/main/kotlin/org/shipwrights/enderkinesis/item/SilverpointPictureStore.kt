package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.world.level.storage.LevelResource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Viewfinder pictures by id, one file each in the world's data folder, so a paper holds only the id. */
object SilverpointPictureStore {
    private val LOG = LogUtils.getLogger()
    private const val CACHE = 64

    // Server thread only.
    private var root: Path? = null
    private val cache = object : LinkedHashMap<String, ByteArray>(CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > CACHE
    }

    fun init() {
        LifecycleEvent.SERVER_STARTED.register { server ->
            root = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("enderkinesis_pictures")
            cache.clear()
        }
        LifecycleEvent.SERVER_STOPPED.register {
            root = null
            cache.clear()
        }
    }

    fun contains(id: String): Boolean = cache.containsKey(id) || file(id)?.let(Files::exists) == true

    fun put(id: String, packed: ByteArray) {
        require(SilverpointPicture.isId(id)) { "bad picture id: $id" }
        cache[id] = packed
        val file = file(id) ?: return
        try {
            Files.createDirectories(file.parent)
            Files.write(file, packed)
        } catch (e: IOException) {
            LOG.error("Cannot save Silverpoint picture {}", id, e)
        }
    }

    fun get(id: String): ByteArray? {
        cache[id]?.let { return it }
        val file = file(id)?.takeIf(Files::exists) ?: return null
        return try {
            Files.readAllBytes(file).takeIf { SilverpointPicture.sideOf(it) != null }?.also { cache[id] = it }
        } catch (e: IOException) {
            LOG.error("Cannot read Silverpoint picture {}", id, e)
            null
        }
    }

    // The id is 16 hex digits (checked), so it is a safe file name.
    private fun file(id: String): Path? = if (SilverpointPicture.isId(id)) root?.resolve("$id.bin") else null
}
