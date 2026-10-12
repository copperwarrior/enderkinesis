package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.shipwrights.enderkinesis.client.OwlSightPasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the GUI draws over an owl frame, rewrite the alpha blend factors so main-target alpha
 * accumulates how much each pixel hides the world. Draws the world shows through (additive,
 * multiply, invert) add no coverage.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerOwlSightMixin {

    @Inject(method = "_blendFunc", at = @At("HEAD"), cancellable = true, remap = false)
    private static void enderkinesis$owlSightBlendFunc(int srcRgb, int dstRgb, CallbackInfo ci) {
        if (!OwlSightPasses.coverageActive) return;
        ci.cancel();
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, coverageSrc(dstRgb), coverageDst(dstRgb));
    }

    @Inject(method = "_blendFuncSeparate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void enderkinesis$owlSightBlendFuncSeparate(
        int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, CallbackInfo ci
    ) {
        if (!OwlSightPasses.coverageActive) return;
        int src = coverageSrc(dstRgb);
        int dst = coverageDst(dstRgb);
        if (src == srcAlpha && dst == dstAlpha) return;
        ci.cancel();
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, src, dst);
    }

    private static int coverageSrc(int dstRgb) {
        return dstRgb == GL11.GL_ONE_MINUS_SRC_ALPHA || dstRgb == GL11.GL_ZERO ? GL11.GL_ONE : GL11.GL_ZERO;
    }

    private static int coverageDst(int dstRgb) {
        if (dstRgb == GL11.GL_ONE_MINUS_SRC_ALPHA) return GL11.GL_ONE_MINUS_SRC_ALPHA;
        return dstRgb == GL11.GL_ZERO ? GL11.GL_ZERO : GL11.GL_ONE;
    }
}
