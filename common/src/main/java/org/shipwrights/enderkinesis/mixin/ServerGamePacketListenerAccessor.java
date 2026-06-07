package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the package-private {@code aboveGroundTickCount} field on
 * {@link ServerGamePacketListenerImpl}. Vanilla increments this every server tick the
 * player is in the air; when it crosses a threshold, the anti-cheat kicks them for
 * "flying is not enabled".
 *
 * The {@link org.shipwrights.enderkinesis.blockentity.AetherPadBlockEntity} aether-pad
 * push holds players aloft in the beam — the same scenario Create's encased fan handles
 * by resetting this counter to 0 each tick via the same direct field assignment. We need
 * the accessor here because Mojang's mappings keep the field private to the packet
 * listener class.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {

    @Accessor("aboveGroundTickCount")
    void enderkinesis$setAboveGroundTickCount(int value);
}
