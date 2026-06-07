package org.shipwrights.enderkinesis.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.animation.KeyframeAnimations
import net.minecraft.client.model.HierarchicalModel
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeDeformation
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition
import net.minecraft.util.Mth
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.Cataloger

/**
 * Cataloger model — robed humanoid. The part hierarchy is the artist's Blockbench
 * export verbatim (body pivot at `(0, 6, 0)`, `headwear` as a child of head, robe
 * nested under body with two sleeve sub-parts), because the [CatalogerAnimations]
 * keyframes were authored against those exact pivots.
 *
 * Animation is driven by Mojang's 1.19+ keyframe system:
 *  - [CatalogerAnimations.IDLE] runs at scale `(1 − blend)`.
 *  - [CatalogerAnimations.WALKING] runs at scale `blend`.
 *
 * Both [net.minecraft.world.entity.AnimationState]s on the entity stay running
 * continuously so their cycle phase is preserved across blend transitions
 * (no snap-to-frame-0 when the cataloger stops and starts again). The cross-fade
 * blend is lerped between `walkBlendOld` and `walkBlend` by partial-tick for
 * sub-tick smoothness, so the transition reads as a continuous interpolation
 * rather than a per-tick step.
 *
 * Animations are time-driven (`KeyframeAnimations.animate(...)` with the state's
 * accumulated time), not limb-swing-driven (`animateWalk`), so the artist's
 * 4-second walking cycle plays at its designed cadence regardless of the
 * cataloger's slow attribute-based movement speed.
 *
 * Head look (driven by `netHeadYaw` / `headPitch`) is applied manually before the
 * animations layer on top — [HierarchicalModel] doesn't give us free head tracking
 * the way [HumanoidModel] does.
 */
class CatalogerModel(private val root: ModelPart) : HierarchicalModel<Cataloger>() {

    private val head: ModelPart = root.getChild("head")

    /** Snapshot of `entity.intersectionAlpha` taken at the start of each
     *  `setupAnim`. Used by [renderToBuffer] to multiply through the per-vertex
     *  alpha so the model fades out as it overlaps another cataloger. Stored as
     *  a field because `renderToBuffer` doesn't have the entity in scope. */
    private var entityAlpha: Float = 1f

    override fun root(): ModelPart = root

    override fun setupAnim(
        entity: Cataloger,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        entityAlpha = entity.intersectionAlpha
        root.allParts.forEach { it.resetPose() }
        head.yRot = netHeadYaw * Mth.DEG_TO_RAD
        head.xRot = headPitch * Mth.DEG_TO_RAD

        // Partial-tick lerp between the entity's previous and current blend values
        // gives a sub-tick-smooth fade (frame-stepped per-tick blending would still
        // pop at 7 ticks/transition × 60+ fps rendering). The raw blend is a linear
        // 0→1 ramp; we then apply smootherstep so the cross-fade has zero first AND
        // second derivative at both endpoints — no acceleration kink at the moment
        // the cataloger starts/stops moving, and no snap into the fully-walking pose
        // at the end of the transition.
        val partialTick = (ageInTicks - entity.tickCount.toFloat()).coerceIn(0f, 1f)
        val rawBlend = Mth.lerp(partialTick, entity.walkBlendOld, entity.walkBlend)
        val blend = smootherstep(rawBlend)

        // Advance both clocks regardless of which is "visible" — keeps cycle phase
        // continuous when the user blends between them.
        entity.idleAnimationState.updateTime(ageInTicks, 1f)
        entity.walkingAnimationState.updateTime(ageInTicks, 1f)

        // KeyframeAnimations.animate accepts a scale factor that's applied to each
        // channel's value before it's `+=`'d onto the part. So we get a clean linear
        // blend by calling each animation with its own weight; values sum on shared
        // bones (head, body, robe), which is exactly what a cross-fade wants.
        if (blend < 1f) {
            entity.idleAnimationState.ifStarted { state ->
                KeyframeAnimations.animate(this, CatalogerAnimations.IDLE,
                    state.accumulatedTime, 1f - blend, ANIMATION_VECTOR_CACHE)
            }
        }
        if (blend > 0f) {
            entity.walkingAnimationState.ifStarted { state ->
                KeyframeAnimations.animate(this, CatalogerAnimations.WALKING,
                    state.accumulatedTime, blend, ANIMATION_VECTOR_CACHE)
            }
        }
    }

    /**
     * Multiply the incoming vertex alpha by [entityAlpha] (captured in [setupAnim])
     * before letting [HierarchicalModel] forward the call down the part tree. Used
     * to fade the cataloger out when it overlaps another cataloger — the alpha
     * factor is pre-multiplied onto every vertex.
     *
     * For the alpha to actually blend (rather than be discarded by the cutout
     * shader), the renderer must also switch to a translucent render type — see
     * `CatalogerRenderer.getRenderType`.
     */
    override fun renderToBuffer(
        poseStack: PoseStack,
        vertexConsumer: VertexConsumer,
        packedLight: Int,
        packedOverlay: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        super.renderToBuffer(
            poseStack, vertexConsumer, packedLight, packedOverlay,
            red, green, blue, alpha * entityAlpha,
        )
    }

