package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.ConstriliarPlatformBlockEntity
import org.shipwrights.enderkinesis.client.model.ConstriliarModel
import org.shipwrights.enderkinesis.entity.ConstriliarEntity
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipObjectManagingPos

/**
 * Draws the Constriliar's body + IK legs from the platform block, i.e. in ship space —
 * one interpolation stream with the cargo blocks, so body and load never visibly
 * separate. The model→shipyard mapping is constant by construction (platform centre =
 * model ground plane, ship yaw = body yaw): RotY(180°) ∘ scale(−1,−1,1) ∘ −1.501y —
 * the same chain LivingEntityRenderer applies after its yaw rotation. IK runs against
 * the ship's *render* transform so feet solve in the frame the body is drawn in.
 *
 * During the spawn intro [ConstriliarRenderer] draws the body on the ENTITY instead
 * (clients have entities immediately; the ship gets the whole intro to load in), so
 * this renderer yields until the intro ends.
 */
class ConstriliarPlatformRenderer(
    ctx: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<ConstriliarPlatformBlockEntity> {

    private val model = ConstriliarModel(ctx.bakeLayer(ConstriliarModel.LAYER_LOCATION))

    /** The body is an entity in all but rendering plumbing — cull it at entity-scale
     *  distance, not the 64-block block-entity default. */
    override fun getViewDistance(): Int = 192

    override fun render(
        be: ConstriliarPlatformBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level ?: return
        val entity = level.getEntity(be.ownerEntityId) as? ConstriliarEntity ?: return
        if (entity.introActive()) return
        val ship = level.getShipObjectManagingPos(be.blockPos) as? ClientShip ?: return

        val rt = ship.renderTransform
        val centre = Vector3d(be.blockPos.x + 0.5, be.blockPos.y + 0.5, be.blockPos.z + 0.5)
        rt.shipToWorld.transformPosition(centre)
        val shipRot = rt.shipToWorldRotation
        val bodyQuat = Quaternionf(
            shipRot.x().toFloat(),
            shipRot.y().toFloat(),
            shipRot.z().toFloat(),
            shipRot.w().toFloat(),
        ).rotateY(Math.PI.toFloat())
        entity.gait.solveRenderAngles(Vec3(centre.x, centre.y, centre.z), bodyQuat, partialTick)
        model.setupAnim(entity, 0f, 0f, 0f, 0f, 0f)

        // Merge world and ship light: BLOCK light is the max of both — the world probe
        // (same one the entity renderer uses, so the intro handoff never flashes) plus
        // the dispatcher's shipyard sample, so a torch carried as cargo lights the
        // body. SKY light comes from the world ONLY: the shipyard's sky is the void's
        // constant 15, which would glow sky-bright in caves.
        val probeLight = net.minecraft.client.renderer.LevelRenderer.getLightColor(
            level,
            net.minecraft.core.BlockPos.containing(entity.getLightProbePosition(partialTick)),
        )
        val worldLight = net.minecraft.client.renderer.LightTexture.pack(
            maxOf(
                net.minecraft.client.renderer.LightTexture.block(probeLight),
                net.minecraft.client.renderer.LightTexture.block(packedLight),
            ),
            net.minecraft.client.renderer.LightTexture.sky(probeLight),
        )

        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(180f))
        poseStack.scale(-1f, -1f, 1f)
        poseStack.translate(0.0, -1.501, 0.0)
        ConstriliarBodySkin.renderBody(poseStack, buffer, model, entity, -1f, partialTick, worldLight)
        poseStack.popPose()
    }
}
