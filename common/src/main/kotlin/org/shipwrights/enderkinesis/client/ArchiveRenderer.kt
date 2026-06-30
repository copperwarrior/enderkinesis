package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.entity.ArchiveEntity
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws an Archive's whirlwind as a ring of generic paper + book item models
 * orbiting the entity's axis. The actual `contents` list isn't synced to the
 * client — the renderer's job is just to communicate "this is a tornado of
 * archive-flavoured stuff"; the real loot only matters at collapse-time
 * (server drops it). Per-frame angular position uses `tickCount + partialTick`
 * so the spin is smooth at any framerate.
 */
class ArchiveRenderer(ctx: EntityRendererProvider.Context) :
    EntityRenderer<ArchiveEntity>(ctx) {

    private val itemRenderer = Minecraft.getInstance().itemRenderer
    private val fallbackPaper: ItemStack = ItemStack(Items.PAPER)

    override fun render(
        entity: ArchiveEntity,
        entityYaw: Float,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
    ) {
        val time = entity.tickCount.toFloat() + partialTick

        // Orbit the actual items the archive will drop on collapse (synced from
        // the server via DATA_CONTENTS). Fall back to a single paper if the
        // sync hasn't landed yet — keeps the tornado visible during the first
        // few client ticks after the entity tracker fires.
        val contents = entity.clientContents().ifEmpty { listOf(fallbackPaper) }
        val itemCount = contents.size

        for (i in 0 until itemCount) {
            val baseAngle = (i.toFloat() / itemCount) * (2f * Math.PI.toFloat())
            val angle = baseAngle + time * SPIN_RATE
            val heightPhase = (time * RISE_RATE + i * 0.7f) % HEIGHT_SPAN
            // Funnel taper: radius 0 at y=0, full at y=HEIGHT_SPAN. Tornado shape
            // — items collapse toward the centre near the bottom, fan out at the top.
            val taper = heightPhase / HEIGHT_SPAN
            val r = ORBIT_RADIUS * taper

            val dx = cos(angle.toDouble()) * r
            val dz = sin(angle.toDouble()) * r
            val dy = heightPhase

            pose.pushPose()
            pose.translate(dx, dy.toDouble(), dz)
            pose.mulPose(Axis.YP.rotationDegrees((time * 8f + i * 31f) % 360f))
            pose.mulPose(Axis.XP.rotationDegrees((time * 5f + i * 47f) % 360f))
            pose.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)

            itemRenderer.renderStatic(
                contents[i],
                ItemDisplayContext.GROUND,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                pose,
                buffers,
                entity.level(),
                entity.id,
            )
            pose.popPose()
        }

        renderHeadGlow(pose, buffers, time, entity.eyeHeight)

        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight)
    }

    /** A yellow billboard glow at the tornado's top-centre — reads as the
     *  archive's "head". Uses [RenderType.eyes] (same render type vanilla uses
     *  for enderman/spider glowing eyes) with the vanilla sun texture as a
     *  soft yellow disc; additive blending + full-bright lightmap make it glow
     *  regardless of ambient light. Billboarded via the dispatcher's camera
     *  orientation so it always faces the player; slight breathing scale keyed
     *  off `time` so it feels alive.
     *
     *  Quad is emitted with BOTH windings — vanilla uses `RenderType.eyes` as
     *  a model overlay (the source model's vertices provide correct facing for
     *  the lit side); for a custom billboard quad the post-`cameraOrientation`
     *  facing is ambiguous, and emitting both windings sidesteps the
     *  default back-face cull entirely. Two quads is trivial cost. */
    private fun renderHeadGlow(pose: PoseStack, buffers: MultiBufferSource, time: Float, eyeHeight: Float) {
        val breathe = 1.0f + 0.08f * sin(time * 0.12f)
        val half = HEAD_HALF * breathe

        pose.pushPose()
        // Anchor the glow at the entity's actual eye height (Mob default = 0.85 ×
        // bbHeight = 2.55 for a 3-block-tall Archive). Reading from the entity
        // means hitbox tweaks won't require touching the renderer.
        pose.translate(0.0, eyeHeight.toDouble(), 0.0)
        pose.mulPose(entityRenderDispatcher.cameraOrientation())

        val consumer = buffers.getBuffer(RenderType.eyes(HEAD_TEX))
        val matrix = pose.last().pose()
        val normal = pose.last().normal()

        drawHeadVertex(consumer, matrix, normal, -half, -half, 0f, 0f)
        drawHeadVertex(consumer, matrix, normal,  half, -half, 1f, 0f)
        drawHeadVertex(consumer, matrix, normal,  half,  half, 1f, 1f)
        drawHeadVertex(consumer, matrix, normal, -half,  half, 0f, 1f)
        drawHeadVertex(consumer, matrix, normal, -half,  half, 0f, 1f)
        drawHeadVertex(consumer, matrix, normal,  half,  half, 1f, 1f)
        drawHeadVertex(consumer, matrix, normal,  half, -half, 1f, 0f)
        drawHeadVertex(consumer, matrix, normal, -half, -half, 0f, 0f)

        pose.popPose()
    }

    /** One NEW_ENTITY vertex — full chain (position, colour, uv0, overlay,
     *  lightmap, normal). Yellow tint baked into the colour channel; lightmap
     *  pinned to FULL_BRIGHT so the glow ignores ambient. */
    private fun drawHeadVertex(
        consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        x: Float, y: Float, u: Float, v: Float,
    ) {
        consumer.vertex(matrix, x, y, 0f)
            .color(255, 240, 100, 255)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(LightTexture.FULL_BRIGHT)
            .normal(normal, 0f, 0f, 1f)
            .endVertex()
    }

    /** No body texture — every visible quad comes from the orbiting items. The
     *  vanilla render pipeline still wants *something* here for the missing-
     *  texture fallback path; pointing at a known atlas entry avoids the
     *  pink-and-black placeholder if anything ever falls through. */
    override fun getTextureLocation(entity: ArchiveEntity): ResourceLocation =
        ResourceLocation("minecraft", "textures/entity/enderdragon/dragon.png")

    private companion object {
        /** Radians per tick the whole ring rotates around the entity's Y axis. */
        const val SPIN_RATE = 0.15f

        /** How fast each item walks vertically through [HEIGHT_SPAN]. */
        const val RISE_RATE = 0.04f

        /** Total vertical band an item travels through before wrapping. */
        const val HEIGHT_SPAN = 2.4f

        /** Horizontal orbit radius. */
        const val ORBIT_RADIUS = 0.6f

        /** Each item rendered at this scale (so a book reads as a flying
         *  fragment, not a placed inventory icon). */
        const val ITEM_SCALE = 0.6f

        /** Half-extent of the head sprite quad. 0.7 = 1.4 block wide before
         *  breathing scale — big enough to read from across a corridor. */
        const val HEAD_HALF = 0.7f

        /** Vanilla sun texture — soft yellow radial disc, already in the
         *  TextureManager since it's used for the overworld sky. Pre-tinted
         *  via vertex colour for the archive's warmer hue. */
        val HEAD_TEX: ResourceLocation = ResourceLocation("textures/environment/sun.png")
    }
}
