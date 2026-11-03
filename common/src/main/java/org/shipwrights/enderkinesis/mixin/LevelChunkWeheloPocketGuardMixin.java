package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.dimension.WeheloPocket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Every block change in a loaded chunk passes here, so commands, explosions and other mods cannot touch a pocket shell.
@Mixin(LevelChunk.class)
public abstract class LevelChunkWeheloPocketGuardMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$guardPocketShell(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        // Server only: the client must accept the shell blocks the server sends.
        if (!level.isClientSide && WeheloPocket.isShellAt(level, pos)) {
            cir.setReturnValue(null);
        }
    }
}
