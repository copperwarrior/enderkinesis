package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the {@link EffectInstance} backing a {@link PostPass}. The scrying client
 * fetches this each frame and writes a {@code SessionTime} uniform on the refract pass
 * so the shader's noise field actually animates — see {@link GameRendererPostEffectInvoker}
 * and {@link PostChainAccessor} for why this dance is required.
 */
@Mixin(PostPass.class)
public interface PostPassAccessor {

    @Accessor("effect")
    EffectInstance enderkinesis$getEffect();
}
