package org.shipwrights.enderkinesis.block

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.level.Level

/** Data-driven Inegric Retort rates. Each `data/<ns>/inegric_retort/<path>.json` is for the dimension
 *  `<ns>:<path>` and holds `{"drip_chance": 0.0..1.0}`, the chance of one bottle on each random tick. */
object InegricRetortRates : SimpleJsonResourceReloadListener(Gson(), "inegric_retort") {

    private val LOG = LogUtils.getLogger()
    private const val DRIP_CHANCE = "drip_chance"

    @Volatile
    private var byDimension: Map<ResourceLocation, Float> = emptyMap()

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = HashMap<ResourceLocation, Float>()
        for ((dimension, element) in objects) {
            val value = (element as? JsonObject)?.get(DRIP_CHANCE)
            val chance = if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asFloat else null
            if (chance == null || chance !in 0f..1f) {
                LOG.warn("Inegric Retort rate for {} needs a \"{}\" number from 0 to 1", dimension, DRIP_CHANCE)
                continue
            }
            loaded[dimension] = chance
        }
        byDimension = loaded
    }

    fun dripChance(dimension: ResourceKey<Level>): Float = byDimension[dimension.location()] ?: 0f
}
