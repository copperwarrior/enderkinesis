package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.vine.VineColliderKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerVineFloatMixin {

    // Vines are entity shapes, which noBlocksAround does not see: without this, standing on one kicks for flying.
    @Inject(method = "noBlocksAround", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$vineSupport(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && VineColliderKt.hasVineSupport(entity)) cir.setReturnValue(false);
    }
}
