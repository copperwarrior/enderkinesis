package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes the list of {@link PostPass} instances on a {@link PostChain}. Used by the
 * scrying client to walk passes and push a {@code SessionTime} uniform onto each pass's
 * inner {@code EffectInstance} every frame — vanilla's PostPass.process only sets
 * {@code Time} (partialTicks 0..1) and {@code ScreenSize}, leaving any {@code GameTime}
 * uniform at its JSON default forever.
 */
@Mixin(PostChain.class)
public interface PostChainAccessor {

    @Accessor("passes")
    List<PostPass> enderkinesis$getPasses();
}
