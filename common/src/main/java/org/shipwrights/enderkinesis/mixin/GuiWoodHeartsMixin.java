package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.client.WoodHeartsHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge's health overlay (ForgeGui.renderHealth) also calls renderHearts, so one hook serves both loaders.
 * In each loop pass renderHeart call 0 draws the container and call 3 draws the health heart.
 */
@Mixin(Gui.class)
public abstract class GuiWoodHeartsMixin {

    @Unique
    private int enderkinesis$heartIndex;

    @Unique
    private int enderkinesis$health;

    @Inject(method = "renderHearts", at = @At("HEAD"))
    private void enderkinesis$startHearts(GuiGraphics graphics, Player player, int x, int y, int rowHeight, int regen,
                                          float maxHealth, int health, int displayHealth, int absorption,
                                          boolean blinking, CallbackInfo ci) {
        enderkinesis$heartIndex = Mth.ceil(maxHealth / 2.0) + Mth.ceil(absorption / 2.0);
        enderkinesis$health = health;
    }

    // The loop counts down, one container per pass.
    @WrapOperation(
        method = "renderHearts",
        at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/Gui;renderHeart(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Gui$HeartType;IIIZZ)V")
    )
    private void enderkinesis$countHeart(Gui self, GuiGraphics graphics, Gui.HeartType type, int x, int y, int v,
                                         boolean blinking, boolean half, Operation<Void> original) {
        enderkinesis$heartIndex--;
        original.call(self, graphics, type, x, y, v, blinking, half);
    }

    @WrapOperation(
        method = "renderHearts",
        at = @At(value = "INVOKE", ordinal = 3,
            target = "Lnet/minecraft/client/gui/Gui;renderHeart(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Gui$HeartType;IIIZZ)V")
    )
    private void enderkinesis$woodHeart(Gui self, GuiGraphics graphics, Gui.HeartType type, int x, int y, int v,
                                        boolean blinking, boolean half, Operation<Void> original) {
        if (WoodHeartsHud.drawWoodHeart(graphics, enderkinesis$heartIndex, x, y, v, enderkinesis$health,
                type.getX(true, false))) {
            return;
        }
        original.call(self, graphics, type, x, y, v, blinking, half);
    }
}
