package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.shipwrights.enderkinesis.construction.ClawhodLids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The recheck counts only players with the menu open; Clawhod hands taking from the container count too. */
@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersCounterClawhodMixin {

    // ModifyReturnValue, not a cancellable Inject: no CallbackInfoReturnable or boxed Integer on each recheck.
    @ModifyReturnValue(method = "getOpenCount(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"))
    private int enderkinesis$countClawhodHands(int original, Level level, BlockPos pos) {
        return original + ClawhodLids.clawhodHandsAt(level, pos);
    }
}
