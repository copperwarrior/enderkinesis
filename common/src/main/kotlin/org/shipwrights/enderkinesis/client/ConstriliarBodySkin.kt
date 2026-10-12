package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.ConstriliarModel
import org.shipwrights.enderkinesis.entity.ConstriliarEntity

/**
 * Shared body rendering for the Constriliar's two render paths (the entity renderer
 * during the spawn intro, the platform BER afterwards): the block-texture plate box and
 * the spawn-animation phase logic. Both paths call within an identical model-space
 * transform, so the intro→ship handoff is seamless.
 */
object ConstriliarBodySkin {

    val TEXTURE = ResourceLocation(EnderkinesisMod.MOD_ID, "textures/entity/constriliar.png")
    val BLINK = ResourceLocation(EnderkinesisMod.MOD_ID, "textures/entity/constriliar_blink.png")

    private const val INSET = 0.004f
    private const val SPRITE_CACHE_MAX = 64

    /** Fully-squashed rim strips per quarter (v0/v1 px): top edge, contiguous 2px
     *  middle, bottom edge. Squash interpolates from each quarter's contiguous
     *  texture quarter toward these. */
    private val FINAL_V0 = floatArrayOf(0f, 7f, 8f, 15f)
    private val FINAL_V1 = floatArrayOf(1f, 8f, 9f, 16f)


    /** Per-face icon: the sprite plus its tint index (-1 = untinted). Greyscale sprites
     *  like grass tops and leaves only get their color from BlockColors at render time. */
    private class FaceIcon(val sprite: TextureAtlasSprite, val tintIndex: Int)

    private val spriteCache = java.util.IdentityHashMap<BlockState, Array<FaceIcon>>()

    /** A resource reload bakes new models, and the sprites of the old ones are stale. */
    private var spriteCacheModels: Any? = null

    /** Model-space top (y is pre-flip down-positive) of the skin box: 0.25 = the full
     *  original block during the intro squash, 1.0 = the settled plate. */
    fun squashTop(animT: Float): Float =
        if (animT in 0f..ConstriliarEntity.ANIM_SQUASH_END.toFloat()) {
            0.25f + 0.75f * smoothstep(animT / ConstriliarEntity.ANIM_SQUASH_END.toFloat())
        } else {
            1.0f
        }

