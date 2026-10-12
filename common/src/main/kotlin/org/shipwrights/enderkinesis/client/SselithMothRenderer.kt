package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.client.model.SselithMothModel
import org.shipwrights.enderkinesis.entity.SselithMothEntity

/**
 * Sselith Moth renderer. Standard [MobRenderer] with the boid-driven flock
 * entity + Blockbench fly/idle animations, plus perch alignment: a landed
 * moth is rotated so its belly rests on the actual face it clings to.
 * The alignment slerps in with the entity's landed blend, matching the
 * fly→idle animation cross-fade instead of popping.
 */
class SselithMothRenderer(ctx: EntityRendererProvider.Context) :
    MobRenderer<SselithMothEntity, SselithMothModel>(
        ctx,
        SselithMothModel(ctx.bakeLayer(SselithMothModel.LAYER_LOCATION)),
        0.0f,
    ) {
    init {
        // Camouflage: landed moths take on the perched block's texture.
        addLayer(SselithMothCamoLayer(this))
        // Glowing markings, drawn last so the additive glow sits on top of
        // both the translucent body and any camouflage.
        addLayer(SselithMothEmissiveLayer(this))
    }

    override fun getTextureLocation(entity: SselithMothEntity): ResourceLocation =
        SselithMothEntity.TEXTURE

    override fun setupRotations(
        entity: SselithMothEntity,
        poseStack: PoseStack,
        ageInTicks: Float,
        rotationYaw: Float,
        partialTicks: Float,
    ) {
        super.setupRotations(entity, poseStack, ageInTicks, rotationYaw, partialTicks)
        // perchRenderNormal (not landedNormal): survives through the
        // takeoff fade so the tilt eases back out with the blend instead
        // of snapping off the moment the synced face clears.
        val normal = entity.perchRenderNormal() ?: return
        val blend = entity.landedBlend(partialTicks)
        if (blend <= 0.001f) return
        applyPerchAlignment(poseStack, normal, rotationYaw, blend)
    }

    companion object {

        /** Perch tilt applied inside the yaw-rotated frame (after vanilla's
         *  YP(180 − yaw)). Shared with [SselithMothCamoLayer], which must
         *  rebuild this exact pose to bake the camo projection. */
        @JvmStatic
        fun applyPerchAlignment(
            poseStack: PoseStack,
            normal: net.minecraft.world.phys.Vec3,
            rotationYaw: Float,
            blend: Float,
        ) {
            when {
                // Straight-down (ceiling) normals make rotationTo ambiguous —
                // roll about the body axis instead, keeping the landing yaw.
                normal.y < -0.95 -> poseStack.mulPose(Axis.ZP.rotationDegrees(180f * blend))
                else -> {
                    // Map model-up onto the smoothed SDF normal. The pose is
                    // already in the yaw-rotated frame, so the WORLD normal
                    // must be rotated into that local frame first.
                    val theta = Math.toRadians(180.0 - rotationYaw)
                    val c = kotlin.math.cos(theta)
                    val s = kotlin.math.sin(theta)
                    val lx = normal.x * c - normal.z * s
                    val lz = normal.x * s + normal.z * c
                    val target = Quaternionf().rotationTo(
                        0f, 1f, 0f,
                        lx.toFloat(), normal.y.toFloat(), lz.toFloat(),
                    )
                    poseStack.mulPose(Quaternionf().slerp(target, blend))
                }
            }
        }
    }
}
