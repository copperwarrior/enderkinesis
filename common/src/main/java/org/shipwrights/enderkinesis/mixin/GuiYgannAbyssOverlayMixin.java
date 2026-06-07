package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import org.shipwrights.enderkinesis.client.YgannAbyssVoidOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the Ygann Abyss overlay visible when the HUD is hidden via F1. Architectury's
 * {@code RENDER_HUD} fires from inside {@code Gui.render}'s {@code !hideGui} branch, so
 * F1 silently drops the callback; {@code RENDER_POST} is screen-only. Injecting at
 * {@code Gui.render}'s RETURN catches the cases the events don't.
 *
 * <table>
 *   <tr><th>hideGui</th><th>screen</th>      <th>covered by</th></tr>
 *   <tr><td>false</td>  <td>none</td>        <td>RENDER_HUD</td></tr>
 *   <tr><td>false</td>  <td>normal</td>      <td>RENDER_POST</td></tr>
 *   <tr><td>false</td>  <td>pause/death</td> <td>RENDER_HUD</td></tr>
 *   <tr><td><b>true</b></td> <td><b>none</b></td>        <td><b>this mixin</b></td></tr>
 *   <tr><td>true</td>   <td>normal</td>      <td>RENDER_POST</td></tr>
 *   <tr><td><b>true</b></td> <td><b>pause/death</b></td> <td><b>this mixin</b></td></tr>
 * </table>
 */
@Mixin(Gui.class)
public abstract class GuiYgannAbyssOverlayMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;F)V",
        at = @At("RETURN")
    )
    private void enderkinesis$drawAbyssOverlayWhenHudHidden(
        GuiGraphics graphics, float partialTick, CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.hideGui) return;            // RENDER_HUD already fired
        Screen screen = mc.screen;
        if (screen != null && !(screen instanceof PauseScreen) && !(screen instanceof DeathScreen)) {
            return;                                  // RENDER_POST will handle it
        }
        YgannAbyssVoidOverlay.renderForF1Fallback(graphics, partialTick);
    }
}
