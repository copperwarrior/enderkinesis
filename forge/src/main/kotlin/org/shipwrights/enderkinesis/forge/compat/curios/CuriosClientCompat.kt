package org.shipwrights.enderkinesis.forge.compat.curios

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.platform.Platform
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.client.SatchelOfShelvingRenderer
import org.shipwrights.enderkinesis.registry.EKItems
import top.theillusivec4.curios.api.SlotContext
import top.theillusivec4.curios.api.client.CuriosRendererRegistry
import top.theillusivec4.curios.api.client.ICurioRenderer

/** Kept apart from [CuriosCompat] so a dedicated server never links the client classes. */
object CuriosClientCompat {

    fun init() {
        if (!Platform.isModLoaded("curios")) return
        CuriosRendererRegistry.register(EKItems.SATCHEL_OF_SHELVING.get()) { SatchelCurioRenderer }
    }

    private object SatchelCurioRenderer : ICurioRenderer {
        override fun <T : LivingEntity, M : EntityModel<T>> render(
            stack: ItemStack, slotContext: SlotContext, matrixStack: PoseStack,
            renderLayerParent: RenderLayerParent<T, M>, renderTypeBuffer: MultiBufferSource, light: Int,
            limbSwing: Float, limbSwingAmount: Float, partialTicks: Float, ageInTicks: Float,
            netHeadYaw: Float, headPitch: Float,
        ) {
            val model = renderLayerParent.model as? HumanoidModel<*> ?: return
            SatchelOfShelvingRenderer.render(matrixStack, renderTypeBuffer, light, slotContext.entity(), model.body)
        }
    }
}
