package org.shipwrights.enderkinesis.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.model.HierarchicalModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.world.entity.Entity

/**
 * The Ygann Abyss **tentacle** — a six-segment tapering column (`tent_base` → `tend_1` → `tent_2`
 * → … → `tent_5`) that writhes via [TentacleAnimation.IDLE_WAVE]. The part hierarchy and pivots are
 * the artist's Blockbench export verbatim (128×128 texture), because the animation keyframes were
 * authored against those exact pivots and look them up by name. `tent_5` is the glowing tip — its UV
 * region is the only place the emissive texture is opaque.
 *
 * It is a [HierarchicalModel] so [net.minecraft.client.animation.KeyframeAnimations.animate] can
 * pose it; there is no entity behind it, so [setupAnim] is unused — `YgannAbyssWrithingSea` resets
 * the pose and applies the animation directly each frame, once per tentacle, at a per-tentacle rate.
 */
class TentacleModel(private val root: ModelPart) : HierarchicalModel<Entity>() {

    override fun root(): ModelPart = root

    override fun setupAnim(
        entity: Entity,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        // Unused: there is no entity. Posing is driven directly from the sea renderer.
    }

    /**
     * Render ONLY the glowing tip ([tent_5]) with the model's current pose — used for the additive
     * emissive overlay so no other segment can sample the emissive texture. Walks the parent chain
     * with [ModelPart.translateAndRotate] (exactly what [ModelPart.render] does recursively) so the
     * tip lands at its posed world transform, then renders just its cube. Call after the animation
     * has been applied, with the same placement [poseStack] used for the body.
     */
    fun renderTip(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        packedLight: Int,
        packedOverlay: Int,
        alpha: Float,
    ) {
        poseStack.pushPose()
        root.translateAndRotate(poseStack)
        var part = root.getChild("tent_base")
        part.translateAndRotate(poseStack)
        for (name in arrayOf("tend_1", "tent_2", "tent_3", "tent_4")) {
            part = part.getChild(name)
            part.translateAndRotate(poseStack)
        }
        part.getChild("tent_5").render(poseStack, consumer, packedLight, packedOverlay, 1.0f, 1.0f, 1.0f, alpha)
        poseStack.popPose()
    }

    companion object {
        fun createMesh(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root

            val tentBase = root.addOrReplaceChild(
                "tent_base",
                CubeListBuilder.create().texOffs(0, 0)
                    .addBox(-7.0f, -14.0f, -7.0f, 14.0f, 14.0f, 14.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, 24.0f, 0.0f),
            )
            val tend1 = tentBase.addOrReplaceChild(
                "tend_1",
                CubeListBuilder.create().texOffs(0, 28)
                    .addBox(-6.0f, -15.0f, -6.0f, 12.0f, 16.0f, 12.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -14.0f, 0.0f),
            )
            val tent2 = tend1.addOrReplaceChild(
                "tent_2",
                CubeListBuilder.create().texOffs(48, 28)
                    .addBox(-5.0f, -18.0f, -5.0f, 10.0f, 20.0f, 10.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -15.0f, 0.0f),
            )
            val tent3 = tent2.addOrReplaceChild(
                "tent_3",
                CubeListBuilder.create().texOffs(0, 56)
                    .addBox(-4.0f, -15.0f, -4.0f, 8.0f, 16.0f, 8.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -18.0f, 0.0f),
            )
            val tent4 = tent3.addOrReplaceChild(
                "tent_4",
                CubeListBuilder.create().texOffs(56, 0)
                    .addBox(-3.0f, -14.0f, -3.0f, 6.0f, 15.0f, 6.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -15.0f, 0.0f),
            )
            tent4.addOrReplaceChild(
                "tent_5",
                CubeListBuilder.create().texOffs(32, 56)
                    .addBox(-2.0f, -14.0f, -2.0f, 4.0f, 15.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -14.0f, 0.0f),
            )

            return LayerDefinition.create(mesh, 128, 128)
        }
    }
}
