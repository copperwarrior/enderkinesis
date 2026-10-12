package org.shipwrights.enderkinesis.item

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.GsonHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition
import org.shipwrights.enderkinesis.registry.EKLootFunctions
import org.shipwrights.enderkinesis.sselith.BookPaginator

/** Loot function `enderkinesis:set_scrap`: writes a random scrap of [category] onto the stack as
 *  Old Paper's written-book NBT. An empty or missing category leaves the stack blank. */
class SetScrapFunction(
    conditions: Array<LootItemCondition>,
    private val category: ResourceLocation,
) : LootItemConditionalFunction(conditions) {

    override fun getType(): LootItemFunctionType = EKLootFunctions.SET_SCRAP.get()

    override fun run(stack: ItemStack, context: LootContext): ItemStack {
        val text = Scraps.random(category, context.random) ?: return stack
        val pages = ListTag()
        var carried = ""
        // Stored as component JSON: OldPaperItem would misread some plain text as JSON.
        for (page in BookPaginator.paginate(text, BookPaginator::sloppyCharWidth).take(OldPaperItem.MAX_PAGES)) {
            pages.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(carried + page))))
            carried = activeCodes(carried + page)
        }
        val tag = stack.orCreateTag
        tag.put("pages", pages)
        return stack
    }

    /** The format codes in effect at the end of [page]. The next page is a new component and starts with them. */
    private fun activeCodes(page: String): String {
        val active = StringBuilder()
        for (match in SECTION_CODE.findAll(page)) {
            // A color code or `r` stops all codes before it, as in vanilla.
            if (match.value[1].lowercaseChar() !in STYLE_CODES) active.setLength(0)
            if (match.value[1].lowercaseChar() != RESET_CODE) active.append(match.value)
        }
        return active.toString()
    }

    companion object {
        private val SECTION_CODE = Regex("${BookPaginator.FORMAT_MARK}[0-9a-fk-orA-FK-OR]")
        private const val STYLE_CODES = "klmno"
        private const val RESET_CODE = 'r'
    }

    class Serializer : LootItemConditionalFunction.Serializer<SetScrapFunction>() {
        override fun serialize(json: JsonObject, function: SetScrapFunction, context: JsonSerializationContext) {
            super.serialize(json, function, context)
            json.addProperty("category", function.category.toString())
        }

        override fun deserialize(
            json: JsonObject, context: JsonDeserializationContext, conditions: Array<LootItemCondition>,
        ): SetScrapFunction =
            SetScrapFunction(conditions, ResourceLocation(GsonHelper.getAsString(json, "category")))
    }
}
