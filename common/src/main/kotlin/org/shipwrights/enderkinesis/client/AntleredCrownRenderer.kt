package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Draws the worn crown on a head part, for the helmet slot and for the Curios / Trinkets head slot. */
object AntleredCrownRenderer {

    private val TEXTURE = EnderkinesisMod.id("textures/entity/antlered_crown/antlered_crown.png")

    private val root: ModelPart by lazy { bake() }
    private val wreath: ModelPart by lazy { root.getChild("wreath") }
    private val antlers: ModelPart by lazy { root.getChild("antlers") }

    fun render(poseStack: PoseStack, buffers: MultiBufferSource, light: Int, stack: ItemStack, head: ModelPart) {
        poseStack.pushPose()
        head.translateAndRotate(poseStack)
        val foil = stack.hasFoil()
        // No cull so the far inside of the wreath shows through its open top.
        wreath.render(
            poseStack, ItemRenderer.getArmorFoilBuffer(buffers, RenderType.armorCutoutNoCull(TEXTURE), false, foil),
            light, OverlayTexture.NO_OVERLAY,
        )
        // Culled: each antler is a zero-depth box whose front and back faces lie in one plane.
        antlers.render(
            poseStack, ItemRenderer.getArmorFoilBuffer(buffers, RenderType.entityCutout(TEXTURE), false, foil),
            light, OverlayTexture.NO_OVERLAY,
        )
        poseStack.popPose()
    }

    // From the "wreath" group of antlered_crown_wreath.bbmodel, head pivot at the origin. Blockbench x is mirrored.
    private fun bake(): ModelPart {
        val mesh = MeshDefinition()
        mesh.root.addOrReplaceChild(
            "wreath",
            CubeListBuilder.create().texOffs(0, 0).addBox(-4f, -8f, -4f, 8f, 8f, 8f, CubeDeformation(WREATH_INFLATION)),
            PartPose.ZERO,
        )
        mesh.root.addOrReplaceChild(
            "antlers",
            CubeListBuilder.create()
                .texOffs(16, 16).addBox(3f, -18f, 1f, 8f, 11f, 0f)
                .texOffs(0, 16).addBox(-11f, -18f, 1f, 8f, 11f, 0f),
            PartPose.ZERO,
        )
        return LayerDefinition.create(mesh, TEXTURE_SIZE, TEXTURE_SIZE).bakeRoot()
    }

    private const val WREATH_INFLATION = 0.5f
    private const val TEXTURE_SIZE = 32
}
