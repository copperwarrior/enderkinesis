package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.shipwrights.enderkinesis.dimension.WeheloPocket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Creative destroyBlock returns true even when the block stays; false makes destroyAndAck send the client the real block.
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeWeheloPocketShellMixin {
    @Shadow protected ServerLevel level;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$keepPocketShell(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (WeheloPocket.isShellAt(level, pos)) cir.setReturnValue(false);
    }
}
