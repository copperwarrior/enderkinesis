package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.EntityModel
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.block.StatueBlock
import org.shipwrights.enderkinesis.block.StatueKind
import org.shipwrights.enderkinesis.client.model.StatueBlackGoatModel
import org.shipwrights.enderkinesis.client.model.StatueCatalogerModel
import org.shipwrights.enderkinesis.client.model.StatueCountingModel
import org.shipwrights.enderkinesis.client.model.StatueSteveModel
import org.shipwrights.enderkinesis.client.model.StatueTentacledBeastModel
import org.shipwrights.enderkinesis.client.model.StatueTentaclesModel
import org.shipwrights.enderkinesis.client.model.StatueYellowTowerModel

/**
 * Draws the full Blockbench statue model as the item icon for every statue
 * BlockItem — inventory, dropped, in-hand, item-frame all use this BEWLR instead
 * of the flat sprite. Transform mirrors [StatueBlockEntityRenderer] so the
 * item-view matches what the player will see when they place the block; the
 * item model's `display` block (in the `builtin/entity` parent) handles the
 * per-context rotation/scale on top.
 *
 * Per-platform wiring:
 *  - **Fabric**: `BuiltinItemRendererRegistry.INSTANCE.register(item) { … StatueBEWLR.renderByItem(…) }` — one call per statue item.
 *  - **Forge**: [org.shipwrights.enderkinesis.forge.client.StatueForgeExtensions] returned from a mixin that checks `instanceof BlockItem && block instanceof StatueBlock`.
 *
 * Models are baked lazily on first paint per kind (the dispatcher's `entityModels`
 * has every registered layer by then) — avoids the eager 7-layer bake on every
 * client startup whether the player has the item or not.
 */
object StatueBEWLR : BlockEntityWithoutLevelRenderer(
    Minecraft.getInstance().blockEntityRenderDispatcher,
    Minecraft.getInstance().entityModels,
) {

    private val models: MutableMap<StatueKind, EntityModel<Entity>> = mutableMapOf()

    private fun modelFor(kind: StatueKind): EntityModel<Entity> = models.getOrPut(kind) {
        val em = Minecraft.getInstance().entityModels
        when (kind) {
            StatueKind.STEVE           -> StatueSteveModel(em.bakeLayer(StatueSteveModel.LAYER_LOCATION))
            StatueKind.CATALOGER       -> StatueCatalogerModel(em.bakeLayer(StatueCatalogerModel.LAYER_LOCATION))
            StatueKind.TENTACLES       -> StatueTentaclesModel(em.bakeLayer(StatueTentaclesModel.LAYER_LOCATION))
            StatueKind.TENTACLED_BEAST -> StatueTentacledBeastModel(em.bakeLayer(StatueTentacledBeastModel.LAYER_LOCATION))
            StatueKind.YELLOW_TOWER    -> StatueYellowTowerModel(em.bakeLayer(StatueYellowTowerModel.LAYER_LOCATION))
            StatueKind.BLACK_GOAT      -> StatueBlackGoatModel(em.bakeLayer(StatueBlackGoatModel.LAYER_LOCATION))
            StatueKind.COUNTING        -> StatueCountingModel(em.bakeLayer(StatueCountingModel.LAYER_LOCATION))
        }
    }

    override fun renderByItem(
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        pose: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        val blockItem = stack.item as? BlockItem ?: return
        val block = blockItem.block as? StatueBlock ?: return
        val model = modelFor(block.kind)

        pose.pushPose()
        // Match the BER's transform — translate above floor, +180° yaw, scale-flip
        // for BB's Y-up convention. The +180 is the same offset the BER applies
        // to FACING; without it the item's non-GUI contexts (hand, ground, head,
        // fixed) end up showing the back of the figure.
        pose.translate(0.5, 1.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(180f))
        pose.scale(-1.0f, -1.0f, 1.0f)

        val consumer = buffers.getBuffer(RenderType.entityTranslucentCull(block.kind.texture))
        model.renderToBuffer(pose, consumer, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f)
        pose.popPose()
    }
}
