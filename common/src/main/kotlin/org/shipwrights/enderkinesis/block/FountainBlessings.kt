package org.shipwrights.enderkinesis.block

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.logging.LogUtils
import java.util.UUID
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.TagParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener
import net.minecraft.util.GsonHelper
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Data-driven Fountain of Blessing rules, from `data/<ns>/fountain_blessing/<path>.json`:
 *
 * ```
 * { "ingredient": <ingredient>, "count": 1, "consume": false,
 *   "outcomes": [ { "type": "transform", "result": { "item": "...", "count": 1, "nbt": "{...}" } },
 *                 { "type": "enchant", "enchantments": { "minecraft:mending": 1 } },
 *                 { "type": "attribute", "attribute": "...", "amount": 2.0, "operation": "addition", "slot": "chest" },
 *                 { "type": "repair", "amount": 1.0 },
 *                 { "type": "effect", "effect": "...", "duration": 200, "amplifier": 0, "radius": 8.0 },
 *                 { "type": "give", "result": { ... } },
 *                 { "type": "experience", "amount": 10 } ] }
 * ```
 *
 * Outcomes apply in order to the offered stack when the player takes it back. With `consume`, the
 * fountain keeps the stack. The first rule (by id) whose ingredient matches wins.
 */
object FountainBlessings : SimpleJsonResourceReloadListener(Gson(), "fountain_blessing") {

    private val LOG = LogUtils.getLogger()

    @Volatile
    private var rules: List<Blessing> = emptyList()

    class Blessing(
        val id: ResourceLocation,
        val ingredient: Ingredient,
        val count: Int,
        val consume: Boolean,
        val outcomes: List<Outcome>,
    )

    sealed class Outcome {
        class Transform(val result: ItemStack) : Outcome()
        class Enchant(val enchantments: Map<Enchantment, Int>) : Outcome()
        class AttributeBonus(
            val attribute: Attribute, val modifier: AttributeModifier, val slot: EquipmentSlot,
        ) : Outcome()
        class Repair(val share: Double) : Outcome()
        class Effect(val effect: MobEffect, val duration: Int, val amplifier: Int, val radius: Double) : Outcome()
        class Give(val result: ItemStack) : Outcome()
        class Experience(val amount: Int) : Outcome()
    }

    override fun apply(objects: MutableMap<ResourceLocation, JsonElement>, manager: ResourceManager, profiler: ProfilerFiller) {
        val loaded = ArrayList<Blessing>()
        for ((id, element) in objects.toSortedMap()) {
            try {
                loaded += parse(id, GsonHelper.convertToJsonObject(element, "fountain blessing"))
            } catch (e: RuntimeException) {
                LOG.warn("Skipped fountain blessing {}: {}", id, e.message)
            }
        }
        rules = loaded
    }

    private fun parse(id: ResourceLocation, json: JsonObject): Blessing {
        val ingredient = Ingredient.fromJson(requireNotNull(json.get("ingredient")) { "needs an \"ingredient\"" })
        val count = GsonHelper.getAsInt(json, "count", 1)
        require(count >= 1) { "\"count\" must be 1 or more" }
        val outcomesJson = GsonHelper.getAsJsonArray(json, "outcomes")
        val outcomes = outcomesJson.mapIndexed { index, element ->
            parseOutcome(id, index, GsonHelper.convertToJsonObject(element, "outcome"))
        }
        require(outcomes.isNotEmpty()) { "needs at least one outcome" }
        return Blessing(id, ingredient, count, GsonHelper.getAsBoolean(json, "consume", false), outcomes)
    }

    private fun parseOutcome(id: ResourceLocation, index: Int, json: JsonObject): Outcome =
        when (val type = GsonHelper.getAsString(json, "type")) {
            "transform" -> Outcome.Transform(parseStack(GsonHelper.getAsJsonObject(json, "result")))
            "give" -> Outcome.Give(parseStack(GsonHelper.getAsJsonObject(json, "result")))
            "enchant" -> {
                val map = LinkedHashMap<Enchantment, Int>()
                for ((key, value) in GsonHelper.getAsJsonObject(json, "enchantments").entrySet()) {
                    map[lookup(BuiltInRegistries.ENCHANTMENT, key)] = value.asInt
                }
                Outcome.Enchant(map)
            }
            "attribute" -> {
                val attributeId = GsonHelper.getAsString(json, "attribute")
                val operation = when (val op = GsonHelper.getAsString(json, "operation", "addition")) {
                    "addition" -> AttributeModifier.Operation.ADDITION
                    "multiply_base" -> AttributeModifier.Operation.MULTIPLY_BASE
                    "multiply_total" -> AttributeModifier.Operation.MULTIPLY_TOTAL
                    else -> throw IllegalArgumentException("unknown operation \"$op\"")
                }
                // A fixed UUID per rule and outcome, so a second blessing replaces the modifier, not stacks it.
                val uuid = UUID.nameUUIDFromBytes("$id/$index".toByteArray())
                val modifier = AttributeModifier(uuid, "Fountain blessing", GsonHelper.getAsDouble(json, "amount"), operation)
                Outcome.AttributeBonus(
                    lookup(BuiltInRegistries.ATTRIBUTE, attributeId), modifier,
                    EquipmentSlot.byName(GsonHelper.getAsString(json, "slot", "mainhand")),
                )
            }
            "repair" -> {
                val share = GsonHelper.getAsDouble(json, "amount", 1.0)
                require(share > 0.0 && share <= 1.0) { "repair \"amount\" must be above 0 and at most 1" }
                Outcome.Repair(share)
            }
            "effect" -> Outcome.Effect(
                lookup(BuiltInRegistries.MOB_EFFECT, GsonHelper.getAsString(json, "effect")),
                GsonHelper.getAsInt(json, "duration", 200),
                GsonHelper.getAsInt(json, "amplifier", 0),
                GsonHelper.getAsDouble(json, "radius", 8.0),
            )
            "experience" -> Outcome.Experience(GsonHelper.getAsInt(json, "amount"))
            else -> throw IllegalArgumentException("unknown outcome type \"$type\"")
        }

