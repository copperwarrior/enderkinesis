package org.shipwrights.enderkinesis.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.animation.AnimationDefinition
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
 * One Clawhod hand, from the user's Blockbench export. Extends [HierarchicalModel] only for
 * [KeyframeAnimations.animate]; the [Entity] type is unused (a block entity renderer draws it).
 */
class ClawhodHandModel(private val bakedRoot: ModelPart) : HierarchicalModel<Entity>() {

    val palm: ModelPart = bakedRoot.getChild("palm")
    val finger: ModelPart = palm.getChild("finger")
    val finger2: ModelPart = palm.getChild("finger2")
    val finger3: ModelPart = palm.getChild("finger3")
    val finger4: ModelPart = palm.getChild("finger4")

    override fun root(): ModelPart = bakedRoot

    override fun setupAnim(
        entity: Entity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
    }

    /** Poses the parts as [from] at [fromMillis] mixed with [to] at [toMillis]; [mix] 0 is all [from], 1 all [to]. */
    fun pose(from: AnimationDefinition, fromMillis: Long, to: AnimationDefinition, toMillis: Long, mix: Float) {
        palm.resetPose()
        finger.resetPose()
        finger2.resetPose()
        finger3.resetPose()
        finger4.resetPose()
        if (mix < 1f) KeyframeAnimations.animate(this, from, fromMillis, 1f - mix, scratch)
        if (mix > 0f) KeyframeAnimations.animate(this, to, toMillis, mix, scratch)
    }

    fun render(ps: PoseStack, buffer: VertexConsumer, light: Int, overlay: Int) {
        palm.render(ps, buffer, light, overlay)
    }

    private val scratch = Vector3f()

    companion object {
        val LEFT_LAYER: ModelLayerLocation = ModelLayerLocation(EnderkinesisMod.id("clawhod_left"), "main")
        val RIGHT_LAYER: ModelLayerLocation = ModelLayerLocation(EnderkinesisMod.id("clawhod_right"), "main")

        fun createLeftLayer(): LayerDefinition = createLayer(left = true)

        fun createRightLayer(): LayerDefinition = createLayer(left = false)

        // The two exports differ only in the thumb, which sits on the other side.
        private fun createLayer(left: Boolean): LayerDefinition {
            val mesh = MeshDefinition()
            val parts = partSpecs(left)
            val palm = parts.first().let { mesh.root.addOrReplaceChild(it.name, it.cubes(), it.pose()) }
            for (spec in parts.drop(1)) palm.addOrReplaceChild(spec.name, spec.cubes(), spec.pose())
            return LayerDefinition.create(mesh, 16, 16)
        }
    }
}

/** One box part of the hand: the exported pivot offset (from its parent) and the box. */
internal class HandPartSpec(
    val name: String,
    val offset: Triple<Float, Float, Float>,
    val boxMin: Triple<Float, Float, Float>,
    val size: Triple<Float, Float, Float>,
    val texU: Int,
    val texV: Int,
) {
    fun cubes(): CubeListBuilder = CubeListBuilder.create().texOffs(texU, texV)
        .addBox(boxMin.first, boxMin.second, boxMin.third, size.first, size.second, size.third, CubeDeformation(0f))

    fun pose(): PartPose = PartPose.offset(offset.first, offset.second, offset.third)
}

private val FINGER_SIZE = Triple(2f, 2f, 4f)

/** The palm first (pivot (0, 24, 0)), then the four fingers, whose offsets are relative to the palm. */
internal fun partSpecs(left: Boolean): List<HandPartSpec> = listOf(
    HandPartSpec("palm", Triple(0f, 24f, 0f), Triple(-2.5f, -2f, -2f), Triple(5f, 2f, 5f), -4, 5),
    HandPartSpec("finger", Triple(-3f, -1f, -2f), Triple(-1f, -1f, -4f), FINGER_SIZE, 4, 6),
    HandPartSpec("finger2", Triple(0f, -1f, -2f), Triple(-1f, -1f, -4f), FINGER_SIZE, 4, 6),
    HandPartSpec("finger3", Triple(3f, -1f, -2f), Triple(-1f, -1f, -4f), FINGER_SIZE, 4, 6),
    HandPartSpec(
        "finger4", Triple(if (left) 2.5f else -2.5f, -1f, 3f),
        Triple(if (left) 0f else -2f, -1f, -4f), FINGER_SIZE, 4, 6,
    ),
)

/** Blockbench rotation (degrees) as the model rotation: the export mirrors X and Y. */
internal fun modelRotation(x: Float, y: Float, z: Float): Triple<Float, Float, Float> = Triple(-x, -y, z)
