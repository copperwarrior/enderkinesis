package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.shipwrights.enderkinesis.client.YgannAbyssVoidOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Final-fallback render path for the Ygann Abyss overlay when the HUD is hidden (F1)
 * AND no screen is open. {@code GameRenderer.render} skips {@code Gui.render} entirely
 * in that case (gated on {@code !hideGui || screen != null}), so
 * {@link GuiYgannAbyssOverlayMixin} never fires. Redirecting the unconditional
 * {@code ToastComponent.render} call gives a stable injection point with a valid
 * {@code GuiGraphics} in the correct coord space.
 *
 * <table>
 *   <tr><th>hideGui</th><th>screen</th>      <th>covered by</th></tr>
 *   <tr><td>false</td>  <td>any</td>         <td>Architectury events</td></tr>
 *   <tr><td>true</td>   <td>none</td>        <td><b>this mixin</b></td></tr>
 *   <tr><td>true</td>   <td>normal</td>      <td>RENDER_POST</td></tr>
 *   <tr><td>true</td>   <td>pause/death</td> <td>{@link GuiYgannAbyssOverlayMixin}</td></tr>
 * </table>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererYgannAbyssOverlayMixin {

    @Redirect(
        method = "render(FJZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;render(Lnet/minecraft/client/gui/GuiGraphics;)V"
        )
    )
    private void enderkinesis$drawAbyssOverlayAroundToasts(ToastComponent toasts, GuiGraphics graphics) {
        // Original behaviour first — toasts always render, regardless of overlay state.
        toasts.render(graphics);

        // Only handle the hideGui + no-screen case here. F1 + normal screen is handled
        // by RENDER_POST; F1 + pause/death is handled by GuiYgannAbyssOverlayMixin
        // (Gui.render IS called when screen != null). !hideGui is handled by the
        // standard RENDER_HUD path.
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.hideGui) return;
        if (mc.screen != null) return;
        YgannAbyssVoidOverlay.renderForF1Fallback(graphics, mc.getFrameTime());
    }
}
