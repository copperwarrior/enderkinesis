package org.shipwrights.enderkinesis.spell.fabric;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

public final class SpellPlatformImpl {
    private SpellPlatformImpl() {}

    public static boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        return PlayerBlockBreakEvents.BEFORE.invoker()
            .beforeBlockBreak(level, player, pos, state, level.getBlockEntity(pos));
    }

    public static boolean mayPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
        return true;
    }

    public static ServerPlayer fakePlayer(ServerLevel level, GameProfile profile) {
        return FakePlayer.get(level, profile);
    }
}
