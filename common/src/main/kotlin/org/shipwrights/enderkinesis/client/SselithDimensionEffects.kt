package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.world.phys.Vec3

/**
 * Custom [DimensionSpecialEffects] for [org.shipwrights.enderkinesis.dimension.SselithRepertory].
 *
 * The single point of this class is the constructor arguments — specifically:
 *
 *  - `cloudLevel = NaN` → vanilla skips its cloud rendering branch entirely.
 *  - `skyType = NONE`   → vanilla's `LevelRenderer.renderSky` enters the "render nothing"
 *                         branch, **never invoking the sun/moon/horizon/cloud rendering**.
 *                         Critically, that's also the branch shaderpacks rely on: without
 *                         vanilla making those rendering calls, the shaderpack's
 *                         `gbuffers_skybasic` program has no sun/cloud geometry to texture.
 *                         Result: shaderpacks like Complementary stop overlaying an overhead
 *                         sun and a cloud plane on top of our custom Sselith sky.
 *
 * This is the pattern used by `wonderland.jar`'s `Abyss`/`TheSlip` dimensions, by Twilight
 * Forest's `TwilightForestRenderInfo`, and (with `SkyType.NORMAL` + Forge-only renderSky
 * override) by Ad Astra. Standard practice for modded dimensions that need a non-overworld
 * skybox to render correctly with shaderpacks.
 *
 * The fog overrides below are present for convention; in practice
 * `FogRendererSselithRepertoryMixin` TAIL-injects the actual fog colour the shader uniform
 * receives, so this method's return value is overwritten before it reaches the GPU.
 *
 * Registration: platform-specific —
 *  - Fabric: `DimensionRenderingRegistry.registerDimensionEffects(...)`.
 *  - Forge:  `RegisterDimensionSpecialEffectsEvent.register(...)` (mod-bus event).
 *
 * Effects ID: matches the `effects` field in `data/enderkinesis/dimension_type/sselith_repertory.json`.
 */
class SselithDimensionEffects : DimensionSpecialEffects(
    /* cloudLevel = */ Float.NaN,
    /* hasGround = */ true,
    /* skyType   = */ SkyType.NONE,
    /* forceBrightLightmap = */ false,
    /* constantAmbientLight = */ false,
) {
    override fun getBrightnessDependentFogColor(fogColor: Vec3, brightness: Float): Vec3 = fogColor

    override fun isFoggyAt(x: Int, y: Int): Boolean = true
}
