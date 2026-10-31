package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import org.shipwrights.enderkinesis.client.BloodSurgeFadeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs before the effect is removed, while the player still has it. The network-thread pass is ignored. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerBloodSurgeMixin {

    @Inject(method = "handleRemoveMobEffect", at = @At("HEAD"))
    private void enderkinesis$bloodSurgeRemoved(ClientboundRemoveMobEffectPacket packet, CallbackInfo ci) {
        BloodSurgeFadeClient.onRemoveEffect(packet);
    }
}
