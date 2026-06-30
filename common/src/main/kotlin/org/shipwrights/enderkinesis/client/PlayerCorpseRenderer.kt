package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.entity.PlayerCorpseEntity

/** Renderer for [PlayerCorpseEntity]. Base render uses the player's actual
 *  skin (via tab-list PlayerInfo); a layer on top draws the wik-lak skin
 *  with `alpha = deathTime/20`, so the corpse visibly transitions from
 *  the player's appearance to the wik-lak's body over the death animation. */
class PlayerCorpseRenderer(
    ctx: EntityRendererProvider.Context,
) : MobRenderer<PlayerCorpseEntity, PlayerModel<PlayerCorpseEntity>>(
    ctx,
    PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER), /* slim = */ false),
    SHADOW_RADIUS,
) {

    init {
        addLayer(WiklakFadeInLayer(this))
    }

    override fun getTextureLocation(entity: PlayerCorpseEntity): ResourceLocation {
        val uuid = entity.playerUuid ?: return DEFAULT_SKIN
        val connection = Minecraft.getInstance().connection ?: return DEFAULT_SKIN
        val info = connection.getPlayerInfo(uuid) ?: return DEFAULT_SKIN
        return info.skinLocation
    }

    /** Overlays the wik-lak skin on top of the base player skin with alpha
     *  ramping from 0 (start of death) to 1 (death animation complete).
     *  At full alpha the wik-lak quads opaquely cover the player quads —
     *  same model, same positions, depth test passes EQUAL — so the
     *  corpse ends up reading as a wik-lak by the time it disappears. */
    private class WiklakFadeInLayer(
        parent: PlayerCorpseRenderer,
    ) : RenderLayer<PlayerCorpseEntity, PlayerModel<PlayerCorpseEntity>>(parent) {

        override fun render(
            poseStack: PoseStack,
            buffer: MultiBufferSource,
            packedLight: Int,
            entity: PlayerCorpseEntity,
            limbSwing: Float,
            limbSwingAmount: Float,
            partialTick: Float,
            ageInTicks: Float,
            netHeadYaw: Float,
            headPitch: Float,
        ) {
            val total = DEATH_TICKS
            val elapsed = (entity.deathTime.toFloat() + partialTick).coerceIn(0f, total)
            val alpha = elapsed / total
            if (alpha <= 0f) return
            val vc = buffer.getBuffer(RenderType.entityTranslucent(WIKLAK_SKIN))
            this.parentModel.renderToBuffer(
                poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY,
                1f, 1f, 1f, alpha,
            )
        }

        companion object {
            /** Match Mob.tickDeath's removal threshold so the fade
             *  completes exactly at corpse-despawn. */
            private const val DEATH_TICKS: Float = 20f
        }
    }

    companion object {
        private const val SHADOW_RADIUS: Float = 0.5f

        /** Fallback skin if the corpse's player has logged out by the
         *  time the corpse renders — wide-arm "Steve" so the silhouette
         *  is still player-shaped. */
        private val DEFAULT_SKIN: ResourceLocation =
            ResourceLocation("textures/entity/player/wide/steve.png")

        private val WIKLAK_SKIN: ResourceLocation =
            EnderkinesisMod.id("textures/entity/wik_lak_host.png")
    }
}
