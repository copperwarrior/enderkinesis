package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.dimension.WeheloPocket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The client checks this before it predicts a break, as it checks the world border; else creative breaks leave ghost air.
@Mixin(Player.class)
public abstract class PlayerWeheloPocketShellMixin {
    @Inject(method = "blockActionRestricted", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$restrictPocketShell(Level level, BlockPos pos, GameType mode, CallbackInfoReturnable<Boolean> cir) {
        if (WeheloPocket.isShellAt(level, pos)) cir.setReturnValue(true);
    }
}
