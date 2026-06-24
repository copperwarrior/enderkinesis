package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.ScryingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep the scrying refraction PostChain alive across camera-type changes.
 *
 * <p>Vanilla {@code GameRenderer.checkEntityPostEffect} closes the current
 * {@code postEffect} and only re-loads it for camera entities that map to a baked-in
 * post-effect (Creeper / Spider / EnderMan). It runs from
 * {@code Minecraft.handleKeybinds} whenever {@code keyTogglePerspective} fires and
 * the first/third-person state flips. The player isn't one of the mob types, so an
 * F5 mid-session closes our refraction PostChain and never re-attaches it.
 *
 * <p>HEAD-cancelling while a session is active keeps the refraction loaded across
 * any number of perspective toggles. The mob-specific vanilla post-effects are
 * mutually exclusive with ours anyway — only one PostChain slot — and we drive ours
 * directly via {@code GameRendererPostEffectInvoker.loadEffect}.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererScryingMixin {

    @Inject(method = "checkEntityPostEffect", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$preserveScryingPostEffect(Entity entity, CallbackInfo ci) {
        if (ScryingClient.isActive()) {
            ci.cancel();
        }
    }

    /**
     * F4 (key code 293) is wired directly in {@code KeyboardHandler.keyPress} to call
     * {@code GameRenderer.togglePostEffect()}, which flips the boolean
     * {@code effectActive}. When false, the {@code PostChain} doesn't run — the shader
     * stays loaded but the per-frame process is skipped, so our refraction effect's
     * noise field freezes. This isn't a mod binding; it's vanilla's own debug toggle
     * (mostly seen in combination with F3, but the F4-alone path goes here too).
     *
     * <p>Cancelling the toggle while scrying keeps the effect running. The user can
     * still toggle it freely outside a session.
     */
    @Inject(method = "togglePostEffect", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$blockToggleDuringScrying(CallbackInfo ci) {
        if (ScryingClient.isActive()) {
            ci.cancel();
        }
    }
}
