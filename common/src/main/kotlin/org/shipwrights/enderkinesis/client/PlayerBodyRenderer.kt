package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Mob
import org.shipwrights.enderkinesis.ritual.SelvesBodies

/** A vanilla mob that is a player body from the Ritual of Selves, drawn as its owner. */
class PlayerBodyRenderer(ctx: EntityRendererProvider.Context, slim: Boolean) :
    HumanoidMobRenderer<Mob, PlayerModel<Mob>>(
        ctx, PlayerModel(ctx.bakeLayer(if (slim) ModelLayers.PLAYER_SLIM else ModelLayers.PLAYER), slim), SHADOW_RADIUS,
    ) {

    init {
        addLayer(
            HumanoidArmorLayer(
                this,
                HumanoidModel(ctx.bakeLayer(if (slim) ModelLayers.PLAYER_SLIM_INNER_ARMOR else ModelLayers.PLAYER_INNER_ARMOR)),
                HumanoidModel(ctx.bakeLayer(if (slim) ModelLayers.PLAYER_SLIM_OUTER_ARMOR else ModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.modelManager,
            ),
        )
    }

    override fun getTextureLocation(entity: Mob): ResourceLocation {
        val owner = SelvesBodies.ownerOf(entity) ?: return DefaultPlayerSkin.getDefaultSkin()
        return Minecraft.getInstance().connection?.getPlayerInfo(owner)?.skinLocation ?: DefaultPlayerSkin.getDefaultSkin(owner)
    }

    companion object {
        private const val SHADOW_RADIUS = 0.5f
    }
}
