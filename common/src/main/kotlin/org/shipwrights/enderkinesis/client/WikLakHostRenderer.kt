package org.shipwrights.enderkinesis.client

import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.WikLakHostEntity

/** Renderer for [WikLakHostEntity]. Uses [WikLakHostModel] over vanilla's
 *  wide-armed [ModelLayers.PLAYER] mesh — same skin layout as a player so
 *  any standard 64×64 skin slots in at `assets/enderkinesis/textures/entity/wik_lak_host.png`.
 *  The model adds the both-arms swing + forward body-lean on every attack. */
class WikLakHostRenderer(
    ctx: EntityRendererProvider.Context,
) : MobRenderer<WikLakHostEntity, WikLakHostModel>(
    ctx,
    WikLakHostModel(ctx.bakeLayer(ModelLayers.PLAYER), /* slim = */ false),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: WikLakHostEntity): ResourceLocation = SKIN

    companion object {
        private const val SHADOW_RADIUS: Float = 0.5f
        private val SKIN: ResourceLocation =
            EnderkinesisMod.id("textures/entity/wik_lak_host.png")
    }
}
