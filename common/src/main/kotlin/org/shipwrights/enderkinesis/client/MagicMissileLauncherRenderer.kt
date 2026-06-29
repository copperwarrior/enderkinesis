package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import org.shipwrights.enderkinesis.block.MagicMissileLauncherBlock
import org.shipwrights.enderkinesis.blockentity.MagicMissileLauncherBlockEntity

/**
 * Per-slot missile overlays drawn as runtime quads instead of blockstate variants —
 * 2¹⁶ × 4 × 2 ≈ 500k entries would explode MC's startup blockstate enumeration, so
 * occupancy is read from the BE inventory each frame and one UV-cropped quad per
 * occupied slot is emitted over the base model's `_front_empty` north face.
 *
 * Quads sit at local `z = -0.0005` — far enough to win the depth test, close enough not
 * to trigger AO / chunk-rebuild surprises. North-face X-flip means model `x` maps to
 * texture `u = 16 − x`; the artist's missile-sprite source is laid out to match.
 */
class MagicMissileLauncherRenderer(@Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<MagicMissileLauncherBlockEntity> {

    override fun render(
        be: MagicMissileLauncherBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val state = be.blockState
        if (state.block !is MagicMissileLauncherBlock) return

        // Quick out — if no slots are occupied, nothing for the BER to draw.
        var anyOccupied = false
        for (i in 0 until MagicMissileLauncherBlockEntity.SLOT_COUNT) {
            if (!be.getItem(i).isEmpty) { anyOccupied = true; break }
        }
        if (!anyOccupied) return

        val sprite = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(SPRITE_ID)

        val facing = state.getValue(MagicMissileLauncherBlock.FACING)

        pose.pushPose()
        // Rotate the block-local frame so the launcher's front face always sits at
        // z = 0 with x running left→right. Translate to block centre first, rotate,
        // then translate back so the rotation is about the block centre.
        //
        // Horizontal facings (N/S/E/W): rotate around Y by the **negated** yaw. MC's
        // blockstate `"y": 90` is CW-from-above which in JOML's right-handed
        // convention is a *negative* rotation around +Y.
        //
        // Vertical facings (UP/DOWN): rotate around X by ±90° so the model's default
        // north face (z=0) ends up on world top or bottom. JOML's right-handed +X
        // rotation by +90° takes (x, y, z) → (x, -z, y) — the model's +Z (back face)
        // moves to world +Y for UP-facing. -90° gives DOWN-facing.
        pose.translate(0.5, 0.5, 0.5)
        when (facing) {
            Direction.NORTH -> {}
            Direction.EAST  -> pose.mulPose(Axis.YP.rotationDegrees(-90f))
            Direction.SOUTH -> pose.mulPose(Axis.YP.rotationDegrees(-180f))
            Direction.WEST  -> pose.mulPose(Axis.YP.rotationDegrees(-270f))
            Direction.UP    -> pose.mulPose(Axis.XP.rotationDegrees(90f))
            Direction.DOWN  -> pose.mulPose(Axis.XP.rotationDegrees(-90f))
        }
        pose.translate(-0.5, -0.5, -0.5)

        val consumer = buffers.getBuffer(RenderType.cutout())
        val matrix = pose.last().pose()
        val normal = pose.last().normal()

        for (slot in 0 until MagicMissileLauncherBlockEntity.SLOT_COUNT) {
            if (be.getItem(slot).isEmpty) continue
            emitSlotQuad(consumer, matrix, normal, slot, sprite, packedLight, packedOverlay)
        }

        pose.popPose()
    }

    /** Emit a flat camera-front-facing quad at slot N's position, UV-cropped to the
     *  slot's 4×4 region of the `_front_full` sprite. CCW winding viewed from -Z so
     *  the quad's normal points outward through the block's north face. Universal
     *  `cx = 14 − col·4` across all facings — paired with `pickSlot`'s symmetric
     *  u-axis (u-high = viewer's left for every facing), this places slot 0 at
     *  viewer's top-left for any facing and ordering reads left→right consistently. */
    private fun emitSlotQuad(
        consumer: VertexConsumer,
        matrix: org.joml.Matrix4f,
        normal: org.joml.Matrix3f,
        slot: Int,
        sprite: TextureAtlasSprite,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val col = slot % 4
        val row = slot / 4
        val cx = 14 - col * 4
        val cy = 14 - row * 4

        val fx = (cx - 2) / 16.0f
        val fy = (cy - 2) / 16.0f
        val tx = (cx + 2) / 16.0f
        val ty = (cy + 2) / 16.0f
        val z = -OVERLAY_Z_OFFSET

        // North-face X-flip: model x range (cx-2, cx+2) → texture u range
        // (16 − (cx+2), 16 − (cx-2)) = (14 − cx, 18 − cx). Y is not flipped.
        val u0 = sprite.getU((14 - cx).toDouble())
        val u1 = sprite.getU((18 - cx).toDouble())
        val v0 = sprite.getV((14 - cy).toDouble())
        val v1 = sprite.getV((18 - cy).toDouble())

        // 4 vertices, CCW viewed from -Z so the implicit normal points outward through
        // the front face and the quad survives `RenderType.cutout()`'s back-face cull.
        // Top-left → top-right → bottom-right → bottom-left, with UV matching the
        // mirror so the texture appears un-mirrored from the viewer's POV.
        consumer.vertex(matrix, fx, ty, z).color(0xFF, 0xFF, 0xFF, 0xFF)
            .uv(u1, v0).overlayCoords(packedOverlay).uv2(packedLight)
            .normal(normal, 0f, 0f, -1f).endVertex()
        consumer.vertex(matrix, tx, ty, z).color(0xFF, 0xFF, 0xFF, 0xFF)
            .uv(u0, v0).overlayCoords(packedOverlay).uv2(packedLight)
            .normal(normal, 0f, 0f, -1f).endVertex()
        consumer.vertex(matrix, tx, fy, z).color(0xFF, 0xFF, 0xFF, 0xFF)
            .uv(u0, v1).overlayCoords(packedOverlay).uv2(packedLight)
            .normal(normal, 0f, 0f, -1f).endVertex()
        consumer.vertex(matrix, fx, fy, z).color(0xFF, 0xFF, 0xFF, 0xFF)
            .uv(u1, v1).overlayCoords(packedOverlay).uv2(packedLight)
            .normal(normal, 0f, 0f, -1f).endVertex()
    }

    private companion object {
        /** Resource location of the `_front_full` sprite on the block atlas — the BER
         *  crops a 4×4 region of this for each occupied slot. */
        private val SPRITE_ID =
            ResourceLocation("enderkinesis", "block/magic_missile_launcher_front_full")

        /** Z-offset of overlay quads from the base front face. 0.0005 blocks ≈ 1/2000
         *  block; far enough to win depth test, close enough that AO and chunk culling
         *  treat the quad as part of this block. */
        private const val OVERLAY_Z_OFFSET = 0.0005f
    }
}
