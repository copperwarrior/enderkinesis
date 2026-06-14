package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import org.shipwrights.enderkinesis.entity.Cataloger

/**
 * Top-level world-space hook for the Cataloger tome-summon flourish.
 * Called once per frame from the level renderer's post-entity event
 * (Fabric: `WorldRenderEvents.AFTER_ENTITIES`; Forge: `RenderLevelStageEvent`
 * at `Stage.AFTER_ENTITIES`). Wired up in each platform module's
 * client init.
 *
 * **Why not as a layer of [CatalogerRenderer]?**
 * The book floats out into world space far beyond the cataloger's
 * own bounding box. A render layer only fires when the cataloger
 * itself passes frustum culling, so a player looking at the source
 * bookshelf with the cataloger off-screen would see no book at all.
 * Hooking the level renderer instead means the book renders whenever
 * the level is being drawn — frustum culling lives at the
 * world-renderer level (the book's quads end up in the bufferSource
 * along with everything else, and the GPU discards off-screen ones).
 */
object CatalogerTomeWorldRenderer {

    fun renderAll(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        partialTicks: Float,
    ) {
        val level = Minecraft.getInstance().level ?: return
        // `entitiesForRendering` is the same iterator the level renderer
        // walks to draw entities — covers all loaded entities in the
        // client's view distance, in render-friendly order.
        for (entity in level.entitiesForRendering()) {
            when {
                entity is Cataloger && entity.tomeSummonBookshelf != null ->
                    CatalogerTomeRender.renderForCataloger(
                        entity, partialTicks, poseStack, bufferSource,
                        cameraX, cameraY, cameraZ,
                        OverlayTexture.NO_OVERLAY,
                    )
                entity is net.minecraft.world.entity.player.Player &&
                    PlayerTomeSummonClient.get(entity) != null ->
                    CatalogerTomeRender.renderForPlayer(
                        entity, partialTicks, poseStack, bufferSource,
                        cameraX, cameraY, cameraZ,
                        OverlayTexture.NO_OVERLAY,
                    )
            }
        }
    }
}
