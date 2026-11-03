package org.shipwrights.enderkinesis.dimension

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller

/** Dimensions the Wehelo Blade can send a target to. Entries are unioned across every file. */
object WeheloDestinations : SimpleJsonResourceReloadListener(Gson(), "wehelo_destinations") {

    @Volatile private var dimensions: Set<ResourceLocation> = emptySet()

    override fun apply(
        objects: MutableMap<ResourceLocation, JsonElement>,
        manager: ResourceManager,
        profiler: ProfilerFiller,
    ) {
        val acc = HashSet<ResourceLocation>()
        for (element in objects.values) {
            val obj = element as? JsonObject ?: continue
            obj.getAsJsonArray("dimensions")?.forEach { entry ->
                runCatching { ResourceLocation.tryParse(entry.asString) }.getOrNull()?.let(acc::add)
            }
        }
        dimensions = acc
    }

    fun contains(dimension: ResourceLocation): Boolean = dimension in dimensions
}
