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

    // Per thread, so a probe on one server thread never hides a real edit on another.
    private static final ThreadLocal<Boolean> PROBE = ThreadLocal.withInitial(() -> false);

    /** True while a spell asks the loader events for permission. Nothing changes the world then, so our own
     *  listeners skip it; on Fabric they never hear the probe at all. */
    public static boolean isPermissionProbe() {
        return PROBE.get();
    }

    public static boolean mayBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        boolean outer = PROBE.get();
        PROBE.set(true);
        try {
            return postBreak(level, pos, state, player);
        } finally {
            PROBE.set(outer);
        }
    }

    public static boolean mayPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
        boolean outer = PROBE.get();
        PROBE.set(true);
        try {
            return postPlace(level, pos, placed, player);
        } finally {
            PROBE.set(outer);
        }
    }

    @ExpectPlatform
    public static boolean postBreak(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer player) {
        throw new AssertionError();
    }

    /** Fabric has no place event, so this always allows the place there. */
    @ExpectPlatform
    public static boolean postPlace(ServerLevel level, BlockPos pos, BlockState placed, ServerPlayer player) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static ServerPlayer fakePlayer(ServerLevel level, GameProfile profile) {
        throw new AssertionError();
    }
}
