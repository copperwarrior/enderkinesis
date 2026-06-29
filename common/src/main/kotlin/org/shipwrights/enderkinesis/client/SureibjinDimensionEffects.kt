package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.world.phys.Vec3

/**
 * Custom [DimensionSpecialEffects] for
 * [org.shipwrights.enderkinesis.dimension.Sureibjin] — the dream-coast.
 *
 *  - `cloudLevel = NaN` → no cloud plane.
 *  - `skyType = NONE` → vanilla's `LevelRenderer.renderSky` enters the
 *    "render nothing" branch (no sun/moon/horizon/cloud), and our
 *    `SureibjinSky` runs instead via `LevelRendererSureibjinSkyMixin`.
 *    NONE is the shaderpack-safe variant for replaced skies — see
 *    [SselithDimensionEffects] for the same pattern.
 *  - `getSunriseColor` returns null → defensive, in case the mixin doesn't
 *    apply and vanilla falls back to NORMAL behaviour.
 *  - `isFoggyAt` returns true → desaturating distance fog wraps everything.
 *
 *  Registration: platform-specific —
 *   - Fabric: `DimensionRenderingRegistry.registerDimensionEffects(...)`.
 *   - Forge:  `RegisterDimensionSpecialEffectsEvent.register(...)` (mod-bus event).
 *
 *  Effects ID: matches the `effects` field in
 *  `data/enderkinesis/dimension_type/sureibjin.json`.
 */
class SureibjinDimensionEffects : DimensionSpecialEffects(
    /* cloudLevel = */ Float.NaN,
    /* hasGround = */ true,
    /* skyType   = */ SkyType.NONE,
    /* forceBrightLightmap = */ false,
    /* constantAmbientLight = */ false,
) {
    override fun getBrightnessDependentFogColor(fogColor: Vec3, brightness: Float): Vec3 = fogColor

    override fun isFoggyAt(x: Int, y: Int): Boolean = false

    override fun getSunriseColor(timeOfDay: Float, partialTick: Float): FloatArray? = null
}
