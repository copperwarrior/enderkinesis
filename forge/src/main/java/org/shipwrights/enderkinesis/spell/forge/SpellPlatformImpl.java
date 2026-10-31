package org.shipwrights.enderkinesis.spell.forge;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;

public final class SpellPlatformImpl {
    private SpellPlatformImpl() {}

    public static boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player));
    }

    public static boolean mayPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        BlockState against = level.getBlockState(pos.relative(Direction.DOWN));
        return !MinecraftForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snapshot, against, player));
    }

    public static ServerPlayer fakePlayer(ServerLevel level, GameProfile profile) {
        return FakePlayerFactory.get(level, profile);
    }
}
