package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.SwarmFlyEntity

/**
 * Draws each fly of a swarm as a camera-facing 3×2 px sprite on a seeded orbit around the swarm centre.
 * The orbit is a pure function of time and fly index, so the client keeps no per-fly state.
 */
class SwarmFlyRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<SwarmFlyEntity>(ctx) {

    private val right = Vector3f()
    private val up = Vector3f()
    private val corner = Vector3f()

    override fun getTextureLocation(entity: SwarmFlyEntity): ResourceLocation = SHEET

    override fun render(
        entity: SwarmFlyEntity, entityYaw: Float, partialTick: Float, poseStack: PoseStack,
        buffer: MultiBufferSource, packedLight: Int,
    ) {
        val count = entity.flyCount
        if (count <= 0) return
        val camera = entityRenderDispatcher.cameraOrientation()
        right.set(HALF_WIDTH, 0f, 0f).rotate(camera)
        up.set(0f, HALF_HEIGHT, 0f).rotate(camera)
        val matrix = poseStack.last().pose()
        val consumer = buffer.getBuffer(RENDER_TYPE)
        val resting = entity.mode == SwarmFlyEntity.Mode.REST
        val radius = entity.cloudRadius
        val time = (entity.tickCount + partialTick) * ORBIT_RATE * (if (resting) REST_PACE else 1f)
        val flapTime = entity.tickCount + partialTick
        val centreY = entity.bbHeight / 2f
        for (i in 0 until count) {
            val h = flySeed(entity.id * SEED_STRIDE + i)
            val fx = MIN_FREQ + byteAt(h, 0) * FREQ_SPREAD
            val fy = MIN_FREQ + byteAt(h, 1) * FREQ_SPREAD
            val fz = MIN_FREQ + byteAt(h, 2) * FREQ_SPREAD
            val phase = byteAt(h, 3) * Mth.TWO_PI
            val h2 = flySeed(h)
            val reach = radius * (MIN_REACH + byteAt(h2, 0) * (1f - MIN_REACH))
            val ox = reach * Mth.sin(time * fx + phase) * Mth.cos(time * fz * DRIFT_FREQ + phase * 2f)
            val oy = centreY + reach * VERTICAL_SQUASH * Mth.sin(time * fy + phase * 3f)
            val oz = reach * Mth.cos(time * fz + phase) * Mth.sin(time * fx * DRIFT_FREQ + phase)
            val variant = (h2 ushr 8 and 0xFF) % VARIANTS
            val frame = ((flapTime * FLAP_RATE + byteAt(h2, 2) * 2f).toInt()) and 1
            val u0 = frame * FLY_PIXELS_WIDE / SHEET_PIXELS
            val v0 = variant * FLY_PIXELS_HIGH / SHEET_PIXELS
            val u1 = u0 + FLY_PIXELS_WIDE / SHEET_PIXELS
            val v1 = v0 + FLY_PIXELS_HIGH / SHEET_PIXELS
            quadCorner(consumer, matrix, ox, oy, oz, -1f, -1f, u0, v1, packedLight)
            quadCorner(consumer, matrix, ox, oy, oz, 1f, -1f, u1, v1, packedLight)
            quadCorner(consumer, matrix, ox, oy, oz, 1f, 1f, u1, v0, packedLight)
            quadCorner(consumer, matrix, ox, oy, oz, -1f, 1f, u0, v0, packedLight)
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight)
    }

    // VertexConsumer.vertex(Matrix4f, ...) and normal(Matrix3f, ...) allocate a vector per call; transform into a scratch.
    @Suppress("LongParameterList") // One quad corner: offset, corner sign, UV and light.
    private fun quadCorner(
        consumer: VertexConsumer, matrix: Matrix4f, ox: Float, oy: Float, oz: Float,
        sx: Float, sy: Float, u: Float, v: Float, light: Int,
    ) {
        matrix.transformPosition(
            ox + right.x * sx + up.x * sy,
            oy + right.y * sx + up.y * sy,
            oz + right.z * sx + up.z * sy,
            corner,
        )
        consumer.vertex(corner.x.toDouble(), corner.y.toDouble(), corner.z.toDouble())
            .color(WHITE, WHITE, WHITE, WHITE)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(0f, 1f, 0f)
            .endVertex()
    }

    private companion object {
        val SHEET: ResourceLocation = EnderkinesisMod.id("textures/entity/${SwarmFlyEntity.ID_PATH}.png")
        val RENDER_TYPE: RenderType = RenderType.entityCutoutNoCull(SHEET)

        // Sheet: 16×16 px; each fly is 3×2 px; frame 1 at x 0–2, frame 2 at x 3–5; one variant per 2 px row.
        const val SHEET_PIXELS = 16f
        const val FLY_PIXELS_WIDE = 3f
        const val FLY_PIXELS_HIGH = 2f
        const val VARIANTS = 5
        // One sheet pixel is 1/32 block, so a fly is about 0.09 × 0.06 blocks.
        const val PIXEL = 1f / 32f
        const val HALF_WIDTH = FLY_PIXELS_WIDE * PIXEL / 2f
        const val HALF_HEIGHT = FLY_PIXELS_HIGH * PIXEL / 2f

        const val WHITE = 255
        const val SEED_STRIDE = 0x9E37
        const val ORBIT_RATE = 0.12f
        const val REST_PACE = 0.25f
        const val MIN_FREQ = 0.6f
        const val FREQ_SPREAD = 0.8f
        const val DRIFT_FREQ = 0.31f
        const val MIN_REACH = 0.35f
        const val VERTICAL_SQUASH = 0.6f
        const val FLAP_RATE = 1f
    }
}

private const val BYTE_MASK = 0xFF
private const val BYTE_MAX = 255f
private const val BITS_PER_BYTE = 8

private fun byteAt(hash: Int, index: Int): Float = (hash ushr (index * BITS_PER_BYTE) and BYTE_MASK) / BYTE_MAX

// Murmur3 finaliser: spreads consecutive fly indices into unrelated bits.
@Suppress("MagicNumber")
private fun flySeed(value: Int): Int {
    var h = value * -0x61c88647
    h = h xor (h ushr 16)
    h *= -0x7a143595
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb
    return h xor (h ushr 16)
}
