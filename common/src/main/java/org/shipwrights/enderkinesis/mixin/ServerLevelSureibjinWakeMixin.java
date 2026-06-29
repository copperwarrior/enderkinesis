package org.shipwrights.enderkinesis.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shipwrights.enderkinesis.dimension.SureibjinEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook the moment vanilla wakes all sleepers and skips to morning. That's
 * the trigger point the user specified for the Sureibjin teleport — the
 * bed acts normally up to here, then the dream begins.
 *
 * <p>{@code ServerLevel.wakeUpAllPlayers} is private; mixin can still
 * target it. HEAD inject captures the sleeping players (and their bed
 * positions, which vanilla's wake loop clears) BEFORE vanilla wakes them;
 * TAIL inject teleports those players to Sureibjin via
 * {@link SureibjinEntry#enter} once the wake loop has run.
 *
 * <p>Non-ulder dimensions (overworld, nether, etc.) are unaffected — the
 * gate at HEAD short-circuits before we touch any state.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSureibjinWakeMixin {

    @Unique
    private final List<ServerPlayer> enderkinesis$pendingSureibjinTransit = new ArrayList<>();

    @Unique
    private final Map<ServerPlayer, BlockPos> enderkinesis$pendingSureibjinBedPos = new HashMap<>();

    @Inject(method = "wakeUpAllPlayers", at = @At("HEAD"))
    private void enderkinesis$captureUlderSleepers(CallbackInfo ci) {
        ServerLevel self = (ServerLevel)(Object) this;
        if (!SureibjinEntry.isUlderEntrySource(self.dimension())) return;

        enderkinesis$pendingSureibjinTransit.clear();
        enderkinesis$pendingSureibjinBedPos.clear();
        for (ServerPlayer p : self.players()) {
            if (!p.isSleeping()) continue;
            BlockPos pos = p.getSleepingPos().orElse(p.blockPosition());
            enderkinesis$pendingSureibjinTransit.add(p);
            enderkinesis$pendingSureibjinBedPos.put(p, pos);
        }
    }

    @Inject(method = "wakeUpAllPlayers", at = @At("TAIL"))
    private void enderkinesis$routeUlderSleepersToSureibjin(CallbackInfo ci) {
        if (enderkinesis$pendingSureibjinTransit.isEmpty()) return;
        for (ServerPlayer p : enderkinesis$pendingSureibjinTransit) {
            BlockPos bedPos = enderkinesis$pendingSureibjinBedPos.get(p);
            if (bedPos != null) {
                SureibjinEntry.enter(p, bedPos);
            }
        }
        enderkinesis$pendingSureibjinTransit.clear();
        enderkinesis$pendingSureibjinBedPos.clear();
    }
}
