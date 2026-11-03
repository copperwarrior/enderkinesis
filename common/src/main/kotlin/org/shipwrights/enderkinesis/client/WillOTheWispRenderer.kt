package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.inventory.InventoryMenu
import org.shipwrights.enderkinesis.entity.WillOTheWispEntity

class WillOTheWispRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<WillOTheWispEntity>(ctx) {
    override fun render(
        entity: WillOTheWispEntity,
        yaw: Float,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
    ) {
        if (SpellLayers.invisible(entity)) return
        WillfaerFlameVolume.queue(
            Mth.lerp(partialTick.toDouble(), entity.xo, entity.x),
            Mth.lerp(partialTick.toDouble(), entity.yo, entity.y) + entity.bbHeight * 0.5,
            Mth.lerp(partialTick.toDouble(), entity.zo, entity.z),
            REACH,
            (entity.id % SEED_SPREAD).toFloat(),
            entity.fade(partialTick),
            entity.burst(partialTick),
        )
        // Draws the name tag of a wisp left by a player death.
        super.render(entity, yaw, partialTick, pose, buffers, light)
    }

    // No model draws, but the contract needs a texture.
    override fun getTextureLocation(entity: WillOTheWispEntity): ResourceLocation = InventoryMenu.BLOCK_ATLAS

    private companion object {
        const val SEED_SPREAD = 97

        // The motes fly out past the hitbox.
        const val REACH = 0.9f
    }
}
