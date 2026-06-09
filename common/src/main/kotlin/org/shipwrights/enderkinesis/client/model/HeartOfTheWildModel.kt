package org.shipwrights.enderkinesis.client.model

import net.minecraft.client.animation.KeyframeAnimations
import net.minecraft.client.model.HierarchicalModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.world.entity.Entity
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Extends [HierarchicalModel] only for [KeyframeAnimations.animate]; the [Entity] type
 * parameter is unused (this is a BER, [setupAnim] is unreachable).
 */
class HeartOfTheWildModel(private val bakedRoot: ModelPart) : HierarchicalModel<Entity>() {

    override fun root(): ModelPart = bakedRoot

    override fun setupAnim(
        entity: Entity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
    }

    /** [KeyframeAnimations] applies additively — the resetPose loop is mandatory each frame. */
    fun animateIdleMillis(clockMillis: Long, rateScalar: Float) {
        bakedRoot.allParts.forEach { it.resetPose() }
        val scaledMillis = (clockMillis.toDouble() * rateScalar.toDouble()).toLong()
        KeyframeAnimations.animate(
            this, HeartOfTheWildAnimations.PULSE,
            scaledMillis, 1f, ANIMATION_VECTOR_CACHE,
        )
    }

    companion object {
        private val ANIMATION_VECTOR_CACHE = Vector3f()

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id("heart_of_the_wild"), "main")

        @JvmStatic
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val partdefinition = mesh.root

            val root = partdefinition.addOrReplaceChild(
                "root",
                CubeListBuilder.create(),
                PartPose.offset(0.0f, 24.0f, 0.0f),
            )

            root.addOrReplaceChild(
                "heart_lchamber",
                CubeListBuilder.create()
                    .texOffs(0, 0)
                    .addBox(-5.0f, 0.5f, -5.0f, 10.0f, 4.0f, 9.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -8.0f, 0.0f),
            )

            root.addOrReplaceChild(
                "heart_rchamber",
                CubeListBuilder.create()
                    .texOffs(0, 13)
                    .addBox(-5.0f, -3.5f, -5.0f, 10.0f, 4.0f, 9.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, -8.0f, 0.0f),
            )

            root.addOrReplaceChild(
                "heart_cv",
                CubeListBuilder.create()
                    .texOffs(0, 26)
                    .addBox(-2.5f, -7.5f, -2.0f, 4.0f, 12.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offset(-6.5f, -7.5f, 0.0f),
            )

            root.addOrReplaceChild(
                "heart_aorta",
                CubeListBuilder.create()
                    .texOffs(16, 26)
                    .addBox(-2.5f, -1.5f, -2.0f, 5.0f, 5.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offset(-0.5f, -15.5f, 0.0f),
            )

            return LayerDefinition.create(mesh, 64, 64)
        }
    }
}
