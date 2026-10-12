package org.shipwrights.enderkinesis.item

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.RandomSource
import net.minecraft.util.profiling.ProfilerFiller

/** Data-driven Old Paper texts. Each `data/<ns>/scraps/<name>.json` is one category, keyed
 *  `<ns>:<name>`, holding `{"scraps": ["text", "text", ...]}`. */
object Scraps : SimpleJsonResourceReloadListener(Gson(), "scraps") {

    private val LOG = LogUtils.getLogger()

    @Volatile
    private var byCategory: Map<ResourceLocation, List<String>> = emptyMap()

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = HashMap<ResourceLocation, List<String>>()
        for ((category, element) in objects) {
            val entries = (element as? JsonObject)?.getAsJsonArray("scraps")
            if (entries == null) {
                LOG.warn("Scrap category {} has no \"scraps\" array", category)
                continue
            }
            loaded[category] = entries.mapNotNull { entry ->
                if (entry.isJsonPrimitive) entry.asString else null
            }
        }
        byCategory = loaded
    }

    fun random(category: ResourceLocation, random: RandomSource): String? {
        val list = byCategory[category]
        if (list.isNullOrEmpty()) return null
        return list[random.nextInt(list.size)]
    }
}
