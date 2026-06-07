package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses every arm-swing animation the local player would otherwise
 * play while holding the Wylland Tome in main hand. The tome is a
 * grab-and-rotate tool, not a melee weapon — every left-click is the
 * start of a grab gesture and every right-click is a repair gesture;
 * neither should produce a hit-swing animation.
 *
 * <p>The pre-existing {@link MinecraftWyllandTomeSwingMixin} suppresses
 * the swing from {@code Minecraft.startAttack()} on the click edge, but
 * {@code Minecraft.continueAttack(true)} is called every tick while the
 * attack key is held and routes through {@code
 * MultiPlayerGameMode.continueDestroyBlock → startDestroyBlock}, which
 * does NOT consult {@code Item.canAttackBlock} on its first tick — it
 * returns {@code true} and {@code continueAttack} then calls {@code
 * player.swing}. Cancelling at the {@code LocalPlayer.swing} entry
 * point catches that path AND any other source (server-driven {@code
 * ClientboundAnimatePacket}, future code paths) in one place.
 *
 * <p>Targeted only at {@link LocalPlayer} so other players or NPCs
 * around the local client can still play their normal swing animations
 * even if they happen to be rendered holding a Wylland Tome — the
 * filter on {@code Minecraft.getInstance().player == this} is just a
 * defensive identity guard. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerWyllandTomeNoSwingMixin {

    @Inject(
        method = "swing(Lnet/minecraft/world/InteractionHand;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$skipSwingWhileHoldingTome(
        InteractionHand hand, CallbackInfo ci
    ) {
        Object self = this;
        if (Minecraft.getInstance().player != self) return;
        LocalPlayer p = (LocalPlayer) self;
        if (p.getMainHandItem().getItem()
                == EKItems.INSTANCE.getWYLLAND_TOME().get()) {
            ci.cancel();
        }
    }
}
