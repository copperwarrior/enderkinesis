package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaternionf
import org.joml.Vector3f
import org.shipwrights.enderkinesis.blockentity.GasVentBlockEntity
import kotlin.math.exp
import kotlin.math.min

/** Draws the 16 louvre slats of the gas vent. The frame is the static block model; the slats tip with the power. */
class GasVentRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<GasVentBlockEntity> {

    private val scratch = Quaternionf()
    private val viewPoint = Vector3f()
    private val viewNormal = Vector3f()

    override fun render(
        be: GasVentBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val state = be.blockState
        val target = state.getValue(BlockStateProperties.POWER) / MAX_POWER
        val now = Util.getMillis()
        val last = be.clientLastRenderMs
        be.clientLastRenderMs = now
        be.clientOpen = if (last < 0L) target else {
            val dt = min(now - last, MAX_STEP_MS).toFloat()
            be.clientOpen + (target - be.clientOpen) * (1f - exp(-dt / EASE_MS))
        }
        val sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(TEXTURE)
        // Both sides of a slat show, so the slats use the no-cull entity type; light is read in front of the vent, like the block.
        val buffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS))
        val front = state.getValue(BlockStateProperties.FACING)
        val level = be.level
        val light = if (level == null) packedLight else LevelRenderer.getLightColor(level, be.blockPos.relative(front))
        val angle = Math.toRadians(CLOSED_ANGLE_DEG + (OPEN_ANGLE_DEG - CLOSED_ANGLE_DEG) * be.clientOpen.toDouble()).toFloat()

        ps.pushPose()
        ps.rotateAround(facingRotation(state.getValue(BlockStateProperties.FACING)), 0.5f, 0.5f, 0.5f)
        for (group in 0 until 2) {
            val z0 = if (group == 0) FRONT_Z0 else BACK_Z0
            val pivotZ = if (group == 0) FRONT_PIVOT_Z else BACK_PIVOT_Z
            for (slat in 0 until SLATS) {
                val y = (TOP_Y - slat) / 16f
                ps.pushPose()
                ps.translate(0.5, y.toDouble(), pivotZ.toDouble())
                ps.mulPose(scratch.rotationX(angle))
                ps.translate(-0.5, -y.toDouble(), -pivotZ.toDouble())
                // One quad on the side that faces the camera, so the two sides never z-fight and each is lit by its own normal.
                val pose = ps.last()
                pose.pose().transformPosition(0.5f, y, (z0 + DEPTH / 2f) / 16f, viewPoint)
                pose.normal().transform(0f, 1f, 0f, viewNormal)
                val upFacesCamera = viewNormal.dot(viewPoint) < 0f
                quad(buffer, ps, sprite, y, z0, upFacesCamera, light, packedOverlay)
                ps.popPose()
            }
        }
        ps.popPose()
    }

    /** One face of a flat slat; positions in blocks, UV rectangles in the 16-unit space of the json. */
    private fun quad(
        buffer: VertexConsumer, ps: PoseStack, sprite: TextureAtlasSprite, y: Float, z0: Float,
        up: Boolean, light: Int, overlay: Int,
    ) {
        val pose = ps.last()
        val x1 = X1 / 16f; val x2 = X2 / 16f
        val z1 = z0 / 16f; val z2 = (z0 + DEPTH) / 16f
        val v1 = if (up) UP_V else DOWN_V
        val ny = if (up) 1f else -1f
        // Up reads north-to-south down the texture; down reads south-to-north, as in vanilla.
        val vNorth = if (up) v1 else v1 + UV_H
        val vSouth = if (up) v1 + UV_H else v1
        corner(buffer, pose, sprite, x1, y, z1, UV_U1, vNorth, ny, light, overlay)
        corner(buffer, pose, sprite, x1, y, z2, UV_U1, vSouth, ny, light, overlay)
        corner(buffer, pose, sprite, x2, y, z2, UV_U2, vSouth, ny, light, overlay)
        corner(buffer, pose, sprite, x2, y, z1, UV_U2, vNorth, ny, light, overlay)
    }

    private fun corner(
        buffer: VertexConsumer, pose: PoseStack.Pose, sprite: TextureAtlasSprite,
        x: Float, y: Float, z: Float, u: Float, v: Float, ny: Float, light: Int, overlay: Int,
    ) {
        buffer.vertex(pose.pose(), x, y, z).color(255, 255, 255, 255)
            .uv(sprite.getU(u.toDouble()), sprite.getV(v.toDouble()))
            .overlayCoords(overlay).uv2(light).normal(pose.normal(), 0f, ny, 0f).endVertex()
    }

    private companion object {
        val TEXTURE = ResourceLocation("enderkinesis", "block/gas_vent")
        const val MAX_POWER = 15f
        // Tilt about the slat's X pivot from the flat slat plane of the json: 90 stands it upright across the opening.
        const val CLOSED_ANGLE_DEG = 90.0
        const val OPEN_ANGLE_DEG = 22.5
        const val EASE_MS = 150f
        const val MAX_STEP_MS = 250L
        const val SLATS = 8
        const val TOP_Y = 11.55866f
        const val X1 = 4f
        const val X2 = 12f
        const val DEPTH = 1f
        const val FRONT_Z0 = -0.53806f
        const val BACK_Z0 = 15.46194f
        const val FRONT_PIVOT_Z = -0.03806f / 16f
        const val BACK_PIVOT_Z = 15.96194f / 16f
        const val UV_U1 = 12f
        const val UV_U2 = 14f
        const val UV_H = 0.25f
        const val UP_V = 8f
        const val DOWN_V = 8.25f

        // Same rotations as the blockstate: north is the front, y is clockwise from above.
        val ROTATIONS: Map<Direction, Quaternionf> = mapOf(
            Direction.NORTH to Quaternionf(),
            Direction.SOUTH to Quaternionf().rotationY(Math.toRadians(-180.0).toFloat()),
            Direction.WEST to Quaternionf().rotationY(Math.toRadians(-270.0).toFloat()),
            Direction.EAST to Quaternionf().rotationY(Math.toRadians(-90.0).toFloat()),
            Direction.UP to Quaternionf().rotationX(Math.toRadians(-270.0).toFloat()),
            Direction.DOWN to Quaternionf().rotationX(Math.toRadians(-90.0).toFloat()),
        )

        fun facingRotation(facing: Direction): Quaternionf = ROTATIONS.getValue(facing)
    }
}
