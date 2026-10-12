package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.resources.ResourceLocation;
import org.shipwrights.enderkinesis.client.BookSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(PageButton.class)
public abstract class PageButtonSkinMixin {

    @ModifyArg(
        method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"
        ),
        index = 0
    )
    private ResourceLocation enderkinesis$skinArrow(ResourceLocation vanilla) {
        return BookSkin.arrowTexture(vanilla);
    }
}
