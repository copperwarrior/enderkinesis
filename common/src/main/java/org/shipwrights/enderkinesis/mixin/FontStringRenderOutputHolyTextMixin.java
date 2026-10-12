package org.shipwrights.enderkinesis.mixin;

import net.minecraft.Util;
import net.minecraft.network.chat.Style;
import org.shipwrights.enderkinesis.client.HolyText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public abstract class FontStringRenderOutputHolyTextMixin {

    @Shadow float x;
    @Shadow float y;

    @Unique private float enderkinesis$holyDriftX;
    @Unique private float enderkinesis$holyDriftY;

    // Not a @WrapOperation on renderChar: Operation.call boxes 13 arguments for each glyph of all text.
    @ModifyVariable(
        method = "accept(ILnet/minecraft/network/chat/Style;I)Z",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Style enderkinesis$shimmerAndDriftHolyGlyph(Style style, int index) {
        if (!HolyText.isHoly(style)) return style;
        long millis = Util.getMillis();
        enderkinesis$holyDriftX = HolyText.driftX(index, millis);
        enderkinesis$holyDriftY = HolyText.driftY(index, millis);
        x += enderkinesis$holyDriftX;
        y += enderkinesis$holyDriftY;
        return style.withColor(HolyText.shimmerColor(index, millis));
    }

    @Inject(method = "accept(ILnet/minecraft/network/chat/Style;I)Z", at = @At("RETURN"))
    private void enderkinesis$undoHolyDrift(int index, Style style, int codePoint, CallbackInfoReturnable<Boolean> cir) {
        x -= enderkinesis$holyDriftX;
        y -= enderkinesis$holyDriftY;
        enderkinesis$holyDriftX = 0.0F;
        enderkinesis$holyDriftY = 0.0F;
    }
}
