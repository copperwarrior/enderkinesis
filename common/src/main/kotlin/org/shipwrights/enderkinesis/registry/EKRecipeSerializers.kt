package org.shipwrights.enderkinesis.registry

import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.crafting.RecipeSerializer
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.KnowledgeOfDeathRecipe

object EKRecipeSerializers {
    val RECIPE_SERIALIZERS: DeferredRegister<RecipeSerializer<*>> =
        DeferredRegister.create(EnderkinesisMod.MOD_ID, Registries.RECIPE_SERIALIZER)

    val KNOWLEDGE_OF_DEATH_SHAPED: RegistrySupplier<RecipeSerializer<KnowledgeOfDeathRecipe>> =
        RECIPE_SERIALIZERS.register("knowledge_of_death_shaped") { KnowledgeOfDeathRecipe.Serializer() }

    fun register() = RECIPE_SERIALIZERS.register()
}
