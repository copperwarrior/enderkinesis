package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.client.ScryingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Center the renderer's {@link net.minecraft.client.renderer.ViewArea} chunk grid on
 * the scrying target rather than the player's actual position.
 *
 * <p>Vanilla {@code LevelRenderer.setupRender} reads {@code mc.player.getX/Y/Z()} and
 * passes them to {@code viewArea.repositionCamera(playerX, playerZ)} — the chunk-grid
 * "centre" tracks the player entity, not the {@link net.minecraft.client.Camera}.
 * Our pose-override moves the rendered camera to the target, but the player entity
 * stays at the source orb (intentionally — that's what protects the player's saved
 * position from move-packet drift). The net effect is: {@code RenderSection}s stay
 * arrayed around the source area and never query target-area chunks, so the parallel
 * camera storage never gets touched by the actual block-render pipeline.
 *
 * <p>Wrapping the three {@code LocalPlayer.getX/Y/Z()} calls in {@code setupRender}
 * substitutes the scrying target's world position when a session is live. The grid
 * recentres to the target, {@code RenderSection.setOrigin} re-aligns every section to
 * a camera-area chunk position, and the next compile pulls those chunks from camera
 * storage through the {@code ClientChunkCache} mixin's fallback. Source rendering is
 * paused for the duration of the session — fine because the camera is at the target
 * and the player can't see the source area anyway.
 */
// priority = 1100 — Sodium's WorldRendererMixin merges code into
// `setupRender` at default priority 1000, and Mixin refuses to inject
// into a merged method unless the injecting mixin has STRICTLY higher
// priority. Without the bump the game crashes during class load.
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class LevelRendererScryingMixin {

    @WrapOperation(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D")
    )
    private double enderkinesis$cameraXForViewArea(LocalPlayer player, Operation<Double> original) {
        Vec3 target = ScryingClient.isActive() ? ScryingClient.targetWorldPos() : null;
        return target != null ? target.x : original.call(player);
    }

    @WrapOperation(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D")
    )
    private double enderkinesis$cameraYForViewArea(LocalPlayer player, Operation<Double> original) {
        Vec3 target = ScryingClient.isActive() ? ScryingClient.targetWorldPos() : null;
        return target != null ? target.y : original.call(player);
    }

    @WrapOperation(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D")
    )
    private double enderkinesis$cameraZForViewArea(LocalPlayer player, Operation<Double> original) {
        Vec3 target = ScryingClient.isActive() ? ScryingClient.targetWorldPos() : null;
        return target != null ? target.z : original.call(player);
    }
}
