package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The field of view a frame is actually drawn with: the world's (the player's setting, moved by
 *  sprinting, Speed or a drawn bow) or the first-person hand's, both narrowed under water. Used
 *  to find where the first-person hand shows in the world ({@link org.shipwrights.enderkinesis.client.SpellHand}). */
@Mixin(GameRenderer.class)
public interface GameRendererFovInvoker {
    @Invoker("getFov")
    double enderkinesis$getFov(Camera camera, float partialTick, boolean useFovSetting);
}
