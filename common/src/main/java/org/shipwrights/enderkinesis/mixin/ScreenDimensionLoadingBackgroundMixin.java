package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.shipwrights.enderkinesis.client.DimensionLoadingBackground;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Screen.class)
public abstract class ScreenDimensionLoadingBackgroundMixin {

    // ClientPacketListener sets the destination level before it opens ReceivingLevelScreen.
    @ModifyArg(
        method = "renderDirtBackground(Lnet/minecraft/client/gui/GuiGraphics;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIFFIIII)V"
        ),
        index = 0
    )
    private ResourceLocation enderkinesis$dimensionLoadingBackground(ResourceLocation dirt) {
        if (!((Object) this instanceof ReceivingLevelScreen)) return dirt;
        return DimensionLoadingBackground.textureOr(dirt);
    }
}
