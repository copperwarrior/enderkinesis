package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererFovInvoker {
    @Invoker("getFov")
    double enderkinesis$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
