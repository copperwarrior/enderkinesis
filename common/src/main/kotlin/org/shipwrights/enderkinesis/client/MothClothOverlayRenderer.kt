package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.block.MothClothBlock
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipObjectManagingPos

/**
 * Per-frame overlay pass drawing the Moth Cloth glint on top of the base
 * wool (which the vanilla SOLID pass — plus VS2's ship-batched SOLID for
 * ship blocks — already rendered).
 *
 * Runs at the end of {@code LevelRenderer.renderLevel} via
 * {@link org.shipwrights.enderkinesis.mixin.LevelRendererMothClothOverlayMixin}.
 * For each Moth Cloth position tracked in {@link MothClothClientPositions}:
 *   - World blocks: PoseStack translated by (pos - cam), then vanilla block
 *     model is emitted via {@code BlockRenderDispatcher.renderBatched} into
 *     our custom RenderType's buffer.
 *   - Ship blocks: {@code ClientShip.renderTransform.shipToWorld} is
 *     multiplied into the PoseStack before the block-position translate, so
 *     the glint quads follow the ship's orientation exactly.
 */
object MothClothOverlayRenderer {

    private val random: RandomSource = RandomSource.create()

    /** Must keep moth_cloth.fsh's GLINT_CELL_PERIOD seamless across the wrap. */
    private const val WORLD_WRAP: Double = 2000.0

    private fun wrap(coord: Double): Double = coord - Math.floor(coord / WORLD_WRAP) * WORLD_WRAP

    @JvmStatic
    fun render(
        poseStack: PoseStack,
        camX: Double,
        camY: Double,
        camZ: Double,
    ) {
        val mc = Minecraft.getInstance()
        val level: ClientLevel = mc.level ?: return
        // Ship chunks bypass the chunk-load scan mixin (VS2 cancels
        // replaceWithPacketData for shipyard chunks) — rescan them here.
        // Internally rate-limited to once per 2 s.
        MothClothClientPositions.rescanShips(level)
        if (MothClothClientPositions.size() == 0) return

        val shader = MothClothRenderType.shader ?: return
        // Wrapped: floats lose sub-block precision far from the origin, and the glint repeats over this span.
        shader.getUniform("CameraPos")
            ?.set(wrap(camX).toFloat(), wrap(camY).toFloat(), wrap(camZ).toFloat())
        shader.getUniform("WallTime")
            ?.set(((System.currentTimeMillis() % 1000000L) / 1000.0).toFloat())

        val bufferSource = mc.renderBuffers().bufferSource()
        val buffer = bufferSource.getBuffer(MothClothRenderType.TYPE)
        val blockRenderer = mc.blockRenderer

        // We use a FRESH identity PoseStack rather than the passed
        // `poseStack` because the passed one already has the camera
        // rotation baked in (applied by GameRenderer before renderLevel).
        // When the buffer is flushed via `endBatch`, the shader's
        // ModelViewMat uniform is set from RenderSystem's ModelView (also
        // camera rotation). Emitting with the passed pose would multiply
        // camera rotation twice, producing the "camera-locked" artifact.
        // With an identity pose here, the buffer contains vertices at
        // pure camera-relative world space, and ModelViewMat (camera
        // rotation) transforms them once into view space.
        val emitPose = com.mojang.blaze3d.vertex.PoseStack()

        MothClothClientPositions.forEach { pos ->
            val state = level.getBlockState(pos)
            if (state.block !is MothClothBlock) return@forEach

            val ship = level.getShipObjectManagingPos(pos) as? ClientShip

            // Cloaked ships render into the concealment side FB, not the
            // main FB — our overlay pass runs outside VS2's per-ship
            // events, so without this gate the glint would keep drawing
            // in the open air while the Staff of Concealment hides the
            // ship under it.
            if (ship != null && ClientShipCloakingState.getProgress(ship.id) > 0.001f) return@forEach

            emitPose.pushPose()
            if (ship != null) {
                // Ship block. All large-coordinate math must stay in
                // doubles: shipToWorld's translation column carries
                // shipyard-scale values (~28M blocks, float ulp ≈ 2
                // blocks), so downcasting the full matrix to Matrix4f
                // yields a multi-block, per-frame-jumping offset. Instead
                // transform the block origin through shipToWorld in
                // double precision, translate by the small camera-relative
                // result, and downcast only the rotation/scale 3×3.
                val s2w = ship.renderTransform.shipToWorld
                val origin = s2w.transformPosition(
                    org.joml.Vector3d(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
                )
                emitPose.translate(origin.x - camX, origin.y - camY, origin.z - camZ)
                // Column-major linear (rotation/scale) part only, no translation.
                emitPose.mulPoseMatrix(
                    Matrix4f(
                        s2w.m00().toFloat(), s2w.m01().toFloat(), s2w.m02().toFloat(), 0f,
                        s2w.m10().toFloat(), s2w.m11().toFloat(), s2w.m12().toFloat(), 0f,
                        s2w.m20().toFloat(), s2w.m21().toFloat(), s2w.m22().toFloat(), 0f,
                        0f, 0f, 0f, 1f,
                    ),
                )
            } else {
                emitPose.translate(pos.x - camX, pos.y - camY, pos.z - camZ)
            }
            blockRenderer.renderBatched(
                state, BlockPos(pos), level, emitPose, buffer, true, random,
            )
            emitPose.popPose()
        }

        // Flush with the camera rotation pushed into RenderSystem's
        // model-view stack. Our shader is chunk-style: it expects
        // UNROTATED camera-relative vertex positions (so worldPos =
        // pos + CameraPos reconstructs true world coords) and the camera
        // rotation in ModelViewMat. BufferUploader._drawWithShader sets
        // ModelViewMat from RenderSystem.getModelViewMatrix(), which is
        // identity during renderLevel (vanilla entity vertices are fully
        // CPU-rotated instead) — so without this bracket the geometry
        // draws unrotated and lands off-screen.
        val mvStack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()
        mvStack.pushPose()
        mvStack.mulPoseMatrix(poseStack.last().pose())
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
        try {
            bufferSource.endBatch(MothClothRenderType.TYPE)
        } finally {
            mvStack.popPose()
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix()
        }
    }
}
