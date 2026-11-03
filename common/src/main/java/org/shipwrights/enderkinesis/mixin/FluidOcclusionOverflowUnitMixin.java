package org.shipwrights.enderkinesis.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// TEMPORARY VS2 WORKAROUND (2026-10-31, reported upstream). Remove when VS2 moves its overflow units.
// VS2 2.4.13+1b453e9d81 binds overflow masks to units 14/15, which ShipShadowMap also uses. In
// rendertype_translucent, sampler2DArrayShadow and sampler2D then share unit 15, so every water draw
// fails with GL_INVALID_OPERATION. Units 16/17 are safe because these binds use raw GL, not GlStateManager.
@Mixin(targets = "org.valkyrienskies.mod.common.fluid.FluidOcclusionOverflow", remap = false)
public abstract class FluidOcclusionOverflowUnitMixin {

    @Unique private static final int VS_SHADOW_UNIT = 14;
    @Unique private static final int VS_SHADOW_COMPARE_UNIT = 15;
    @Unique private static final int MASK_UNIT = 16;
    @Unique private static final int DESCRIPTOR_UNIT = 17;

    @ModifyVariable(method = "update(Ljava/util/List;DDDII)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int enderkinesis$updateMaskUnit(int unit) {
        return enderkinesis$remap(unit);
    }

    @ModifyVariable(method = "update(Ljava/util/List;DDDII)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int enderkinesis$updateDescriptorUnit(int unit) {
        return enderkinesis$remap(unit);
    }

    @ModifyVariable(method = "bind(IIIIII)V", at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private int enderkinesis$bindMaskUnit(int unit) {
        return enderkinesis$remap(unit);
    }

    @ModifyVariable(method = "bind(IIIIII)V", at = @At("HEAD"), argsOnly = true, ordinal = 5)
    private int enderkinesis$bindDescriptorUnit(int unit) {
        return enderkinesis$remap(unit);
    }

    // Only the clashing units move; the Sodium compact path (3/4) passes through unchanged.
    @Unique
    private static int enderkinesis$remap(int unit) {
        if (unit == VS_SHADOW_UNIT) return MASK_UNIT;
        if (unit == VS_SHADOW_COMPARE_UNIT) return DESCRIPTOR_UNIT;
        return unit;
    }
}
