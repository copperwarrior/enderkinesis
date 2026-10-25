package org.shipwrights.enderkinesis.spell;

import com.mojang.authlib.GameProfile;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Loader hooks for spell claim checks. Claim and protection mods listen to the loaders'
 * own block events, so a spell posts the same events a player breaking or placing the
 * block would.
 */
public final class SpellPlatform {
    private SpellPlatform() {}

    /** Whether [player] may break [state] at [pos], by the loader's break event. */
    @ExpectPlatform
    public static boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        throw new AssertionError();
    }

    /** Whether [player] may place [placed] at [pos], by the loader's place event. Fabric
     *  has none, so it always allows there. */
    @ExpectPlatform
    public static boolean mayPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
        throw new AssertionError();
    }

    /** A loader fake player standing in for an offline spell owner. */
    @ExpectPlatform
    public static ServerPlayer fakePlayer(ServerLevel level, GameProfile profile) {
        throw new AssertionError();
    }
}
