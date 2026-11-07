package org.shipwrights.enderkinesis.item

import com.google.gson.JsonObject
import net.minecraft.core.RegistryAccess
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.CraftingContainer
import net.minecraft.world.inventory.CraftingMenu
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.dimension.SureibjinEntry
import org.shipwrights.enderkinesis.mixin.CraftingMenuPlayerAccessor
import org.shipwrights.enderkinesis.mixin.InventoryMenuOwnerAccessor
import org.shipwrights.enderkinesis.mixin.TransientCraftingContainerMenuAccessor
import org.shipwrights.enderkinesis.registry.EKRecipeSerializers

/** A shaped recipe that matches only for a player with the Knowledge of Death advancement.
 *  The check is in [matches], so a recipe-book unlock or `doLimitedCrafting` false does not bypass it. */
class KnowledgeOfDeathRecipe(base: ShapedRecipe) : ShapedRecipe(
    base.id, base.group, base.category(), base.width, base.height,
    base.ingredients, base.getResultItem(RegistryAccess.EMPTY), base.showNotification(),
) {

    override fun matches(container: CraftingContainer, level: Level): Boolean =
        super.matches(container, level) && crafterKnowsDeath(container)

    override fun getSerializer(): RecipeSerializer<*> = EKRecipeSerializers.KNOWLEDGE_OF_DEATH_SHAPED.get()

    // Automated crafters (Create, autocrafters) use a container with no player menu, so they fail here.
    private fun crafterKnowsDeath(container: CraftingContainer): Boolean {
        val menu = (container as? TransientCraftingContainerMenuAccessor)?.`enderkinesis$getMenu`()
        val player = when (menu) {
            is CraftingMenu -> (menu as CraftingMenuPlayerAccessor).`enderkinesis$getPlayer`()
            is InventoryMenu -> (menu as InventoryMenuOwnerAccessor).`enderkinesis$getOwner`()
            else -> null
        } as? ServerPlayer ?: return false
        val advancement = player.server.advancements.getAdvancement(SureibjinEntry.ADV_KNOWLEDGE_OF_DEATH) ?: return false
        return player.advancements.getOrStartProgress(advancement).isDone
    }

    class Serializer : RecipeSerializer<KnowledgeOfDeathRecipe> {
        override fun fromJson(id: ResourceLocation, json: JsonObject): KnowledgeOfDeathRecipe =
            KnowledgeOfDeathRecipe(RecipeSerializer.SHAPED_RECIPE.fromJson(id, json))

        override fun fromNetwork(id: ResourceLocation, buf: FriendlyByteBuf): KnowledgeOfDeathRecipe =
            KnowledgeOfDeathRecipe(RecipeSerializer.SHAPED_RECIPE.fromNetwork(id, buf))

        override fun toNetwork(buf: FriendlyByteBuf, recipe: KnowledgeOfDeathRecipe) =
            RecipeSerializer.SHAPED_RECIPE.toNetwork(buf, recipe)
    }
}
