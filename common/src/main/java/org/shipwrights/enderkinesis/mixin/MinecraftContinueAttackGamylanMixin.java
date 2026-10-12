package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.client.GamylanCageClientRegistry;
import org.shipwrights.enderkinesis.client.GamylanCageRaytrace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sibling of {@link MinecraftStartAttackGamylanMixin} for the hold-left-click path.
 * Vanilla {@code continueAttack} reads {@code Minecraft.hitResult} independently of
 * {@code startAttack} and calls {@code MultiPlayerGameMode.continueDestroyBlock} —
 * so cancelling {@code startAttack} alone lets the very next tick start mining the
 * block behind the cage. This intercepts each tick and, when a cage is in the ray,
 * calls {@code stopDestroyBlock} to reset any in-flight mine progress and short-
 * circuits vanilla so no new progress accumulates.
 *
 * <p>Same 5-block reach as the click path so a player who looks past the cage at
 * a distant block can still mine that block normally.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftContinueAttackGamylanMixin {

    private static final double MAX_REACH = 5.0;

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$stopMiningThroughCage(boolean pressed, CallbackInfo ci) {
        if (!pressed) return;
        Minecraft self = (Minecraft) (Object) this;
        LocalPlayer player = self.player;
        if (player == null || self.level == null) return;
        if (GamylanCageClientRegistry.INSTANCE.currentLevelEntries().isEmpty()) return;

        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        GamylanCageClientRegistry.Entry hit =
            GamylanCageRaytrace.INSTANCE.findCageOnRay(eye, look, MAX_REACH);
        if (hit == null) return;
        // A cage behind a wall or a mob must not take the click from it.
        if (GamylanCageRaytrace.vanillaTargetIsNearer(eye, self.hitResult)) return;

        // Kill any mine progress that was already accumulating on the block
        // behind the cage before the player turned to face it.
        self.gameMode.stopDestroyBlock();
        ci.cancel();
    }
}
