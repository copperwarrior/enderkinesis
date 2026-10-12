package org.shipwrights.enderkinesis.mixin;

import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import org.shipwrights.enderkinesis.item.CommandNetwork;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Left-click while main-handing the Staff of Command = "Aim To". Sends the AIM
 * C2S packet (the server re-derives the gaze from its own player state), swings
 * for feedback, and short-circuits vanilla so no block-break / entity-attack
 * runs behind the gesture. Mirrors {@link MinecraftStartAttackGamylanMixin}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftStartAttackCommandMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$aimCommandedDevices(CallbackInfoReturnable<Boolean> cir) {
        Minecraft self = (Minecraft) (Object) this;
        LocalPlayer player = self.player;
        if (player == null || self.level == null) return;
        if (player.getMainHandItem().getItem() != EKItems.INSTANCE.getSTAFF_OF_COMMAND().get()) return;

        NetworkManager.sendToServer(CommandNetwork.INSTANCE.getAIM(), new FriendlyByteBuf(Unpooled.buffer()));
        player.swing(InteractionHand.MAIN_HAND);
        cir.setReturnValue(false);
    }
}
