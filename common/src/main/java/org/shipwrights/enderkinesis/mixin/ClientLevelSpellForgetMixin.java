package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.SpellNetworkClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server sends spell state again when tracking starts again ({@code SpellNetwork}), so the client can discard it here. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSpellForgetMixin {

    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void enderkinesis$forgetSpellState(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        SpellNetworkClient.forget(id);
    }
}
