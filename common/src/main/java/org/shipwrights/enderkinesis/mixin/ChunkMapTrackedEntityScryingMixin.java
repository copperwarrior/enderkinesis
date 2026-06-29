package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.scrying.ScryingSessionManager;
import org.shipwrights.enderkinesis.scrying.ScryingSessionManager.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps {@code player.position()} inside {@code ChunkMap$TrackedEntity.updatePlayer} during
 * an active scrying session so vanilla measures the entity-in-range distance from the
 * camera, not the source-orb-bound viewer — entities near the camera get added to
 * {@code seenBy} and packets flow; source-area entities go client-side-stale until the
 * session ends, which is fine because the viewer looks through the camera.
 *
 * <p>Gating on the session manager (not {@code ServerPlayer.camera}) is deliberate:
 * vanilla's move-packet handler routes WASD through {@code camera}, so writing it would
 * persist the player at the camera location after the session ends.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackedEntityScryingMixin {

    @WrapOperation(
        method = "updatePlayer",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;")
    )
    private Vec3 enderkinesis$useScryingCameraForDistance(
        ServerPlayer player, Operation<Vec3> original
    ) {
        Session session = ScryingSessionManager.INSTANCE.activeSession(player);
        if (session != null) {
            return session.getTargetWorldPos();
        }
        return original.call(player);
    }
}
