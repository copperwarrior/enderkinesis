package org.shipwrights.enderkinesis.block

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.logging.LogUtils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

/**
 * Data-driven Rite of the Staff recipes, `data/<ns>/wohlon_staff_rites/<path>.json`:
 * `{"gem": item, "result": item, "colour": "#RRGGBB", "body_colour": "#RRGGBB", "slots": [item, ...], "advancements": [id, ...]}`.
 * The gem goes in first; each slot then takes one item, in order, from a player who has every listed advancement.
 */
object WohlonStaffRites : SimpleJsonResourceReloadListener(Gson(), "wohlon_staff_rites") {

    private val LOG = LogUtils.getLogger()

    /** [Recipe.bodyColour] of a recipe with no "body_colour": its ghost keeps the ritual cyan and green. */
    const val NO_BODY_COLOUR = -1

    @Suppress("LongParameterList") // One recipe's data fields.
    class Recipe(
        val gem: Item, val result: Item, val colour: Int, val bodyColour: Int, val slots: List<Item>, val advancements: List<ResourceLocation>,
    ) {
        fun isKnownBy(player: ServerPlayer): Boolean = advancements.all { id ->
            val advancement = player.server.advancements.getAdvancement(id)
            advancement != null && player.advancements.getOrStartProgress(advancement).isDone
        }
    }

    @Volatile
    private var byGem: Map<Item, Recipe> = emptyMap()

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = HashMap<Item, Recipe>()
        for ((id, element) in objects) {
            val recipe = parse(element as? JsonObject)
            if (recipe == null) {
                LOG.warn(
                    "Staff rite {} needs \"gem\", \"result\", \"colour\" (#RRGGBB), a non-empty \"slots\" list of items, " +
                        "and, if present, \"body_colour\" (#RRGGBB) and an \"advancements\" list of ids", id,
                )
                continue
            }
            if (loaded.put(recipe.gem, recipe) != null) LOG.warn("Staff rite {} replaces another rite for the same gem", id)
        }
        byGem = loaded
    }

    private fun parse(json: JsonObject?): Recipe? {
        json ?: return null
        val gem = item(json.get("gem")) ?: return null
        val result = item(json.get("result")) ?: return null
        val colour = colour(json.get("colour")) ?: return null
        val bodyColour = if (json.has("body_colour")) colour(json.get("body_colour")) ?: return null else NO_BODY_COLOUR
        val slotsJson = json.get("slots")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val slots = slotsJson.map { item(it) ?: return null }
        if (slots.isEmpty()) return null
        val advancementsJson = json.get("advancements")
        val advancements = when {
            advancementsJson == null -> emptyList()
            !advancementsJson.isJsonArray -> return null
            else -> advancementsJson.asJsonArray.map { e ->
                e.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::tryParse) ?: return null
            }
        }
        return Recipe(gem, result, colour, bodyColour, slots, advancements)
    }

    private fun colour(element: JsonElement?): Int? =
        element?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix("#")?.toIntOrNull(16)

    private fun item(element: JsonElement?): Item? {
        val id = element?.takeIf { it.isJsonPrimitive }?.asString?.let(ResourceLocation::tryParse) ?: return null
        return BuiltInRegistries.ITEM.get(id).takeIf { it != Items.AIR }
    }

    fun forGem(item: Item): Recipe? = byGem[item]
}
