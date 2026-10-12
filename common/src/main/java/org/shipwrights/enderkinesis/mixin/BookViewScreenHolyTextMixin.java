package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.FormattedText;
import org.shipwrights.enderkinesis.client.HolyText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BookViewScreen.class)
public abstract class BookViewScreenHolyTextMixin {

    @ModifyExpressionValue(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;getPage(I)Lnet/minecraft/network/chat/FormattedText;"
        )
    )
    private FormattedText enderkinesis$markHolyText(FormattedText page) {
        return HolyText.mark(page);
    }
}
