package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.world.inventory.InventoryMenu
import org.joml.Vector3f
import org.shipwrights.enderkinesis.blockentity.EnderAstrolabeBlockEntity

/**
 * Renders the astrolabe with the four Blockbench groups animated independently:
 *
 *  - the *base* draws straight, with no transform — it's the pedestal that stays put;
 *  - the *body* group (shaft + everything above) gets a yaw spin around `body.origin`
 *    (8, 1, 8 in Blockbench pixels);
 *  - the *pitch_rot* group (the rings and everything inside them) additionally gets a pitch
 *    tilt around `pitch_rot.origin` ((8, 11, 8) — the ring centre);
 *  - the *center* group (spyglasses + globe) additionally gets a roll spin around
 *    `center.origin` ((8, 12, 7)).
 *
 *  All angles come from [EnderAstrolabeBlockEntity], which interpolates between the angle at
 *  the start of the most recent "tune" event and the random target the BE chose on the cycle,
 *  with an ease-out curve. The renderer uses `partialTick` so the spin is smooth at any FPS.
 *
 *  Render layer is [RenderType.entityCutout] (alpha-tested, backface-culled, no blend) so the
 *  dedicated `ender_astrolabe.png` texture's transparent regions read as cutouts.
 */
class EnderAstrolabeRenderer : BlockEntityRenderer<EnderAstrolabeBlockEntity> {

    override fun render(
        be: EnderAstrolabeBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val mc = Minecraft.getInstance()
        val geom = EnderAstrolabeGeometry.ensureBaked(mc) ?: return
        val level = be.level ?: return

        val now = level.gameTime + partialTick.toDouble()
        val yaw = be.displayYaw(now)
        val pitch = be.displayPitch(now)
        val roll = be.displayRoll(now)

        val consumer = buffers.getBuffer(RenderType.entityCutout(InventoryMenu.BLOCK_ATLAS))

        // Base: no transform — pedestal sits still.
        renderQuads(geom.base, pose, consumer, packedLight, packedOverlay)

        // Body group: yaw around the body's pivot. All quads inside body are drawn under this
        // transform; the body pivot is in block-pixel units so we convert to render units
        // (1 block = 16 px) before translating.
        pose.pushPose()
        rotateAround(
            pose,
            EnderAstrolabeGeometry.BODY_ORIGIN_PX_X / 16f,
            EnderAstrolabeGeometry.BODY_ORIGIN_PX_Y / 16f,
            EnderAstrolabeGeometry.BODY_ORIGIN_PX_Z / 16f,
            axis = AXIS_Y, angleRad = yaw,
        )
        renderQuads(geom.shaft, pose, consumer, packedLight, packedOverlay)

        // pitch_rot group (nested in body): pitch around the ring centre.
        pose.pushPose()
        rotateAround(
            pose,
            EnderAstrolabeGeometry.PITCH_ORIGIN_PX_X / 16f,
            EnderAstrolabeGeometry.PITCH_ORIGIN_PX_Y / 16f,
            EnderAstrolabeGeometry.PITCH_ORIGIN_PX_Z / 16f,
            axis = AXIS_X, angleRad = pitch,
        )
        renderQuads(geom.pitchTop, pose, consumer, packedLight, packedOverlay)

        // center group (nested in pitch_rot): roll around the **spyglass's own Z axis**,
        // not the world / local-frame Z. Every element in this group (spyglass_a / b /
        // sphere) shares a `-45° X` element rotation that's baked into its quads, which
        // sends the spyglass's pre-bake local Z = `(0, 0, 1)` to `(0, sin 45°, cos 45°)` in
        // the post-bake frame the quads live in. Rotating around that axis spins the
        // spyglass around its own forward direction — true roll for the spyglass's body —
        // rather than around the world Z, which only tilts the entire assembly sideways.
        pose.pushPose()
        rotateAround(
            pose,
            EnderAstrolabeGeometry.CENTER_ORIGIN_PX_X / 16f,
            EnderAstrolabeGeometry.CENTER_ORIGIN_PX_Y / 16f,
            EnderAstrolabeGeometry.CENTER_ORIGIN_PX_Z / 16f,
            axis = AXIS_SPYGLASS_Z, angleRad = roll,
        )
        renderQuads(geom.center, pose, consumer, packedLight, packedOverlay)
        pose.popPose()

        pose.popPose()
        pose.popPose()
    }

    private fun renderQuads(
        quads: List<BakedQuad>, pose: PoseStack, vc: VertexConsumer, light: Int, overlay: Int,
    ) {
        val poseEntry = pose.last()
        for (q in quads) {
            vc.putBulkData(poseEntry, q, 1f, 1f, 1f, light, overlay)
        }
    }

    /** Rotate the pose-stack around the point `(px, py, pz)` in render units about [axis] by
     *  [angleRad] radians. Standard translate-rotate-untranslate pattern; uses
     *  [com.mojang.math.Axis] which exists in 1.20.1 but we go through the explicit Quaternion
     *  path so the same code works regardless of mapping naming. */
    private fun rotateAround(pose: PoseStack, px: Float, py: Float, pz: Float, axis: Vector3f, angleRad: Float) {
        pose.translate(px.toDouble(), py.toDouble(), pz.toDouble())
        pose.mulPose(org.joml.Quaternionf().setAngleAxis(angleRad.toDouble(), axis.x.toDouble(), axis.y.toDouble(), axis.z.toDouble()))
        pose.translate(-px.toDouble(), -py.toDouble(), -pz.toDouble())
    }

    private companion object {
        private val AXIS_X = Vector3f(1f, 0f, 0f)
        private val AXIS_Y = Vector3f(0f, 1f, 0f)

        /** The spyglass's local Z axis — in body-frame convention this is the **forward
         *  (looking)** direction, which is the spyglass's long barrel axis. In the model
         *  the spyglass is built along Y in element-local space (`spyglass_b` runs from
         *  `y = 1.5` to `y = 20.5`), then a `-45° X` element rotation tilts it. The MC /
         *  JOML right-hand-rule X rotation sends `(0, 1, 0)` to `(0, cos 45°, -sin 45°) =
         *  (0, 0.7071, -0.7071)` — that's where the barrel actually points after bake, and
         *  rotating around it spins the spyglass around its own length like rolling a
         *  pencil between fingers. The previous `+0.7071` Z was the transverse axis, which
         *  is why the spyglass was tumbling end-over-end instead of barrel-rolling. */
        private val AXIS_SPYGLASS_Z = Vector3f(0f, 0.70710677f, -0.70710677f)
    }
}
