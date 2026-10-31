package org.shipwrights.enderkinesis.spell;

import com.mojang.authlib.GameProfile;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Claim and protection mods listen to the loader block events, so a spell sends the same events as a player. */
public final class SpellPlatform {
    private SpellPlatform() {}

    @ExpectPlatform
    public static boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        throw new AssertionError();
    }

    /** Fabric has no place event, so this always allows the place there. */
    @ExpectPlatform
    public static boolean mayPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static ServerPlayer fakePlayer(ServerLevel level, GameProfile profile) {
        throw new AssertionError();
    }
}
