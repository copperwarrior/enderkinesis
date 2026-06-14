package org.shipwrights.enderkinesis.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity;
import org.shipwrights.enderkinesis.item.VentriloquismNetwork;
import org.shipwrights.enderkinesis.item.VentriloquismTomeOrbBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sound + vibration mirror for the Tome of Ventriloquism. Four paths are tapped at HEAD; a SEND
 * orb's 7-block capture cancels the original and re-broadcasts at each RECEIVE orb shifted by
 * the SEND→source offset. {@code playSeededSound} covers most sounds; {@code levelEvent} is
 * needed for block-break (id 2001) which bypasses playSeededSound; {@code gameEvent} mirrors
 * the vibration pipeline so sculk sensors and the Warden see the displaced signature.
 *
 * <p>Sound and vibration are decoupled in vanilla (a roar fires both, a comparator only sound,
 * a piston only the event) so each is mirrored independently. ThreadLocal active-receiver set
 * prevents direct loops (S→R→S→…) while still permitting chains through unrelated orbs.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelVentriloquismSoundMixin {

    /** Receive-orb positions currently being emitted to on this thread's mirror chain. A SEND
     *  whose receivers intersect this set is "linked" to the current emission point and would
     *  loop if it captured — those are skipped, every other SEND is free to chain-capture. */
    private static final ThreadLocal<Set<BlockPos>> ENDERKINESIS$ACTIVE_TARGETS =
        ThreadLocal.withInitial(HashSet::new);

    @Inject(
        method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$mirrorAtCoords(
        @Nullable Player player, double x, double y, double z,
        Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
        CallbackInfo ci
    ) {
        if (!enderkinesis$mirrorsChannel(source)) return;
        if (enderkinesis$mirrorSound(x, y, z, sound, source, volume, pitch, seed)) ci.cancel();
    }

    @Inject(
        method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$mirrorAtEntity(
        @Nullable Player player, Entity entity,
        Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
        CallbackInfo ci
    ) {
        if (!enderkinesis$mirrorsChannel(source)) return;
        if (enderkinesis$mirrorSound(entity.getX(), entity.getY(), entity.getZ(), sound, source, volume, pitch, seed)) ci.cancel();
    }

    @Inject(
        method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$mirrorLevelEvent(
        @Nullable Player player, int eventId, BlockPos pos, int data, CallbackInfo ci
    ) {
        // Sound-bearing level-event range. 1000–1999 = pure sound, 2000–2999 = particle+sound
        // (block-break id 2001 lives here). 3000+ events are reserved for special/global
        // effects (end portal, dragon, etc); leave those alone.
        if (eventId < 1000 || eventId >= 3000) return;
        if (enderkinesis$mirrorLevelEventBody(eventId, pos, data)) ci.cancel();
    }

    @Inject(
        method = "gameEvent(Lnet/minecraft/world/level/gameevent/GameEvent;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$mirrorGameEvent(
        GameEvent event, Vec3 pos, GameEvent.Context context, CallbackInfo ci
    ) {
        if (enderkinesis$mirrorGameEventBody(event, pos, context)) ci.cancel();
    }

    private boolean enderkinesis$mirrorSound(
        double x, double y, double z,
        Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed
    ) {
        ServerLevel self = (ServerLevel) (Object) this;
        Collection<BlockPos> orbs = VentriloquismNetwork.sendOrbs(self);
        if (orbs.isEmpty()) return false;
        Set<BlockPos> active = ENDERKINESIS$ACTIVE_TARGETS.get();

        boolean captured = false;
        List<BlockPos> stale = null;
        for (BlockPos orbPos : orbs) {
            BlockEntity be = self.getBlockEntity(orbPos);
            if (!(be instanceof OrbOfLinkingBlockEntity sendBe)) {
                if (stale == null) stale = new ArrayList<>();
                stale.add(orbPos);
                continue;
            }
            Vec3 sendCentre = sendBe.worldCenter();
            if (sendCentre == null) continue;
            double dx = x - sendCentre.x;
            double dy = y - sendCentre.y;
            double dz = z - sendCentre.z;
            if (dx * dx + dy * dy + dz * dz > VentriloquismNetwork.CAPTURE_RADIUS_SQ) continue;

            List<BlockPos> receivers = sendBe.outgoingPeers(
                VentriloquismTomeOrbBehavior.INSTANCE.getTomeKind()
            );
            if (enderkinesis$wouldLoop(receivers, active)) continue;

            captured = true;
            active.addAll(receivers);
            try {
                for (BlockPos recvPos : receivers) {
                    BlockEntity recvBeRaw = self.getBlockEntity(recvPos);
                    if (!(recvBeRaw instanceof OrbOfLinkingBlockEntity recvBe)) continue;
                    Vec3 recvCentre = recvBe.worldCenter();
                    if (recvCentre == null) continue;
                    self.playSeededSound(
                        null,
                        recvCentre.x + dx, recvCentre.y + dy, recvCentre.z + dz,
                        sound, source, volume, pitch, seed
                    );
                }
            } finally {
                active.removeAll(receivers);
            }
        }
        if (stale != null) {
            for (BlockPos s : stale) VentriloquismNetwork.unregister(self, s);
        }
        return captured;
    }

    private boolean enderkinesis$mirrorLevelEventBody(int eventId, BlockPos pos, int data) {
        ServerLevel self = (ServerLevel) (Object) this;
        Collection<BlockPos> orbs = VentriloquismNetwork.sendOrbs(self);
        if (orbs.isEmpty()) return false;
        Set<BlockPos> active = ENDERKINESIS$ACTIVE_TARGETS.get();

        // Treat the level-event source as the block centre — that's what the vanilla packet's
        // particle/sound origin lands on, so receivers see/hear it at the matching offset.
        double sx = pos.getX() + 0.5;
        double sy = pos.getY() + 0.5;
        double sz = pos.getZ() + 0.5;

        boolean captured = false;
        List<BlockPos> stale = null;
        for (BlockPos orbPos : orbs) {
            BlockEntity be = self.getBlockEntity(orbPos);
            if (!(be instanceof OrbOfLinkingBlockEntity sendBe)) {
                if (stale == null) stale = new ArrayList<>();
                stale.add(orbPos);
                continue;
            }
            Vec3 sendCentre = sendBe.worldCenter();
            if (sendCentre == null) continue;
            double dx = sx - sendCentre.x;
            double dy = sy - sendCentre.y;
            double dz = sz - sendCentre.z;
            if (dx * dx + dy * dy + dz * dz > VentriloquismNetwork.CAPTURE_RADIUS_SQ) continue;

            List<BlockPos> receivers = sendBe.outgoingPeers(
                VentriloquismTomeOrbBehavior.INSTANCE.getTomeKind()
            );
            if (enderkinesis$wouldLoop(receivers, active)) continue;

            captured = true;
            active.addAll(receivers);
            try {
                for (BlockPos recvPos : receivers) {
                    BlockEntity recvBeRaw = self.getBlockEntity(recvPos);
                    if (!(recvBeRaw instanceof OrbOfLinkingBlockEntity recvBe)) continue;
                    Vec3 recvCentre = recvBe.worldCenter();
                    if (recvCentre == null) continue;
                    // Level events take an integer block pos. Round the offset-adjusted point
                    // back to the nearest block so the client's particle/sound origin lands on
                    // a real block-aligned spot.
                    BlockPos relayPos = BlockPos.containing(
                        recvCentre.x + dx, recvCentre.y + dy, recvCentre.z + dz
                    );
                    self.levelEvent(null, eventId, relayPos, data);
                }
            } finally {
                active.removeAll(receivers);
            }
        }
        if (stale != null) {
            for (BlockPos s : stale) VentriloquismNetwork.unregister(self, s);
        }
        return captured;
    }

    private boolean enderkinesis$mirrorGameEventBody(GameEvent event, Vec3 pos, GameEvent.Context context) {
        ServerLevel self = (ServerLevel) (Object) this;
        Collection<BlockPos> orbs = VentriloquismNetwork.sendOrbs(self);
        if (orbs.isEmpty()) return false;
        Set<BlockPos> active = ENDERKINESIS$ACTIVE_TARGETS.get();

        boolean captured = false;
        List<BlockPos> stale = null;
        for (BlockPos orbPos : orbs) {
            BlockEntity be = self.getBlockEntity(orbPos);
            if (!(be instanceof OrbOfLinkingBlockEntity sendBe)) {
                if (stale == null) stale = new ArrayList<>();
                stale.add(orbPos);
                continue;
            }
            Vec3 sendCentre = sendBe.worldCenter();
            if (sendCentre == null) continue;
            double dx = pos.x - sendCentre.x;
            double dy = pos.y - sendCentre.y;
            double dz = pos.z - sendCentre.z;
            if (dx * dx + dy * dy + dz * dz > VentriloquismNetwork.CAPTURE_RADIUS_SQ) continue;

            List<BlockPos> receivers = sendBe.outgoingPeers(
                VentriloquismTomeOrbBehavior.INSTANCE.getTomeKind()
            );
            if (enderkinesis$wouldLoop(receivers, active)) continue;

            captured = true;
            active.addAll(receivers);
            try {
                for (BlockPos recvPos : receivers) {
                    BlockEntity recvBeRaw = self.getBlockEntity(recvPos);
                    if (!(recvBeRaw instanceof OrbOfLinkingBlockEntity recvBe)) continue;
                    Vec3 recvCentre = recvBe.worldCenter();
                    if (recvCentre == null) continue;
                    self.gameEvent(
                        event,
                        new Vec3(recvCentre.x + dx, recvCentre.y + dy, recvCentre.z + dz),
                        context
                    );
                }
            } finally {
                active.removeAll(receivers);
            }
        }
        if (stale != null) {
            for (BlockPos s : stale) VentriloquismNetwork.unregister(self, s);
        }
        return captured;
    }

    /** A send orb whose outgoing-receiver list intersects the currently-active target set is
     *  "linked" to the emission point and would loop if it captured. */
    private static boolean enderkinesis$wouldLoop(List<BlockPos> receivers, Set<BlockPos> active) {
        if (active.isEmpty()) return false;
        for (BlockPos r : receivers) {
            if (active.contains(r)) return true;
        }
        return false;
    }

    private static boolean enderkinesis$mirrorsChannel(SoundSource src) {
        return switch (src) {
            case BLOCKS, HOSTILE, NEUTRAL, PLAYERS, RECORDS, VOICE -> true;
            default -> false;
        };
    }
}
