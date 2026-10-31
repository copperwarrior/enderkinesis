package org.shipwrights.enderkinesis.item

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import net.minecraft.util.GsonHelper
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType
import net.minecraft.world.level.storage.loot.parameters.LootContextParam
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import org.shipwrights.enderkinesis.registry.EKLootFunctions

/** Loot function `enderkinesis:scale_count_by_size`: count = hitbox volume × [perVolume], capped at [max].
 *  The fraction rounds up by chance, so small entities still drop sometimes. */
class ScaleCountBySizeFunction(
    conditions: Array<LootItemCondition>,
    private val perVolume: Float,
    private val max: Int,
) : LootItemConditionalFunction(conditions) {

    override fun getType(): LootItemFunctionType = EKLootFunctions.SCALE_COUNT_BY_SIZE.get()

    override fun getReferencedContextParams(): Set<LootContextParam<*>> = setOf(LootContextParams.THIS_ENTITY)

    override fun run(stack: ItemStack, context: LootContext): ItemStack {
        val entity = context.getParamOrNull(LootContextParams.THIS_ENTITY) ?: return stack
        val exact = entity.bbWidth * entity.bbWidth * entity.bbHeight * perVolume
        val whole = Mth.floor(exact)
        val count = whole + if (context.random.nextFloat() < exact - whole) 1 else 0
        stack.count = count.coerceAtMost(max)
        return stack
    }

    class Serializer : LootItemConditionalFunction.Serializer<ScaleCountBySizeFunction>() {
        override fun serialize(json: JsonObject, function: ScaleCountBySizeFunction, context: JsonSerializationContext) {
            super.serialize(json, function, context)
            json.addProperty("per_volume", function.perVolume)
            json.addProperty("max", function.max)
        }

        override fun deserialize(
            json: JsonObject, context: JsonDeserializationContext, conditions: Array<LootItemCondition>,
        ): ScaleCountBySizeFunction {
            val perVolume = GsonHelper.getAsFloat(json, "per_volume")
            val max = GsonHelper.getAsInt(json, "max", DEFAULT_MAX)
            require(perVolume >= 0f) { "\"per_volume\" must be 0 or more" }
            require(max >= 0) { "\"max\" must be 0 or more" }
            return ScaleCountBySizeFunction(conditions, perVolume, max)
        }
    }

    companion object {
        private const val DEFAULT_MAX = 64
    }
}
