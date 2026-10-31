package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import org.shipwrights.enderkinesis.client.YgannBarnacleClientWork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerYgannBarnacleHullMixin {

    // TAIL runs only on the client thread: the handler's first call re-queues it there from the netty thread.
    @Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
    private void enderkinesis$ygannBarnacleHullChunkForgotten(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        YgannBarnacleClientWork.onChunkForgotten(packet.getX(), packet.getZ());
    }
}
