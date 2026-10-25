package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.entity.SpellBoltEntity
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Spell Bolt: no item model, only light. A white-hot core inside a halo of the shape
 * element's colour, with motes circling it, always facing the camera. The trail is the
 * entity's own particles.
 */
class SpellBoltRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<SpellBoltEntity>(ctx) {

    override fun render(bolt: SpellBoltEntity, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val rgb = bolt.element()?.color ?: 0xFFFFFF
        val time = bolt.tickCount + partialTick
        pose.pushPose()
        pose.translate(0.0, bolt.bbHeight / 2.0, 0.0)
        pose.mulPose(entityRenderDispatcher.cameraOrientation())
        val c = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        val m = pose.last().pose()
        val throb = 1f + 0.1f * sin(time * 0.9f)
        disc(c, m, 0f, 0f, HALO * throb, rgb, 0.45f)
        disc(c, m, 0f, 0f, HALO * 0.55f * throb, rgb, 0.7f)
        disc(c, m, 0f, 0f, CORE, 0xFFFFFF, 1f)
        for (i in 0 until MOTES) {
            val angle = time * 0.5f + i * (2f * Math.PI.toFloat() / MOTES)
            val r = HALO * (0.7f + 0.15f * sin(time * 0.7f + i))
            disc(c, m, cos(angle) * r, sin(angle) * r, MOTE, rgb, 0.9f)
        }
        pose.popPose()
        super.render(bolt, yaw, partialTick, pose, buffers, light)
    }

    /** A soft disc in the camera plane: bright centre, clear edge. */
    private fun disc(c: VertexConsumer, m: Matrix4f, x: Float, y: Float, r: Float, rgb: Int, alpha: Float) {
        val red = (rgb shr 16) and 0xFF; val green = (rgb shr 8) and 0xFF; val blue = rgb and 0xFF
        val a = (alpha * 255).toInt()
        for (k in 0 until SECTORS) {
            val a0 = 2 * Math.PI * k / SECTORS
            val a1 = 2 * Math.PI * (k + 1) / SECTORS
            c.vertex(m, x, y, 0f).color(red, green, blue, a).endVertex()
            c.vertex(m, x, y, 0f).color(red, green, blue, a).endVertex()
            c.vertex(m, x + (cos(a1) * r).toFloat(), y + (sin(a1) * r).toFloat(), 0f).color(red, green, blue, 0).endVertex()
            c.vertex(m, x + (cos(a0) * r).toFloat(), y + (sin(a0) * r).toFloat(), 0f).color(red, green, blue, 0).endVertex()
        }
    }

    @Suppress("DEPRECATION")
    override fun getTextureLocation(bolt: SpellBoltEntity): ResourceLocation = TextureAtlas.LOCATION_BLOCKS

    private companion object {
        const val HALO = 0.42f
        const val CORE = 0.12f
        const val MOTE = 0.07f
        const val MOTES = 6
        const val SECTORS = 12
    }
}
