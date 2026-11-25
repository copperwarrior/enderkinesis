package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.client.WoodHeartsHud;
import org.shipwrights.enderkinesis.registry.EKEffects;
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
    private int enderkinesis$healthHearts;

    @Unique
    private int enderkinesis$health;

    @Unique
    private boolean enderkinesis$special;

    @Unique
    private boolean enderkinesis$trypoemia;

    @Inject(method = "renderHearts", at = @At("HEAD"))
    private void enderkinesis$startHearts(GuiGraphics graphics, Player player, int x, int y, int rowHeight, int regen,
                                          float maxHealth, int health, int displayHealth, int absorption,
                                          boolean blinking, CallbackInfo ci) {
        enderkinesis$healthHearts = Mth.ceil(maxHealth / 2.0);
        enderkinesis$heartIndex = enderkinesis$healthHearts + Mth.ceil(absorption / 2.0);
        enderkinesis$health = health;
        enderkinesis$special = Gui.HeartType.forPlayer(player) != Gui.HeartType.NORMAL;
        enderkinesis$trypoemia = player.hasEffect(EKEffects.INSTANCE.getTRYPOEMIA().get());
    }

    // The loop counts down, one container per pass. Absorption containers and the damage flash stay vanilla.
    @WrapOperation(
        method = "renderHearts",
        at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/Gui;renderHeart(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Gui$HeartType;IIIZZ)V")
    )
    private void enderkinesis$container(Gui self, GuiGraphics graphics, Gui.HeartType type, int x, int y, int v,
                                        boolean blinking, boolean half, Operation<Void> original) {
        int index = --enderkinesis$heartIndex;
        if (!blinking && index < enderkinesis$healthHearts
            && WoodHeartsHud.drawHeartContainer(graphics, index, x, y, enderkinesis$health,
                enderkinesis$special, enderkinesis$trypoemia)) {
            return;
        }
        original.call(self, graphics, type, x, y, v, blinking, half);
    }

    @WrapOperation(
        method = "renderHearts",
        at = @At(value = "INVOKE", ordinal = 3,
            target = "Lnet/minecraft/client/gui/Gui;renderHeart(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Gui$HeartType;IIIZZ)V")
    )
    private void enderkinesis$healthHeart(Gui self, GuiGraphics graphics, Gui.HeartType type, int x, int y, int v,
                                          boolean blinking, boolean half, Operation<Void> original) {
        if (WoodHeartsHud.drawHealthHeart(graphics, enderkinesis$heartIndex, x, y, v, enderkinesis$health,
                enderkinesis$special, enderkinesis$trypoemia, type.getX(true, false))) {
            return;
        }
        original.call(self, graphics, type, x, y, v, blinking, half);
    }
}
