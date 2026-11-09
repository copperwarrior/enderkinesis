package org.shipwrights.enderkinesis.spell.forge;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.level.BlockEvent;

public final class SpellPlatformImpl {
    private SpellPlatformImpl() {}

    public static boolean postBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player));
    }

    public static boolean postPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, pos);
        BlockState against = level.getBlockState(pos.relative(Direction.DOWN));
        return !MinecraftForge.EVENT_BUS.post(new ProbePlaceEvent(snapshot, against, player, placed));
    }

    /** The world is not changed for a probe, so the snapshot still reads the old block; this reports [placed]. */
    public static final class ProbePlaceEvent extends BlockEvent.EntityPlaceEvent {
        private final BlockState placed;

        public ProbePlaceEvent(BlockSnapshot snapshot, BlockState against, Entity entity, BlockState placed) {
            super(snapshot, against, entity);
            this.placed = placed;
        }

        @Override
        public BlockState getState() {
            return placed;
        }

        @Override
        public BlockState getPlacedBlock() {
            return placed;
        }
    }

    public static ServerPlayer fakePlayer(ServerLevel level, GameProfile profile) {
        return FakePlayerFactory.get(level, profile);
    }
}
