package org.shipwrights.enderkinesis.item

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.logging.LogUtils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.tags.TagKey
import net.minecraft.util.GsonHelper
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import org.shipwrights.enderkinesis.block.FountainBlessings

/**
 * Datapack rites for the Grand Staff of the Dead, loaded from `data/<ns>/raising_rite/`:
 * ```
 * { "entities": ["minecraft:zombie", "#minecraft:raiders"],
 *   "wear_skin": false, "zombie": false,
 *   "flesh": { "item": "minecraft:rotten_flesh" }, "flesh_count": 1,
 *   "equipment": { "mainhand": "inherit", "offhand": { "item": "minecraft:shield" } } }
 * { "gather": { "item": "minecraft:bone" }, "count": 16,
 *   "flesh": { "item": "minecraft:rotten_flesh" }, "flesh_count": 1,
 *   "equipment": { "mainhand": { "item": "minecraft:bow" } } }
 * ```
 * `inherit` moves the dying entity's item to the skeleton, so it does not also drop. An exact entity id
 * wins over a tag; otherwise the first rite by id wins.
 */
object RaisingRites : SimpleJsonResourceReloadListener(Gson(), "raising_rite") {

    private val LOG = LogUtils.getLogger()

    @Volatile
    private var loaded: Loaded = Loaded(emptyMap(), emptyList(), emptyList())

    /** With at least [fleshCount] of [flesh] lying in the caster's range, the flesh is used up and the body is a zombie. */
    class Rite(
        val id: ResourceLocation,
        val equipment: Map<EquipmentSlot, Equip>,
        val wearSkin: Boolean,
        /** Always a zombie body, with no flesh needed. */
        val zombie: Boolean,
        val flesh: Ingredient?,
        val fleshCount: Int,
    )

    class GatherRite(val rite: Rite, val ingredient: Ingredient, val count: Int)

    sealed class Equip {
        object Inherit : Equip()
        class Give(val stack: ItemStack) : Equip()
    }

    private class Loaded(
        val byType: Map<EntityType<*>, Rite>,
        val byTag: List<Pair<TagKey<EntityType<*>>, Rite>>,
        val gather: List<GatherRite>,
    )

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val byType = HashMap<EntityType<*>, Rite>()
        val byTag = ArrayList<Pair<TagKey<EntityType<*>>, Rite>>()
        val gather = ArrayList<GatherRite>()
        for ((id, element) in objects.toSortedMap()) {
            try {
                val json = GsonHelper.convertToJsonObject(element, "raising rite")
                val flesh = if (json.has("flesh")) Ingredient.fromJson(json.get("flesh")) else null
                val fleshCount = GsonHelper.getAsInt(json, "flesh_count", 1)
                require(fleshCount >= 1) { "\"flesh_count\" must be 1 or more" }
                val rite = Rite(
                    id, parseEquipment(json), GsonHelper.getAsBoolean(json, "wear_skin", false),
                    GsonHelper.getAsBoolean(json, "zombie", false), flesh, fleshCount,
                )
                val hasEntities = json.has("entities")
                require(hasEntities != json.has("gather")) { "needs exactly one of \"entities\" or \"gather\"" }
                if (hasEntities) {
                    for (element in GsonHelper.getAsJsonArray(json, "entities")) {
                        val key = element.asString
                        if (key.startsWith("#")) {
                            byTag += TagKey.create(Registries.ENTITY_TYPE, parseId(key.substring(1))) to rite
                        } else {
                            val type = BuiltInRegistries.ENTITY_TYPE.getOptional(parseId(key)).orElse(null)
                            requireNotNull(type) { "unknown entity \"$key\"" }
                            byType.putIfAbsent(type, rite)
                        }
                    }
                } else {
                    require(rite.equipment.values.none { it is Equip.Inherit }) { "a gather rite has nothing to inherit" }
                    val count = GsonHelper.getAsInt(json, "count", 1)
                    require(count >= 1) { "\"count\" must be 1 or more" }
                    gather += GatherRite(rite, Ingredient.fromJson(json.get("gather")), count)
                }
            } catch (e: RuntimeException) {
                LOG.warn("Skipped raising rite {}: {}", id, e.message)
            }
        }
        loaded = Loaded(byType, byTag, gather)
    }

    private fun parseEquipment(json: JsonObject): Map<EquipmentSlot, Equip> {
        if (!json.has("equipment")) return emptyMap()
        val out = java.util.EnumMap<EquipmentSlot, Equip>(EquipmentSlot::class.java)
        for ((slotName, value) in GsonHelper.getAsJsonObject(json, "equipment").entrySet()) {
            val slot = EquipmentSlot.byName(slotName)
            out[slot] = if (value.isJsonPrimitive && value.asString == INHERIT) {
                Equip.Inherit
            } else {
                Equip.Give(FountainBlessings.parseStack(GsonHelper.convertToJsonObject(value, slotName)))
            }
        }
        return out
    }

    private fun parseId(key: String): ResourceLocation =
        requireNotNull(ResourceLocation.tryParse(key)) { "bad id \"$key\"" }

    fun forEntity(entity: LivingEntity): Rite? {
        val current = loaded
        current.byType[entity.type]?.let { return it }
        return current.byTag.firstOrNull { entity.type.`is`(it.first) }?.second
    }

    fun gatherRites(): List<GatherRite> = loaded.gather

    private const val INHERIT = "inherit"
}