    /**
     * Ken Perlin's smootherstep: `t³(6t² − 15t + 10) = 6t⁵ − 15t⁴ + 10t³`. Maps
     * `[0, 1]` to `[0, 1]` with first AND second derivative zero at both endpoints
     * — i.e. no acceleration discontinuity anywhere along the curve. Used to
     * ease the linear walk-blend ramp into an S-curve for natural cross-fade.
     *
     * Smoother than the more common `smoothstep(t) = t²(3 − 2t)`, which has zero
     * first derivative at endpoints but a non-zero second derivative (acceleration
     * starts/ends abruptly).
     */
    private fun smootherstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * c * (c * (c * 6f - 15f) + 10f)
    }

    companion object {
        /** Shared scratch vector used by [KeyframeAnimations.animate]. Static-shared
         *  matches what `HierarchicalModel` does internally; MC rendering is
         *  single-threaded, so the sharing is safe. */
        private val ANIMATION_VECTOR_CACHE = Vector3f()

        val LAYER_LOCATION: ModelLayerLocation =
            ModelLayerLocation(EnderkinesisMod.id("cataloger"), "main")

        @JvmStatic
        fun createBodyLayer(): LayerDefinition {
            val mesh = MeshDefinition()
            val root = mesh.root

            val head = root.addOrReplaceChild(
                "head",
                CubeListBuilder.create()
                    .texOffs(0, 0)
                    .addBox(-4.0f, -8.0f, -4.0f, 8.0f, 8.0f, 8.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, 0.0f, 0.0f),
            )

            head.addOrReplaceChild(
                "headwear",
                CubeListBuilder.create()
                    .texOffs(32, 0)
                    .addBox(-4.0f, -8.0f, -4.0f, 8.0f, 8.0f, 8.0f, CubeDeformation(0.5f)),
                PartPose.offset(0.0f, 0.0f, 0.0f),
            )

            val body = root.addOrReplaceChild(
                "body",
                CubeListBuilder.create()
                    .texOffs(16, 16)
                    .addBox(-4.0f, -6.0f, -2.0f, 8.0f, 12.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offset(0.0f, 6.0f, 0.0f),
            )

            val robe = body.addOrReplaceChild(
                "robe",
                CubeListBuilder.create()
                    .texOffs(0, 32)
                    .addBox(-3.0f, 0.0132f, -2.2394f, 6.0f, 12.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(0.0f, 5.812f, 0.3866f, 0.2618f, 0.0f, 0.0f),
            )

            robe.addOrReplaceChild(
                "body_r1",
                CubeListBuilder.create()
                    .texOffs(21, 33)
                    .addBox(2.0f, -6.0f, -1.0f, 3.0f, 12.0f, 3.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(-7.0f, 5.7632f, -1.2394f, -0.0088f, -0.2527f, 0.0692f),
            )

            robe.addOrReplaceChild(
                "body_r2",
                CubeListBuilder.create()
                    .texOffs(35, 33)
                    .addBox(-5.0f, -6.0f, -1.0f, 3.0f, 12.0f, 3.0f, CubeDeformation(0.0f)),
                PartPose.offsetAndRotation(7.0f, 5.7632f, -1.2394f, -0.0088f, 0.2527f, -0.0692f),
            )

            root.addOrReplaceChild(
                "left_arm",
                CubeListBuilder.create()
                    .texOffs(40, 16)
                    .mirror()
                    .addBox(-1.0f, -2.0f, -2.0f, 4.0f, 12.0f, 4.0f, CubeDeformation(0.0f))
                    .mirror(false),
                PartPose.offset(5.0f, 2.0f, 0.0f),
            )

            root.addOrReplaceChild(
                "right_arm",
                CubeListBuilder.create()
                    .texOffs(40, 16)
                    .addBox(-3.0f, -2.0f, -2.0f, 4.0f, 12.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offset(-5.0f, 2.0f, 0.0f),
            )

            root.addOrReplaceChild(
                "left_leg",
                CubeListBuilder.create()
                    .texOffs(0, 16)
                    .mirror()
                    .addBox(-1.9f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, CubeDeformation(0.0f))
                    .mirror(false),
                PartPose.offset(1.9f, 12.0f, 0.0f),
            )

            root.addOrReplaceChild(
                "right_leg",
                CubeListBuilder.create()
                    .texOffs(0, 16)
                    .addBox(-2.1f, 0.0f, -2.0f, 4.0f, 12.0f, 4.0f, CubeDeformation(0.0f)),
                PartPose.offset(-1.9f, 12.0f, 0.0f),
            )

            return LayerDefinition.create(mesh, 64, 64)
        }
    }
}
