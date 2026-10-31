package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.SpellNetworkClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An entity leaving the client (out of range, dead, gone to another dimension) takes its
 *  spell state with it: the server only sends state to players tracking an entity, and sends
 *  it again when they start tracking it once more (see {@code SpellNetwork}). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSpellForgetMixin {

    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void enderkinesis$forgetSpellState(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        SpellNetworkClient.forget(id);
    }
}
