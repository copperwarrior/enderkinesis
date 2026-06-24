package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Two pieces of {@link GameRenderer} exposed for the scrying client:
 *
 * <ul>
 *   <li>{@code loadEffect(ResourceLocation)} — package-private in 1.20.1. The scrying
 *       client installs our refraction PostChain on {@code beginScrying} and uninstalls
 *       via {@code GameRenderer#shutdownEffect} (already public) when the view ends.</li>
 *   <li>{@code postEffect} — the currently-loaded {@link PostChain}, also package-
 *       private. We need to reach into it each frame to push a {@code SessionTime}
 *       uniform onto the refract pass: vanilla's {@link net.minecraft.client.renderer.PostPass#process(float)}
 *       only auto-sets {@code Time} and {@code ScreenSize}, not {@code GameTime}, so any
 *       PostChain shader referencing {@code GameTime} just reads the JSON default (0.0)
 *       forever and never animates. Without this accessor there's no clean way to drive
 *       continuous time into a PostChain shader.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public interface GameRendererPostEffectInvoker {

    @Invoker("loadEffect")
    void enderkinesis$loadEffect(ResourceLocation resourceLocation);

    @Accessor("postEffect")
    PostChain enderkinesis$getPostEffect();

    /** Gate for {@code GameRenderer.render} to actually invoke
     *  {@code postEffect.process}: when false the PostChain stays loaded but no
     *  per-frame quad runs. Toggled by [ConcealmentPostEffect] so we don't have
     *  to {@code shutdownEffect} (which nulls the PostChain → next engagement
     *  reloads cold → one-frame dark flash from first-use shader compile +
     *  intermediate-target alloc). Keep loaded, toggle this instead. */
    @Accessor("effectActive")
    void enderkinesis$setEffectActive(boolean active);

    @Accessor("effectActive")
    boolean enderkinesis$getEffectActive();
}
