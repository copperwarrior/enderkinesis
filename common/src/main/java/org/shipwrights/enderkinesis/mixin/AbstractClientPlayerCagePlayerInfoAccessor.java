package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writable handle for {@code AbstractClientPlayer.playerInfo} — used by the
 * Ga'Mylan cage's player-ghost path to install a Mojang-fetched
 * {@link PlayerInfo} on the ghost when the client's own connection has no
 * matching entry (world-reload / offline-player case). Without this,
 * {@link AbstractClientPlayer#getSkin()} finds no PlayerInfo and falls back
 * to Steve/Alex forever.
 */
@Mixin(AbstractClientPlayer.class)
public interface AbstractClientPlayerCagePlayerInfoAccessor {

    @Accessor("playerInfo")
    void enderkinesis$setPlayerInfo(PlayerInfo info);
}
