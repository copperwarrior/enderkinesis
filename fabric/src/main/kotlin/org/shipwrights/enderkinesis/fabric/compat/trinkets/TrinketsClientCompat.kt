package org.shipwrights.enderkinesis.fabric.compat.trinkets

import dev.architectury.platform.Platform
import dev.emi.trinkets.api.client.TrinketRendererRegistry
import net.minecraft.client.model.HumanoidModel
import org.shipwrights.enderkinesis.client.SatchelOfShelvingRenderer
import org.shipwrights.enderkinesis.registry.EKItems

/** Kept apart from [TrinketsCompat] so a dedicated server never links the client classes. */
object TrinketsClientCompat {

    fun init() {
        if (!Platform.isModLoaded("trinkets")) return
        TrinketRendererRegistry.registerRenderer(EKItems.SATCHEL_OF_SHELVING.get()) {
                _, _, contextModel, matrices, buffers, light, entity, _, _, _, _, _, _ ->
            val model = contextModel as? HumanoidModel<*> ?: return@registerRenderer
            SatchelOfShelvingRenderer.render(matrices, buffers, light, entity, model.body)
        }
    }
}
