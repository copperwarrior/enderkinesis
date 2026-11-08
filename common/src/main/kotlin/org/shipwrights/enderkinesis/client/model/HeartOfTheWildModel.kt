package org.shipwrights.enderkinesis.client.model

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
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Extends [HierarchicalModel] only for [KeyframeAnimations.animate]; the [Entity] type
 * parameter is unused (this is a BER, [setupAnim] is unreachable).
 */
class HeartOfTheWildModel(private val bakedRoot: ModelPart) : HierarchicalModel<Entity>() {

    override fun root(): ModelPart = bakedRoot

    private val arteryChains: List<List<ModelPart>> = ARTERY_PARTS.map { (bone, cube) ->
        val heart = bakedRoot.getChild("Heart2")
        val artery = heart.getChild(bone)
        listOf(bakedRoot, heart, artery, artery.getChild(cube))
    }

    /** Writes the open end of artery [index] and its outward direction in the frame of [pose], which this changes. */
    fun arteryTip(index: Int, pose: Matrix4f, pos: Vector3f, dir: Vector3f) {
        for (part in arteryChains[index]) applyPose(part, pose)
        val tip = ARTERY_TIPS[index]
        pose.transformPosition(tip.x / 16f, tip.y / 16f, tip.z / 16f, pos)
        pose.transformDirection(0f, -1f, 0f, dir).normalize()
    }

    // The same transform as ModelPart.translateAndRotate, on a matrix instead of a PoseStack.
    private fun applyPose(part: ModelPart, pose: Matrix4f) {
        pose.translate(part.x / 16f, part.y / 16f, part.z / 16f)
        if (part.xRot != 0f || part.yRot != 0f || part.zRot != 0f) pose.rotateZYX(part.zRot, part.yRot, part.xRot)
        if (part.xScale != 1f || part.yScale != 1f || part.zScale != 1f) pose.scale(part.xScale, part.yScale, part.zScale)
    }

    override fun setupAnim(
        entity: Entity, limbSwing: Float, limbSwingAmount: Float,
        ageInTicks: Float, netHeadYaw: Float, headPitch: Float,
    ) {
    }

    fun animateBeatMillis(clockMillis: Long, rateScalar: Float) {
        // Vanilla converts the millis to Float before its modulo; millis since boot lose the
        // frame steps after some days. Wrap to the loop first.
        val loopMillis = HeartOfTheWildAnimations.BEAT_MILLIS.coerceAtLeast(1L)
        val scaledMillis = (clockMillis.toDouble() * rateScalar.toDouble()).toLong() % loopMillis
        play(HeartOfTheWildAnimations.BEATING, scaledMillis)
    }

    fun animateGrowthMillis(elapsedMillis: Long) {
        play(HeartOfTheWildAnimations.SUMMON, elapsedMillis)
    }

    /** [KeyframeAnimations] applies additively — the resetPose loop is mandatory each frame. */
    private fun play(animation: AnimationDefinition, millis: Long) {
        bakedRoot.allParts.forEach { it.resetPose() }
        KeyframeAnimations.animate(this, animation, millis, 1f, ANIMATION_VECTOR_CACHE)
    }

    companion object {
        private val ANIMATION_VECTOR_CACHE = Vector3f()

        const val ARTERY_COUNT: Int = 3
        private val ARTERY_PARTS = listOf("arety1" to "arety1_r1", "artety2" to "artety2_r1", "arthreery" to "arthreery_r1")
        // Centre of the top face of each artery cube in createBodyLayer; model -Y is up.
        private val ARTERY_TIPS = listOf(Vector3f(-1f, -11f, 1f), Vector3f(-0.5f, -7f, 0.5f), Vector3f(-1f, -9f, 1f))

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id("heart_of_the_wild"), "main")

        private fun rad(degrees: Double): Float = Math.toRadians(degrees).toFloat()

        /** Converted from `heart.bbmodel`; part names match its bones so the keyframes resolve. */
        @Suppress("LongMethod") // verbatim Blockbench geometry
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root

            val heart = root.addOrReplaceChild(
                "Heart2",
                CubeListBuilder.create(),
                PartPose.offsetAndRotation(-2.0f, 7.0f, 0.0f, 0f, 0f, rad(-12.5)),
            )

            heart.addOrReplaceChild(
                "bone1",
                CubeListBuilder.create()
                    .texOffs(38, 34)
                    .addBox(-6.0f, -6.0f, -6.0f, 8.0f, 10.0f, 12.0f, CubeDeformation(0.3f)),
                PartPose.offset(-0.5862f, 6.4834f, 1.0f),
            )

            heart.addOrReplaceChild(
                "bone2",
                CubeListBuilder.create()
                    .texOffs(0, 0)
                    .addBox(-6.0f, -5.0f, -7.0f, 13.0f, 11.0f, 15.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.4138f, 5.4834f, -1.0f),
            )

            heart.addOrReplaceChild(
                "bone3",
                CubeListBuilder.create()
                    .texOffs(0, 26)
                    .addBox(-6.0f, -1.0f, -7.0f, 12.0f, 7.0f, 13.0f, CubeDeformation(-0.5f)),
                PartPose.offset(1.4138f, 11.4834f, -1.0f),
            )

            heart.addOrReplaceChild(
                "arety1",
                CubeListBuilder.create(),
                PartPose.offset(-0.5862f, 0.4834f, -4.0f),
            ).addOrReplaceChild(
                "arety1_r1",
                CubeListBuilder.create()
                    .texOffs(41, 0)
                    .addBox(-3.0f, -11.0f, -1.0f, 4.0f, 11.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(-1.0f, 2.0f, -1.0f, 0f, 0f, rad(55.0)),
            )

            heart.addOrReplaceChild(
                "artety2",
                CubeListBuilder.create(),
                PartPose.offset(-1.8362f, 0.4834f, 4.5f),
            ).addOrReplaceChild(
                "artety2_r1",
                CubeListBuilder.create()
                    .texOffs(0, 0)
                    .addBox(-2.0f, -7.0f, -1.0f, 3.0f, 7.0f, 3.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.25f, 1.0f, -0.5f, 0f, 0f, rad(12.5)),
            )

            heart.addOrReplaceChild(
                "arthreery",
                CubeListBuilder.create(),
                PartPose.offset(-2.5862f, -0.2666f, -1.0f),
            ).addOrReplaceChild(
                "arthreery_r1",
                CubeListBuilder.create()
                    .texOffs(0, 46)
                    .addBox(-3.0f, -9.0f, -1.0f, 4.0f, 9.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, 1.75f, -1.0f, rad(-10.0), 0f, rad(40.0)),
            )

            return LayerDefinition.create(mesh, 128, 128)
        }
    }
}
