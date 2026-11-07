package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.EnderkinesisMod

// Vanilla `HumanoidArmorLayer` draws only an `ArmorItem`. The crown is not one, so it has no armor or repair.
// Fabric calls this from an `ArmorRenderer`, Forge from `HumanoidArmorLayerCrownUntestedForgeMixin`.
class CrownUntestedArmorRenderer {

    private var model: HumanoidModel<LivingEntity>? = null

    fun render(
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        stack: ItemStack,
        slot: EquipmentSlot,
        light: Int,
        contextModel: HumanoidModel<LivingEntity>,
    ) {
        if (slot != EquipmentSlot.HEAD) return
        val model = obtainModel()
        contextModel.copyPropertiesTo(model)
        model.setAllVisible(false)
        model.head.visible = true
        model.hat.visible = true
        val consumer =
            ItemRenderer.getArmorFoilBuffer(buffer, RenderType.armorCutoutNoCull(TEXTURE), false, stack.hasFoil())
        model.renderToBuffer(poseStack, consumer, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f)
    }

    private fun obtainModel(): HumanoidModel<LivingEntity> =
        model ?: HumanoidModel<LivingEntity>(
            Minecraft.getInstance().entityModels.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR),
        ).also { model = it }

    companion object {
        private val TEXTURE = EnderkinesisMod.id("textures/models/armor/crown_untested_layer_1.png")

        @JvmField
        val INSTANCE = CrownUntestedArmorRenderer()
    }
}
