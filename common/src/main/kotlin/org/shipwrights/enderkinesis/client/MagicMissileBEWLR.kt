package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.model.ShulkerBulletModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.projectile.ShulkerBullet
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * BEWLR for the Magic Missile item. Draws the actual vanilla [ShulkerBulletModel] baked
 * from [ModelLayers.SHULKER_BULLET] — the same model the entity renderer uses in flight —
 * rather than a flat sprite.
 *
 * Per-platform wiring:
 *  - **Fabric**: `BuiltinItemRendererRegistry.INSTANCE.register(item) { … BEWLR.renderByItem(…) }`
 *  - **Forge**: a tiny `IClientItemExtensions` subclass whose `getCustomRenderer()` returns
 *    this object.
 *
 * The model takes a `<ShulkerBullet>` type parameter in vanilla; we never call `setupAnim`
 * (`ShulkerBulletModel` has an empty animation chain anyway) so the param doesn't matter —
 * `renderToBuffer` draws every part statically.
 */
object MagicMissileBEWLR : BlockEntityWithoutLevelRenderer(
    Minecraft.getInstance().blockEntityRenderDispatcher,
    Minecraft.getInstance().entityModels,
) {

    private val SPARK_TEX = ResourceLocation("textures/entity/shulker/spark.png")

    private val model: ShulkerBulletModel<ShulkerBullet> by lazy {
        ShulkerBulletModel(
            Minecraft.getInstance().entityModels.bakeLayer(ModelLayers.SHULKER_BULLET)
        )
    }

    override fun renderByItem(
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        pose: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        pose.pushPose()
        // Vanilla ItemRenderer leaves us at (-0.5, -0.5, -0.5) before dispatching to BEWLR
        // (so the display transform's translation is consistent with vanilla block items).
        // Move to the model centre.
        pose.translate(0.5, 0.5, 0.5)
        pose.scale(SCALE, SCALE, SCALE)
        // GUI / inventory / ground / fixed slots: slow Y spin so the silhouette reads.
        // In-hand contexts get a natural rotation from the player's animation; don't add
        // anything there.
        if (displayContext == ItemDisplayContext.GUI
            || displayContext == ItemDisplayContext.GROUND
            || displayContext == ItemDisplayContext.FIXED
        ) {
            val time = System.currentTimeMillis() % 36000L
            pose.mulPose(Axis.YP.rotationDegrees(time / 100f))
            pose.mulPose(Axis.XP.rotationDegrees(20f))
        }
        val vc = buffers.getBuffer(model.renderType(SPARK_TEX))
        model.renderToBuffer(pose, vc, light, overlay, 1f, 1f, 1f, 1f)
        pose.popPose()
    }

    /** Render scale of the shulker-bullet model. Vanilla `ShulkerBulletRenderer` uses
     *  0.5 for the in-flight entity; we render the *item* at twice that so the missile
     *  reads boldly in inventory slots and the launcher's GUI tooltip — the flying
     *  entity stays at vanilla scale (handled separately by [MagicMissileRenderer]). */
    private const val SCALE = 1.0f
}
