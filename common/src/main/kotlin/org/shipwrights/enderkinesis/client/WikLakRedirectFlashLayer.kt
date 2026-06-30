package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Player render layer that overlays the wik-lak skin on a player whose
 *  UUID is currently tracked by [WikLakRedirectFlashTracker]. Drawn on
 *  top of the base player render via the alpha returned by the tracker,
 *  so the player visibly fades from "wik-lak body" to "their own skin"
 *  in the seconds after a [org.shipwrights.enderkinesis.entity.WikLakDeathRedirect]
 *  swap.
 *
 *  Registered onto both `default` and `slim` PlayerRenderers by
 *  `PlayerRendererWikLakFlashLayerMixin`. */
class WikLakRedirectFlashLayer(
    parent: RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>,
) : RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>(parent) {

    override fun render(
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        entity: AbstractClientPlayer,
        limbSwing: Float,
        limbSwingAmount: Float,
        partialTick: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        val alpha = WikLakRedirectFlashTracker.alpha(entity.uuid)
        if (alpha <= 0f) return
        val vc = buffer.getBuffer(RenderType.entityTranslucent(WIKLAK_SKIN))
        // The PlayerModel was already posed by the parent renderer's
        // setupAnim before layers fire, so a single renderToBuffer call
        // with our texture + alpha overlays correctly on top of the base
        // player render. Same model + same positions → no z-fighting,
        // standard alpha blend.
        this.parentModel.renderToBuffer(
            poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY,
            1f, 1f, 1f, alpha,
        )
    }

    companion object {
        private val WIKLAK_SKIN: ResourceLocation =
            EnderkinesisMod.id("textures/entity/wik_lak_host.png")
    }
}
