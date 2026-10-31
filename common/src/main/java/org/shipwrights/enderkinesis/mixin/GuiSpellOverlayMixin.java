package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.shipwrights.enderkinesis.client.SpellOverlays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The spell statuses' screen overlays ({@link SpellOverlays}) lie under the HUD, as vanilla's
 *  vignette and powder-snow frost do: drawn just before the hotbar, after those. The hotbar is
 *  drawn through this method on both loaders (Forge's hotbar overlay calls it too), so the
 *  overlays land in the same place on either. */
@Mixin(Gui.class)
public abstract class GuiSpellOverlayMixin {
    @Inject(method = "renderHotbar", at = @At("HEAD"))
    private void enderkinesis$spellOverlays(float partialTick, GuiGraphics graphics, CallbackInfo ci) {
        SpellOverlays.renderUnderHud(graphics, partialTick);
    }
}
