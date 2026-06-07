package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.item.ItemDisplayContext

/**
 * In-world renderer for the Tome of Transportation's ghost-item visual. Called from
 * [org.shipwrights.enderkinesis.mixin.LevelRendererTransportationGhostsMixin] inside
 * `LevelRenderer.renderLevel`, just before the translucent block pass — at which point
 * the opaque, cutout, and entity passes have finished, so the items depth-test correctly
 * against world geometry but are still drawn before translucents that should blend over
 * them.
 *
 * Each in-flight ghost is rendered via `ItemRenderer.renderStatic` with
 * [ItemDisplayContext.GROUND] — the same display mode vanilla ItemEntities use, so the
 * visual matches what a dropped stack looks like (3D model with sprite-faces and proper
 * lighting orientation). No entity is involved at any point.
 *
 * The ghost tumbles on all three axes at deliberately incommensurate rates so the rotation
 * never looks like a flat synchronized spin.
 */
object TransportationGhostRenderer {

    // Tumble rates (deg/sec) on each axis. Coprime-ish values so the per-axis rotations
    // don't lock into a repeating compound motion — the ghost looks chaotic in the air.
    private const val PITCH_DEG_PER_SEC: Double = 73.0
    private const val YAW_DEG_PER_SEC: Double = 113.0
    private const val ROLL_DEG_PER_SEC: Double = 47.0

    fun render(poseStack: PoseStack, camera: Camera, partialTick: Float) {
        // HOISTED gate. Spark v8 attributed ~32 ms self to this
        // function despite the user reporting zero active
        // transportation links during sampling. The old structure
        // pre-resolved Minecraft.getInstance(), level, itemRenderer,
        // renderBuffers().bufferSource() and camera.position
        // BEFORE checking `flights.isEmpty()`, so every render
        // frame in the world paid those lookups even when nothing
        // was in flight. At high FPS that adds up. Now the flight
        // check is the very first statement — empty case returns
        // in nanoseconds before any state is touched.
        val flights = TransportationClient.activeFlights()
        if (flights.isEmpty()) return

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val itemRenderer = mc.itemRenderer
        val bufferSource = mc.renderBuffers().bufferSource()
        val cameraPos = camera.position

        val gameTime = level.gameTime
        val tSec = (gameTime + partialTick) / 20.0

        for (flight in flights) {
            val world = TransportationClient.renderPos(level, flight, partialTick) ?: continue

            poseStack.pushPose()
            poseStack.translate(
                world.x - cameraPos.x,
                world.y - cameraPos.y,
                world.z - cameraPos.z,
            )
            // Per-item tumble. The seed scales each axis's rate (range 0.7×–1.3×) and
            // injects a per-axis phase offset (different primes per axis), so two flights
            // dispatched on the same tick still have visibly distinct tumbles, and their
            // rotations diverge over time instead of staying locked in phase.
            val seed = flight.tumbleSeed.toDouble()
            val rateScale = 0.7 + seed * 0.6
            val phaseDeg = seed * 360.0
            val pitch = (tSec * PITCH_DEG_PER_SEC * rateScale + phaseDeg * 1.3) % 360.0
            val yaw = (tSec * YAW_DEG_PER_SEC * rateScale + phaseDeg * 2.7) % 360.0
            val roll = (tSec * ROLL_DEG_PER_SEC * rateScale + phaseDeg * 0.4) % 360.0
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw.toFloat()))
            poseStack.mulPose(Axis.XP.rotationDegrees(pitch.toFloat()))
            poseStack.mulPose(Axis.ZP.rotationDegrees(roll.toFloat()))
            // Full-bright so the ghost reads clearly even against shadowed walls — this is a
            // magical visual, not a real item subject to area lighting. NO_OVERLAY because
            // we don't want hit-flash / damage tint.
            itemRenderer.renderStatic(
                flight.stack,
                ItemDisplayContext.GROUND,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY,
                poseStack,
                bufferSource,
                level,
                0,
            )
            poseStack.popPose()
        }
        // Flush our additions immediately so they actually render in this pass instead of
        // queuing into the next flush boundary (which can drop them).
        bufferSource.endBatch()
    }
}
