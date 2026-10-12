package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sibling of {@link MinecraftStartAttackCommandMixin} for the hold-left-click
 * path — {@code continueAttack} reads {@code hitResult} independently and would
 * start mining the block behind the aim gesture. Mirrors
 * {@link MinecraftContinueAttackGamylanMixin}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftContinueAttackCommandMixin {

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$stopMiningWithStaff(boolean pressed, CallbackInfo ci) {
        if (!pressed) return;
        Minecraft self = (Minecraft) (Object) this;
        LocalPlayer player = self.player;
        if (player == null || self.level == null) return;
        if (player.getMainHandItem().getItem() != EKItems.INSTANCE.getSTAFF_OF_COMMAND().get()) return;

        self.gameMode.stopDestroyBlock();
        ci.cancel();
    }
}
