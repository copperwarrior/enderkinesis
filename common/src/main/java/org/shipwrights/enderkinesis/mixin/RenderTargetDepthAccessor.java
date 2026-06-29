package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Expose {@link RenderTarget}'s protected {@code depthBufferId} so
 *  {@link org.shipwrights.enderkinesis.client.ConcealmentMaskFB} can attach the main
 *  render target's depth texture to its own FBO (shared depth → bubble auto-occludes
 *  against world geometry).
 */
@Mixin(RenderTarget.class)
public interface RenderTargetDepthAccessor {

    @Accessor("depthBufferId")
    int enderkinesis$getDepthBufferId();
}
