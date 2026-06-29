package org.shipwrights.enderkinesis.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.dimension.SureibjinEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Allow normal sleeping behaviour in ulder source dimensions (Sselith /
 * Wohlon / Ygann) — bypass vanilla's natural-dimension check and the
 * setRespawnPosition call, but keep the night-time gate and let the
 * player enter the sleeping state for real (long animation, day-skip
 * accumulating).
 *
 * <p>The actual Sureibjin teleport doesn't happen here — it fires in
 * {@link ServerLevelSureibjinWakeMixin} at the moment vanilla would
 * normally wake all sleepers and skip to morning. That's "the time it
 * would usually trigger the time change."
 *
 * <p>HEAD-cancellable returns {@code Either.right(Unit.INSTANCE)} after
 * starting the sleep manually via {@code startSleeping(BlockPos)} (a
 * public method on {@code LivingEntity}). We skip the vanilla
 * setRespawnPosition deliberately — we don't want the bed to become the
 * player's respawn point in an ulder dim. We also skip the monsters-nearby
 * safety check; the dream-portal is meant to be reachable.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerSureibjinSleepMixin {

    @Inject(method = "startSleepInBed", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$ulderDimSleep(
        BlockPos bedPos,
        CallbackInfoReturnable<Either<Player.BedSleepingProblem, Unit>> cir
    ) {
        ServerPlayer self = (ServerPlayer)(Object) this;
        if (!SureibjinEntry.isUlderEntrySource(self.level().dimension())) return;

        if (self.isSleeping() || !self.isAlive()) {
            cir.setReturnValue(Either.left(Player.BedSleepingProblem.OTHER_PROBLEM));
            return;
        }
        if (self.level().isDay()) {
            cir.setReturnValue(Either.left(Player.BedSleepingProblem.NOT_POSSIBLE_NOW));
            return;
        }

        // Enter the sleep state without setting respawn position. Vanilla
        // sleep tick logic (skip-night detection in ServerLevel.tick) will
        // pick this up like any other sleeping player.
        self.startSleeping(bedPos);
        // CRITICAL: refresh the level's sleep status. The sleepStatus is
        // only recomputed when startSleepInBed / stopSleepInBed call
        // updateSleepingPlayerList — without this our sleeper is invisible
        // to areEnoughSleeping and wakeUpAllPlayers never fires.
        self.serverLevel().updateSleepingPlayerList();
        cir.setReturnValue(Either.right(Unit.INSTANCE));
    }
}
