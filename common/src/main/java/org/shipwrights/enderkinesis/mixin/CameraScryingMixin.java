package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.client.ScryingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Move the camera to the scrying target by TAIL-injecting {@code Camera.setup} and
 * overwriting the position vanilla just set. Rotation is left alone, so the player's
 * mouse aim is also the camera's aim — looking around at the source is looking around
 * through the target orb.
 *
 * <p>Also forces {@code Camera.detached = true}. Vanilla's {@code LevelRenderer.renderLevel}
 * skips drawing the camera-entity's model in first person via a guard equivalent to
 * {@code entity != camera.getEntity() || camera.isDetached() || sleeping}; since our
 * camera entity is still the local player (we deliberately don't call setCameraEntity),
 * that guard hides the player at the source unless detached is true. Forcing it on
 * here makes the player's body render when the camera at the target has it in frustum,
 * regardless of the user's actual first/third-person preference.
 *
 * <p>We deliberately do NOT use {@code Minecraft.setCameraEntity}: that path also flips
 * vanilla's server-side {@code ServerPlayer.camera}, which causes
 * {@code ServerGamePacketListenerImpl.handleMovePlayer} to route the client's move
 * packets to the camera entity instead of the player — leaving the player itself at
 * the distant location after the session ends, which persists across reload. Replacing
 * only the rendered position keeps player physics and persistence intact.
 */
@Mixin(Camera.class)
public abstract class CameraScryingMixin {

    @Shadow protected abstract void setPosition(double x, double y, double z);

    @Shadow private boolean detached;

    @Inject(method = "setup", at = @At("TAIL"))
    private void enderkinesis$overrideForScrying(
        BlockGetter level, Entity entity, boolean detachedParam, boolean thirdPersonReverse,
        float partialTick, CallbackInfo ci
    ) {
        // Per-frame state ticks. The actual refraction dispatch runs at the
        // TAIL of LevelRenderer.renderLevel (see LevelRendererBubblePassMixin),
        // not here — Camera.setup is too early in the frame for that.
        org.shipwrights.enderkinesis.client.ConcealmentTransitionWatcher.tick();
        org.shipwrights.enderkinesis.client.ConcealmentPostEffect.tick();
        if (!ScryingClient.isActive()) return;
        Vec3 target = ScryingClient.targetWorldPos();
        if (target == null) return;
        setPosition(target.x, target.y, target.z);
        // Force the renderer to treat this as a detached / third-person camera so the
        // local player's model isn't skipped by the first-person guard.
        this.detached = true;
    }
}
