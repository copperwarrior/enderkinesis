package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity;
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingClientRegistry;
import org.shipwrights.enderkinesis.item.VentriloquismNetwork;
import org.shipwrights.enderkinesis.item.VentriloquismTomeOrbBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side companion to {@link ServerLevelVentriloquismSoundMixin}. Client prediction plays
 * footstep / block-place / block-break sounds locally before the server's broadcast comes back,
 * so the server-side cancel never reaches the originating player's ears. This mixin runs the
 * same in-bubble check on the client and cancels the local play. No replay here — the server
 * still emits its own broadcasts to the receivers, which the client picks up through the
 * normal ClientboundSoundPacket / ClientboundLevelEventPacket paths.
 *
 * <p>Three client paths are intercepted:
 * <ol>
 *   <li>{@code playSeededSound} (coord) — sounds emitted through {@code Level.playSound}.</li>
 *   <li>{@code playSeededSound} (entity) — entity-anchored variant.</li>
 *   <li>{@code playLocalSound} — what {@link net.minecraft.client.player.LocalPlayer} routes
 *       its own sounds through, including footsteps, breathing, swing, hurt. {@code LocalPlayer}
 *       overrides {@code playSound} to call this directly, bypassing {@code playSeededSound}
 *       entirely; without intercepting it the local player still hears their own steps.</li>
 *   <li>{@code levelEvent} — block-break particles+sound, riding the level-event packet.</li>
 * </ol>
 *
 * <p>The orb lookup uses {@link OrbOfLinkingClientRegistry}, which the BE already maintains for
 * the beam renderer — same source of truth, no extra packet plumbing.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelVentriloquismSoundMixin {

    @Inject(
        method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$suppressAtCoords(
        @Nullable Player player, double x, double y, double z,
        Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
        CallbackInfo ci
    ) {
        if (!enderkinesis$mirrorsChannel(source)) return;
        if (enderkinesis$insideAnyBubble(x, y, z)) ci.cancel();
    }

    @Inject(
        method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$suppressAtEntity(
        @Nullable Player player, Entity entity,
        Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
        CallbackInfo ci
    ) {
        if (!enderkinesis$mirrorsChannel(source)) return;
        if (enderkinesis$insideAnyBubble(entity.getX(), entity.getY(), entity.getZ())) ci.cancel();
    }

    @Inject(
        method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$suppressLocalSound(
        double x, double y, double z,
        SoundEvent sound, SoundSource source, float volume, float pitch, boolean distanceDelay,
        CallbackInfo ci
    ) {
        if (!enderkinesis$mirrorsChannel(source)) return;
        if (enderkinesis$insideAnyBubble(x, y, z)) ci.cancel();
    }

    @Inject(
        method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$suppressLevelEvent(
        @Nullable Player player, int eventId, BlockPos pos, int data, CallbackInfo ci
    ) {
        if (eventId < 1000 || eventId >= 3000) return;
        if (enderkinesis$insideAnyBubble(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) ci.cancel();
    }

    private boolean enderkinesis$insideAnyBubble(double x, double y, double z) {
        ClientLevel self = (ClientLevel) (Object) this;
        var tomeKind = VentriloquismTomeOrbBehavior.INSTANCE.getTomeKind();
        for (OrbOfLinkingBlockEntity orb : OrbOfLinkingClientRegistry.INSTANCE.sendOrbs(self)) {
            if (orb.outgoingPeers(tomeKind).isEmpty()) continue;
            Vec3 c = orb.worldCenter();
            if (c == null) continue;
            double dx = x - c.x;
            double dy = y - c.y;
            double dz = z - c.z;
            if (dx * dx + dy * dy + dz * dz <= VentriloquismNetwork.CAPTURE_RADIUS_SQ) return true;
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