    private fun <T : Any> lookup(registry: net.minecraft.core.Registry<T>, key: String): T {
        val id = requireNotNull(ResourceLocation.tryParse(key)) { "bad id \"$key\"" }
        return requireNotNull(registry.getOptional(id).orElse(null)) { "unknown id \"$key\"" }
    }

    internal fun parseStack(json: JsonObject): ItemStack {
        val nbt = if (json.has("nbt")) GsonHelper.getAsString(json, "nbt") else null
        val copy = json.deepCopy()
        copy.remove("nbt")
        val stack = ShapedRecipe.itemStackFromJson(copy)
        if (nbt != null) stack.tag = TagParser.parseTag(nbt)
        return stack
    }

    /** The rule that takes [offered], or null when none does. */
    fun find(offered: ItemStack): Blessing? = rules.firstOrNull { it.ingredient.test(offered) && offered.count >= it.count }

    /** Null after a reload removed the rule. */
    fun byId(id: ResourceLocation): Blessing? = rules.firstOrNull { it.id == id }

    /** Applies [rule] to [stored] and hands the results to [player]; effects reach players around [at]. */
    fun release(level: ServerLevel, rule: Blessing, stored: ItemStack, player: Player, at: Vec3) {
        var working = stored
        for (outcome in rule.outcomes) working = applyOutcome(level, outcome, working, player, at)
        if (!rule.consume) give(player, working)
    }

    private fun applyOutcome(level: ServerLevel, outcome: Outcome, stack: ItemStack, player: Player, at: Vec3): ItemStack {
        when (outcome) {
            is Outcome.Transform -> return outcome.result.copy()
            is Outcome.Enchant -> {
                val current = EnchantmentHelper.getEnchantments(stack)
                for ((enchantment, lvl) in outcome.enchantments) current.merge(enchantment, lvl, ::maxOf)
                EnchantmentHelper.setEnchantments(current, stack)
            }
            is Outcome.AttributeBonus -> addAttribute(stack, outcome)
            is Outcome.Repair -> if (stack.isDamageableItem) {
                stack.damageValue = maxOf(0, stack.damageValue - Math.ceil(outcome.share * stack.maxDamage).toInt())
            }
            is Outcome.Effect -> {
                val area = AABB(at, at).inflate(outcome.radius)
                for (player in level.getEntitiesOfClass(Player::class.java, area)) {
                    player.addEffect(MobEffectInstance(outcome.effect, outcome.duration, outcome.amplifier))
                }
            }
            is Outcome.Give -> give(player, outcome.result.copy())
            is Outcome.Experience -> ExperienceOrb.award(level, player.position(), outcome.amount)
        }
        return stack
    }

    /** The first explicit modifier drops the item's default modifiers (vanilla), so copy those in first. */
    private fun addAttribute(stack: ItemStack, outcome: Outcome.AttributeBonus) {
        if (stack.tag?.contains("AttributeModifiers", net.minecraft.nbt.Tag.TAG_LIST.toInt()) != true) {
            val defaults = EquipmentSlot.values().flatMap { slot -> stack.getAttributeModifiers(slot).entries().map { Triple(slot, it.key, it.value) } }
            for ((slot, attribute, modifier) in defaults) stack.addAttributeModifier(attribute, modifier, slot)
        }
        val list = stack.tag?.getList("AttributeModifiers", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt())
        list?.removeIf { (it as? net.minecraft.nbt.CompoundTag)?.hasUUID("UUID") == true && it.getUUID("UUID") == outcome.modifier.id }
        stack.addAttributeModifier(outcome.attribute, outcome.modifier, outcome.slot)
    }

    fun give(player: Player, stack: ItemStack) {
        if (stack.isEmpty) return
        if (!player.inventory.add(stack)) player.drop(stack, false)
    }
}
