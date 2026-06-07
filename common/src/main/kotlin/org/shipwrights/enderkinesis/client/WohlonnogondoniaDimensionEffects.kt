package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.world.phys.Vec3

/**
 * Custom [DimensionSpecialEffects] for
 * [org.shipwrights.enderkinesis.dimension.Wohlonnogondonia].
 *
 * The point of this class is the constructor configuration:
 *
 *  - `cloudLevel = NaN` → vanilla skips its cloud rendering branch (no
 *    cloud plane against the dim swamp sunset).
 *  - `skyType = NORMAL` → vanilla's `LevelRenderer.renderSky` runs the
 *    full sky pass: gradient + sun + moon + stars. We keep this so the
 *    biome's cool-palette `fog_color` drives the sky gradient and the
 *    `fixed_time` value sets the sun's position.
 *  - `getSunriseColor` returns **null** → suppresses the hardcoded warm
 *    orange/yellow horizon ring vanilla draws around sunrise/sunset
 *    (`Level.effects().getSunriseColor(...)` is null-checked in
 *    `LevelRenderer.renderSky`). Without this override, the dimension's
 *    cool-palette setup is overlaid with a saturated yellow ring at the
 *    sun's elevation — what the user reported as "the sunset is still
 *    yellow". Returning null kills that ring; the dimension's slate-blue
 *    fog now dominates the horizon.
 *
 * The sun and moon textures themselves are swapped to `wogor_eye.png`
 * by [org.shipwrights.enderkinesis.mixin.LevelRendererWohlonSunMoonMixin]
 * — `DimensionSpecialEffects` has no hook for the texture binding so the
 * mixin redirects the `RenderSystem.setShaderTexture` calls inside
 * `renderSky` when the live dimension is Wohlonnogondonia.
 *
 * Registration: platform-specific —
 *  - Fabric: `DimensionRenderingRegistry.registerDimensionEffects(...)`.
 *  - Forge:  `RegisterDimensionSpecialEffectsEvent.register(...)` (mod-bus event).
 *
 * Effects ID: matches the `effects` field in
 * `data/enderkinesis/dimension_type/wohlonnogondonia.json`.
 */
class WohlonnogondoniaDimensionEffects : DimensionSpecialEffects(
    /* cloudLevel = */ Float.NaN,
    /* hasGround = */ true,
    /* skyType   = */ SkyType.NORMAL,
    /* forceBrightLightmap = */ false,
    /* constantAmbientLight = */ false,
) {
    override fun getBrightnessDependentFogColor(fogColor: Vec3, brightness: Float): Vec3 = fogColor

    override fun isFoggyAt(x: Int, y: Int): Boolean = false

    /**
     * Suppress vanilla's warm sunrise/sunset horizon ring. Returning null is
     * checked by [net.minecraft.client.renderer.LevelRenderer.renderSky]
     * (`if (afloat != null) { … render gradient … }`), so the colour pass
     * is skipped entirely and only the biome-driven sky gradient remains.
     */
    override fun getSunriseColor(timeOfDay: Float, partialTick: Float): FloatArray? = null
}
