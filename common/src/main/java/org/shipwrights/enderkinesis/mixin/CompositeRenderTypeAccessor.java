package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Package-private in vanilla; only the dev jar widens it, so the AP sees it as public.
@SuppressWarnings("public-target")
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface CompositeRenderTypeAccessor {
    @Invoker("state")
    RenderType.CompositeState enderkinesis$state();
}
