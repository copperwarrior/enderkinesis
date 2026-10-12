package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.EyesLayer
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.SselithMothModel
import org.shipwrights.enderkinesis.entity.SselithMothEntity

/**
 * Glowing markings on the moth, from `sselith_moth_e.png`. [EyesLayer] is
 * vanilla's emissive-overlay layer: it re-renders the parent model (so the
 * glow inherits the live pose and perch tilt for free) at a full-bright
 * lightmap.
 *
 * Uses `entityTranslucentEmissive`, NOT `RenderType.eyes`: eyes blends
 * ADDITIVELY, which washes the texture out — dark texels disappear into
 * the background and bright ones blow toward white. The translucent
 * emissive shader ignores the lightmap and applies no diffuse term, so
 * the texture's own colours render at full brightness, alpha-masked.
 */
class SselithMothEmissiveLayer(
    parent: RenderLayerParent<SselithMothEntity, SselithMothModel>,
) : EyesLayer<SselithMothEntity, SselithMothModel>(parent) {

    override fun renderType(): RenderType = EMISSIVE

    private companion object {
        private val TEXTURE: ResourceLocation =
            EnderkinesisMod.id("textures/entity/sselith_moth_e.png")

        /** Cached — the factory builds a new instance per call. */
        private val EMISSIVE: RenderType = RenderType.entityTranslucentEmissive(TEXTURE)
    }
}
