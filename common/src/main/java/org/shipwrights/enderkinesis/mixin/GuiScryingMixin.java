package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.shipwrights.enderkinesis.client.ScryingClient;
import org.shipwrights.enderkinesis.client.TomeSummonOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two responsibilities while a scrying view is active:
 *
 * <ol>
 *   <li><b>Force-hide the HUD.</b> {@code Gui.render} reads {@code minecraft.options.hideGui}
 *       in several places to decide whether to draw the hotbar, hearts, hunger, crosshair,
 *       chat, etc. We intercept every one of those reads with a MixinExtras
 *       {@code @ModifyExpressionValue} and OR in {@link ScryingClient#isActive()}. While a
 *       view is active every check sees {@code true} regardless of the player's actual F1
 *       preference, so the HUD stays gone even if the player presses F1 mid-view — the
 *       previous {@code mc.options.hideGui = true} approach could be circumvented by F1.
 *       The real value of {@code hideGui} is never mutated, so F1 still toggles the
 *       baseline preference and the player's setting is preserved when the view ends.
 *   </li>
 *   <li><b>Draw the vignette + glyph overlay.</b> At the TAIL of {@code Gui.render}, after
 *       all the HUD branches have run (or been skipped), we draw the scrying overlay.
 *       Self-gated on {@code isActive()} so non-scrying players pay one {@code if}.</li>
 * </ol>
 */
@Mixin(Gui.class)
public abstract class GuiScryingMixin {

    @ModifyExpressionValue(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;hideGui:Z")
    )
    private boolean enderkinesis$forceHideHudWhileScrying(boolean original) {
        return original || ScryingClient.isActive();
    }

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
        at = @At("HEAD")
    )
    private void enderkinesis$drawScryingBackground(
        GuiGraphics graphics, float partialTick, CallbackInfo ci
    ) {
        // (Refraction uniform push moved to CameraScryingMixin.setup TAIL — Gui.render
        // is gated by vanilla's `!hideGui || screen != null`, so the scrying HUD-hide
        // skips Gui.render entirely. Camera.setup runs every frame regardless.)
        //
        // Fade overlay FIRST so the scry vignette and SGA glyphs render on top of it —
        // during the begin-fade hold the user still sees the atmospheric scry overlay
        // (brown vignette edges + drifting SGA runes) over the black fade rather than
        // pure void. Always-on; internally no-ops when alpha is effectively zero.
        ScryingClient.renderFadeOverlay(graphics);
        // Then the vignette on top of the fade (active sessions only). Sits in the same
        // "early HUD" layer as vanilla's own renderVignette / pumpkin overlay, so F3
        // debug, chat, and any subsequent screen composite OVER it.
        ScryingClient.renderBackground(graphics, partialTick);
        // Tome-summon overlay (only the LOCAL player's summon). Self-gated on the
        // envelope intensity, so the cost is a single null-check during normal play.
        // Drawn after the scrying vignette so when both are somehow active the
        // tome-summon vignette composites on top — in practice they're mutually
        // exclusive but the ordering is well-defined either way.
        TomeSummonOverlay.renderBackground(graphics, partialTick);
    }

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
        at = @At("RETURN")
    )
    private void enderkinesis$drawScryingForeground(
        GuiGraphics graphics, float partialTick, CallbackInfo ci
    ) {
        // SGA glyphs — drawn AFTER vanilla's body so they sit on top of F3 / chat /
        // vanilla's own multiplicative-vignette darkening pass, and on top of the fade +
        // vignette that were drawn during the HEAD inject. Without this RETURN split
        // the glyphs were getting visually drowned out by vanilla's later passes.
        ScryingClient.renderForeground(graphics, partialTick);
        // Outward-drifting glyphs for the local tome summon. Self-gated.
        TomeSummonOverlay.renderForeground(graphics, partialTick);
    }
}
