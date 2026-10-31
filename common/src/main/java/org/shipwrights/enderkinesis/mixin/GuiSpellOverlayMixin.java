package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.shipwrights.enderkinesis.client.SpellOverlays;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws under the HUD, after the vignette and frost. Forge's hotbar overlay also calls this method, so the two loaders agree. */
@Mixin(Gui.class)
public abstract class GuiSpellOverlayMixin {
    @Inject(method = "renderHotbar", at = @At("HEAD"))
    private void enderkinesis$spellOverlays(float partialTick, GuiGraphics graphics, CallbackInfo ci) {
        SpellOverlays.renderUnderHud(graphics, partialTick);
    }
}
