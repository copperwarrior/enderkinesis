package org.shipwrights.enderkinesis.spell

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.logging.LogUtils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid

/** `data/<ns>/fluid_freezing/<path>.json` is for the source fluid `<ns>:<path>`: `{"source": "<block>", "flowing": "<block>"}`.
 *  Both keys are optional. A fluid with no file never freezes. */
object FluidFreezing : SimpleJsonResourceReloadListener(Gson(), "fluid_freezing") {

    private val LOG = LogUtils.getLogger()

    private class Result(val source: Block?, val flowing: Block?)

    @Volatile
    private var byFluid: Map<ResourceLocation, Result> = emptyMap()

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = HashMap<ResourceLocation, Result>()
        for ((fluid, element) in objects) {
            val json = element as? JsonObject
            if (json == null) {
                LOG.warn("Fluid freezing entry for {} must be an object", fluid)
                continue
            }
            loaded[fluid] = Result(block(json, "source", fluid), block(json, "flowing", fluid))
        }
        byFluid = loaded
    }

    private fun block(json: JsonObject, key: String, fluid: ResourceLocation): Block? {
        val id = json.get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val location = ResourceLocation.tryParse(id)
        val block = location?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
        if (block == null) LOG.warn("Fluid freezing entry for {} names unknown block {}", fluid, id)
        return block
    }

    fun frozen(state: BlockState): BlockState? {
        // Only fluid blocks freeze, so a waterlogged block keeps its water.
        if (state.block !is LiquidBlock) return null
        val fluidState = state.fluidState
        if (fluidState.isEmpty) return null
        val type = fluidState.type
        val source = if (type is FlowingFluid) type.source else type
        val result = byFluid[BuiltInRegistries.FLUID.getKey(source)] ?: return null
        val block = if (fluidState.isSource) result.source else result.flowing
        return block?.defaultBlockState()
    }
}
