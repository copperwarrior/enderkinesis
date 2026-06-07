package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.MobRenderer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.CatalogerModel
import org.shipwrights.enderkinesis.entity.Cataloger

/**
 * Renderer for the [Cataloger]. Uses the custom [CatalogerModel] (a HierarchicalModel
 * with Blockbench-authored idle + walking animations cross-faded via easing). Plain
 * [MobRenderer] base — no armor / held-item layers, since the cataloger is invulnerable,
 * never armoured, and never holds anything.
 *
 * Shadow is disabled: `shadowRadius = 0` makes `EntityRenderer.render` skip its
 * shadow-draw block entirely. Sselith's diffuse yellow lighting doesn't read
 * a hard projected blob well anyway.
 *
 * **Translucent fade on cataloger-vs-cataloger overlap.** When two catalogers'
 * bounding boxes intersect, [Cataloger.intersectionAlpha] drops below 1 and the
 * renderer switches to [RenderType.entityTranslucent] — the default
 * `model.renderType` returns a cutout-no-cull layer that discards low-alpha
 * pixels but doesn't blend, so the model's per-vertex alpha multiply
 * (`CatalogerModel.renderToBuffer`) would have no visible effect with the
 * default render type. With the translucent layer active, the alpha multiply
 * smoothly fades the entire model.
 *
 * Texture: `assets/enderkinesis/textures/entity/cataloger/cataloger.png`.
 */
class CatalogerRenderer(
    ctx: EntityRendererProvider.Context,
) : MobRenderer<Cataloger, CatalogerModel>(
    ctx,
    CatalogerModel(ctx.bakeLayer(CatalogerModel.LAYER_LOCATION)),
    SHADOW_RADIUS,
) {
    override fun getTextureLocation(entity: Cataloger): ResourceLocation = SKIN

    override fun getRenderType(
        entity: Cataloger,
        bodyVisible: Boolean,
        translucent: Boolean,
        glowing: Boolean,
    ): RenderType? {
        // When overlapping another cataloger we need a translucent layer so the
        // model's alpha multiply actually blends. Otherwise defer to vanilla,
        // which picks the cutout layer (cheaper, no sort-order quirks).
        return if (entity.intersectionAlpha < 1f) {
            RenderType.entityTranslucent(SKIN)
        } else {
            super.getRenderType(entity, bodyVisible, translucent, glowing)
        }
    }

    companion object {
        private const val SHADOW_RADIUS = 0f
        private val SKIN: ResourceLocation =
            EnderkinesisMod.id("textures/entity/cataloger/cataloger.png")
    }
}
