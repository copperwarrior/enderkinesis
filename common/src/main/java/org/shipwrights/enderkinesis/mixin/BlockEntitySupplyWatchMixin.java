package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.shipwrights.enderkinesis.construction.SupplyWatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Container.setChanged is an interface method, so the hook sits on the BlockEntity class that implements it. */
@Mixin(BlockEntity.class)
public class BlockEntitySupplyWatchMixin {

    // A ModifyArg on the static call, not an Inject: setChanged runs constantly, and this makes no CallbackInfo.
    @ModifyArg(
        method = "setChanged()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setChanged(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"),
        index = 0
    )
    private Level enderkinesis$wakeBuildSupply(Level level) {
        SupplyWatch.blockEntityChanged((BlockEntity) (Object) this);
        return level;
    }
}
