package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A render type's state, to read the texture it draws with ({@code EntityPasses}). */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface CompositeRenderTypeAccessor {
    @Invoker("state")
    RenderType.CompositeState enderkinesis$state();
}
