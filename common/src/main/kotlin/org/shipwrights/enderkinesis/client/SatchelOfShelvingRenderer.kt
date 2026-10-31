package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.Item
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.TightRobeArmorModel
import org.shipwrights.enderkinesis.item.SatchelOfShelvingItem
import org.shipwrights.enderkinesis.item.ScalingRobeArmorItem

/** Draws the worn satchel for the chest slot and for the Curios / Trinkets belt slot. */
object SatchelOfShelvingRenderer {

    private val TEXTURE = EnderkinesisMod.id("textures/entity/satchel_of_shelving.png")

    /** Strap inflation over the bare body, from `dev/blockbench/satchel_of_shelving.json`. */
    private const val STRAP_INFLATION = 0.35f

    /** Body-cube deformation of each armor kind; ordered so the bulkiest worn piece wins. */
    private enum class ArmorBulk(val inflation: Float) {
        NONE(0f),
        ROBE_LEGGINGS(TightRobeArmorModel.LEGGINGS_BODY_DEFORMATION),
        ROBE(TightRobeArmorModel.BODY_DEFORMATION),
        LEGGINGS(0.5f),
        CHESTPLATE(1.0f);

        val model: ModelPart by lazy { bake(inflation) }
    }

    fun render(poseStack: PoseStack, buffers: MultiBufferSource, light: Int, entity: LivingEntity, body: ModelPart) {
        poseStack.pushPose()
        body.translateAndRotate(poseStack)
        bulkOf(entity).model.render(
            poseStack, buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), light, OverlayTexture.NO_OVERLAY,
        )
        poseStack.popPose()
    }

    private fun bulkOf(entity: LivingEntity): ArmorBulk = maxOf(
        bulkOf(entity.getItemBySlot(EquipmentSlot.CHEST).item, ArmorBulk.ROBE, ArmorBulk.CHESTPLATE),
        bulkOf(entity.getItemBySlot(EquipmentSlot.LEGS).item, ArmorBulk.ROBE_LEGGINGS, ArmorBulk.LEGGINGS),
    )

    private fun bulkOf(item: Item, robe: ArmorBulk, vanilla: ArmorBulk): ArmorBulk = when (item) {
        is ScalingRobeArmorItem -> robe
        is ArmorItem -> vanilla
        else -> ArmorBulk.NONE
    }

    // Box UV matches the Blockbench face UVs: strap = torso box at (0, 0), bag = 5×6×2 at (0, 16).
    private fun bake(inflation: Float): ModelPart {
        val mesh = MeshDefinition()
        mesh.root.addOrReplaceChild(
            "strap",
            CubeListBuilder.create().texOffs(0, 0)
                .addBox(-4f, 0f, -2f, 8f, 12f, 4f, CubeDeformation(STRAP_INFLATION + inflation)),
            PartPose.ZERO,
        )
        mesh.root.addOrReplaceChild(
            "bag",
            CubeListBuilder.create().texOffs(0, 16).addBox(-5f, 7f, -4.4f - inflation, 5f, 6f, 2f),
            PartPose.ZERO,
        )
        return LayerDefinition.create(mesh, 32, 32).bakeRoot()
    }
}

class SatchelOfShelvingLayer<T : LivingEntity, M : HumanoidModel<T>>(parent: RenderLayerParent<T, M>) :
    RenderLayer<T, M>(parent) {

    override fun render(
        poseStack: PoseStack, buffers: MultiBufferSource, light: Int, entity: T,
        limbSwing: Float, limbSwingAmount: Float, partialTick: Float, ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
        if (entity.getItemBySlot(EquipmentSlot.CHEST).item !is SatchelOfShelvingItem) return
        SatchelOfShelvingRenderer.render(poseStack, buffers, light, entity, parentModel.body)
    }
}
