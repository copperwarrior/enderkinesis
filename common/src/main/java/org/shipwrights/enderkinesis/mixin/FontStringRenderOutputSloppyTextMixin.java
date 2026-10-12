package org.shipwrights.enderkinesis.mixin;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.shipwrights.enderkinesis.client.SloppyText;
import org.shipwrights.enderkinesis.item.InkBleed;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public abstract class FontStringRenderOutputSloppyTextMixin {

    @Shadow float x;
    @Shadow float y;

    // accept reads these when the style has no color. The tint changes them for one glyph, without a Style allocation.
    @Shadow @Final @Mutable private float r;
    @Shadow @Final @Mutable private float g;
    @Shadow @Final @Mutable private float b;
    @Shadow @Final private float dimFactor;

    @Unique private boolean enderkinesis$sloppyTinted;
    @Unique private float enderkinesis$plainR;
    @Unique private float enderkinesis$plainG;
    @Unique private float enderkinesis$plainB;

    @Unique private boolean enderkinesis$inSloppyWord;
    @Unique private float enderkinesis$sloppyWordDx;
    @Unique private float enderkinesis$sloppyWordDy;
    @Unique private float enderkinesis$sloppyDx;
    @Unique private float enderkinesis$sloppyDy;

    // Allocates a Style, but only for the few glyphs near a colored run on an Old Paper page.
    @ModifyVariable(method = "accept(ILnet/minecraft/network/chat/Style;I)Z", at = @At("HEAD"), argsOnly = true)
    private Style enderkinesis$bleedSloppyInk(Style style) {
        TextColor color = style.getColor();
        if (color == null || color.getValue() >>> InkBleed.WEIGHT_SHIFT == 0 || !SloppyText.isSloppy(style)) {
            return style;
        }
        return style.withColor(TextColor.fromRgb(SloppyText.bleedColor(x, y, color.getValue())));
    }

    @Inject(method = "accept(ILnet/minecraft/network/chat/Style;I)Z", at = @At("HEAD"))
    private void enderkinesis$jitterSloppyGlyph(int index, Style style, int codePoint, CallbackInfoReturnable<Boolean> cir) {
        if (!SloppyText.isSloppy(style) || Character.isWhitespace(codePoint)) {
            enderkinesis$inSloppyWord = false;
            return;
        }
        if (!enderkinesis$inSloppyWord) {
            enderkinesis$inSloppyWord = true;
            enderkinesis$sloppyWordDx = SloppyText.wordDx(x, y);
            enderkinesis$sloppyWordDy = SloppyText.wordDy(x, y);
        }
        int ink = SloppyText.inkColor(x, y);
        enderkinesis$sloppyTinted = true;
        enderkinesis$plainR = r;
        enderkinesis$plainG = g;
        enderkinesis$plainB = b;
        r = SloppyText.tintChannel(r, ink, SloppyText.RED_SHIFT, dimFactor);
        g = SloppyText.tintChannel(g, ink, SloppyText.GREEN_SHIFT, dimFactor);
        b = SloppyText.tintChannel(b, ink, 0, dimFactor);
        enderkinesis$sloppyDx = enderkinesis$sloppyWordDx + SloppyText.glyphDx(x, y, codePoint);
        enderkinesis$sloppyDy = SloppyText.glyphDy(x, y, codePoint, enderkinesis$sloppyWordDy);
        x += enderkinesis$sloppyDx;
        y += enderkinesis$sloppyDy;
    }

    @Inject(method = "accept(ILnet/minecraft/network/chat/Style;I)Z", at = @At("RETURN"))
    private void enderkinesis$undoSloppyJitter(int index, Style style, int codePoint, CallbackInfoReturnable<Boolean> cir) {
        x -= enderkinesis$sloppyDx;
        y -= enderkinesis$sloppyDy;
        enderkinesis$sloppyDx = 0.0F;
        enderkinesis$sloppyDy = 0.0F;
        if (enderkinesis$sloppyTinted) {
            enderkinesis$sloppyTinted = false;
            r = enderkinesis$plainR;
            g = enderkinesis$plainG;
            b = enderkinesis$plainB;
        }
    }
}