    private fun smoothstep(p: Float): Float {
        val c = p.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Draw body skin + (phase-permitting) the model overlay. Expects the poseStack to
     *  already be in body model space. */
    @Suppress("LongParameterList")
    fun renderBody(
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        model: ConstriliarModel,
        entity: ConstriliarEntity,
        animT: Float,
        partialTick: Float,
        light: Int,
    ) {
        var skinTexture = if (entity.isBlinking()) BLINK else TEXTURE
        var skinAlpha = 1f
        var drawModel = true
        if (animT >= 0f) {
            if (animT < ConstriliarEntity.ANIM_EYES_START) {
                drawModel = false
            } else if (animT < ConstriliarEntity.ANIM_RISE_END) {
                skinTexture = BLINK
                skinAlpha = smoothstep(
                    ((animT - ConstriliarEntity.ANIM_EYES_START) /
                        (ConstriliarEntity.ANIM_EYES_END - ConstriliarEntity.ANIM_EYES_START)).toFloat(),
                )
            } else {
                skinTexture = TEXTURE
            }
            model.applySpawnPop(animT)
        } else {
            model.resetSpawnPop()
        }
        drawBlockSkin(poseStack, buffer, entity, entity.bodyBlockState(), light, squashTop(animT))
        if (drawModel) {
            model.renderToBuffer(
                poseStack,
                buffer.getBuffer(RenderType.entityTranslucent(skinTexture)),
                light,
                OverlayTexture.NO_OVERLAY,
                1f, 1f, 1f, skinAlpha,
            )
        }
    }

    /** The plate box (model-space x/z −0.5..0.5, y [squashTop]..1.25), inset a hair so
     *  the translucent skin above never z-fights. The side band shows the TOP
     *  (height×16)px of each side sprite, top edge pinned to the plate's top. */
    private fun drawBlockSkin(
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        entity: ConstriliarEntity,
        state: BlockState,
        light: Int,
        squashTop: Float,
    ) {
        val sprites = spritesFor(state)
        val vc = buffer.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS))
        val pose = poseStack.last()
        val n = 0.5f - INSET
        val yT = squashTop + INSET
        val yB = 1.25f - INSET
        val top = sprites[Direction.UP.ordinal]
        val bottom = sprites[Direction.DOWN.ordinal]
        // World-top (model y = yT, normal model -Y) and world-bottom faces, full sprite.
        quad(pose, vc, light, top, faceTint(entity, state, top), 0.0, 16.0, 0.0, 16.0,
            -n, yT, -n, n, yT, -n, n, yT, n, -n, yT, n, 0f, -1f, 0f)
        quad(pose, vc, light, bottom, faceTint(entity, state, bottom), 0.0, 16.0, 0.0, 16.0,
            -n, yB, -n, n, yB, -n, n, yB, n, -n, yB, n, 0f, 1f, 0f)
        for (dir in Direction.Plane.HORIZONTAL) {
            val s = sprites[dir.ordinal]
            val (x0, z0, x1, z1) = when (dir) {
                Direction.NORTH -> arrayOf(-n, -n, n, -n)
                Direction.SOUTH -> arrayOf(n, n, -n, n)
                Direction.WEST -> arrayOf(-n, n, -n, -n)
                else -> arrayOf(n, -n, n, n)                        // EAST
            }
            sideQuad(pose, vc, light, s, faceTint(entity, state, s),
                x0, z0, x1, z1, yT, yB, dir.stepX.toFloat(), 0f, dir.stepZ.toFloat())
        }
    }

    /** Resolve a face's biome tint at the entity's CURRENT position, so grass/foliage
     *  bodies wear the local biome color and re-tint as they roam. */
    private fun faceTint(entity: ConstriliarEntity, state: BlockState, icon: FaceIcon): Int {
        if (icon.tintIndex < 0) return -1
        return Minecraft.getInstance().blockColors
            .getColor(state, entity.level(), entity.blockPosition(), icon.tintIndex)
    }

    @Suppress("LongParameterList")
    private fun quad(
        pose: PoseStack.Pose, vc: VertexConsumer, light: Int, icon: FaceIcon, tint: Int,
        u0px: Double, u1px: Double, v0px: Double, v1px: Double,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
    ) {
        val sprite = icon.sprite
        val u0 = sprite.getU(u0px); val u1 = sprite.getU(u1px)
        val v0 = sprite.getV(v0px); val v1 = sprite.getV(v1px)
        vert(pose, vc, light, tint, x0, y0, z0, u0, v0, nx, ny, nz)
        vert(pose, vc, light, tint, x1, y1, z1, u1, v0, nx, ny, nz)
        vert(pose, vc, light, tint, x2, y2, z2, u1, v1, nx, ny, nz)
        vert(pose, vc, light, tint, x3, y3, z3, u0, v1, nx, ny, nz)
    }

    /** The side band is four equal quarters whose texture sampling SQUASHES with the
     *  geometry: unsquashed (spawn) each quarter shows its contiguous texture quarter
     *  (the whole side, undistorted); fully squashed (4px rim) they've compressed to
     *  the final strips — top edge, 2px contiguous middle, bottom edge — so a grass
     *  block keeps its crown and dirt base with no stretching at any point. */
    @Suppress("LongParameterList")
    private fun sideQuad(
        pose: PoseStack.Pose, vc: VertexConsumer, light: Int, icon: FaceIcon, tint: Int,
        x0: Float, z0: Float, x1: Float, z1: Float, yT: Float, yB: Float,
        nx: Float, ny: Float, nz: Float,
    ) {
        val sprite = icon.sprite
        val u0 = sprite.getU(0.0); val u1 = sprite.getU(16.0)
        val heightPx = (yB - yT) * 16f
        // 0 = full block (16px side), 1 = fully squashed rim (4px).
        val p = ((16f - heightPx) / 12f).coerceIn(0f, 1f)
        val slice = (yB - yT) / 4f
        for (i in 0..3) {
            val vTop = sprite.getV(Mth.lerp(p, i * 4f, FINAL_V0[i]).toDouble())
            val vBot = sprite.getV(Mth.lerp(p, i * 4f + 4f, FINAL_V1[i]).toDouble())
            val sT = yT + slice * i
            val sB = yT + slice * (i + 1)
            vert(pose, vc, light, tint, x0, sT, z0, u0, vTop, nx, ny, nz)
            vert(pose, vc, light, tint, x1, sT, z1, u1, vTop, nx, ny, nz)
            vert(pose, vc, light, tint, x1, sB, z1, u1, vBot, nx, ny, nz)
            vert(pose, vc, light, tint, x0, sB, z0, u0, vBot, nx, ny, nz)
        }
    }

    @Suppress("LongParameterList")
    private fun vert(
        pose: PoseStack.Pose, vc: VertexConsumer, light: Int, tint: Int,
        x: Float, y: Float, z: Float, u: Float, v: Float, nx: Float, ny: Float, nz: Float,
    ) {
        val r = if (tint >= 0) (tint shr 16 and 0xFF) else 255
        val g = if (tint >= 0) (tint shr 8 and 0xFF) else 255
        val b = if (tint >= 0) (tint and 0xFF) else 255
        vc.vertex(pose.pose(), x, y, z)
            .color(r, g, b, 255)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(pose.normal(), nx, ny, nz)
            .endVertex()
    }

    private fun spritesFor(state: BlockState): Array<FaceIcon> {
        val mc = Minecraft.getInstance()
        val models = mc.modelManager.missingModel
        if (models !== spriteCacheModels || spriteCache.size > SPRITE_CACHE_MAX) {
            spriteCache.clear()
            spriteCacheModels = models
        }
        spriteCache[state]?.let { return it }
        val blockModel = mc.blockRenderer.getBlockModel(state)
        val fallback = mc.blockRenderer.blockModelShaper.getParticleIcon(state)
        val random = RandomSource.create(42L)
        val out = Array(6) { i ->
            random.setSeed(42L)
            val bakedQuad = blockModel.getQuads(state, Direction.values()[i], random).firstOrNull()
            if (bakedQuad != null) {
                FaceIcon(bakedQuad.sprite, if (bakedQuad.isTinted) bakedQuad.tintIndex else -1)
            } else {
                FaceIcon(fallback, -1)
            }
        }
        spriteCache[state] = out
        return out
    }
}
