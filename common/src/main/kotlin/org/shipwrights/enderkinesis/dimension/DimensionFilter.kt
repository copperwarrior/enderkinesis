package org.shipwrights.enderkinesis.dimension

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller

/**
 * Data-driven deny lists for two orthogonal dimension permissions:
 *  - **reachable** (`deny`): dimensions the Almanac and Ender Astrolabe may target.
 *  - **learnable** (`learnable_deny`): dimensions a player flow may record into an Almanac
 *    via [AlmanacData.addVisited]. Trusted seeders (e.g.
 *    [org.shipwrights.enderkinesis.worldgen.SeedLecternAlmanacProcessor]) go through
 *    `addEntryTrusted` and bypass this filter entirely.
 *
 * Default is allow-everything; any dimension whose id matches a deny regex is rejected.
 * Entries are unioned across every JSON file under `dimension_filter`, so a mod adding a
 * `deny` entry only subtracts — it can never narrow another datapack's permits.
 *
 * Example file:
 * ```json
 * {
 *   "deny": ["enderkinesis:sselith_repertory"],
 *   "learnable_deny": ["enderkinesis:ygann_abyss"]
 * }
 * ```
 */
object DimensionFilter : SimpleJsonResourceReloadListener(Gson(), "dimension_filter") {

    @Volatile private var reachableDeny: List<Regex> = emptyList()
    @Volatile private var learnableDeny: List<Regex> = emptyList()

    override fun apply(
        objects: MutableMap<ResourceLocation, com.google.gson.JsonElement>,
        manager: ResourceManager,
        profiler: ProfilerFiller
    ) {
        val reachAcc = mutableListOf<Regex>()
        val learnAcc = mutableListOf<Regex>()
        for ((_, element) in objects) {
            val obj = element as? JsonObject ?: continue
            obj.getAsJsonArray("deny")?.forEach { runCatching { reachAcc += Regex(it.asString) } }
            obj.getAsJsonArray("learnable_deny")?.forEach { runCatching { learnAcc += Regex(it.asString) } }
        }
        reachableDeny = reachAcc
        learnableDeny = learnAcc
    }

    fun isAllowed(dimension: ResourceLocation): Boolean {
        val id = dimension.toString()
        return reachableDeny.none { it.matches(id) }
    }

    fun isLearnable(dimension: ResourceLocation): Boolean {
        val id = dimension.toString()
        return learnableDeny.none { it.matches(id) }
    }
}
