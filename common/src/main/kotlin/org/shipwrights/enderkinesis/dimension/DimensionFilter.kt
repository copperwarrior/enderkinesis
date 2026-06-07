package org.shipwrights.enderkinesis.dimension

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller

/**
 * Data-driven allow/deny filter for dimensions the Almanac and Ender Astrolabe may target.
 *
 * Loaded from JSON files under the `dimension_filter` data folder, each file shaped like:
 * ```json
 * { "allow": ["minecraft:.*", "enderkinesis:.*"], "deny": ["minecraft:the_end"] }
 * ```
 * `allow`/`deny` entries are regexes matched against the full dimension id (`namespace:path`).
 * Deny wins over allow. An empty `allow` list means "allow everything not denied".
 */
object DimensionFilter : SimpleJsonResourceReloadListener(Gson(), "dimension_filter") {

    @Volatile private var allow: List<Regex> = emptyList()
    @Volatile private var deny: List<Regex> = emptyList()

    override fun apply(
        objects: MutableMap<ResourceLocation, com.google.gson.JsonElement>,
        manager: ResourceManager,
        profiler: ProfilerFiller
    ) {
        val allowAcc = mutableListOf<Regex>()
        val denyAcc = mutableListOf<Regex>()
        for ((_, element) in objects) {
            val obj = element as? JsonObject ?: continue
            obj.getAsJsonArray("allow")?.forEach { runCatching { allowAcc += Regex(it.asString) } }
            obj.getAsJsonArray("deny")?.forEach { runCatching { denyAcc += Regex(it.asString) } }
        }
        allow = allowAcc
        deny = denyAcc
    }

    fun isAllowed(dimension: ResourceLocation): Boolean {
        val id = dimension.toString()
        if (deny.any { it.matches(id) }) return false
        return allow.isEmpty() || allow.any { it.matches(id) }
    }
}
