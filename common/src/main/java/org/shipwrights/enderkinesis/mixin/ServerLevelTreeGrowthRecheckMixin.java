package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.block.WohlonnogondoniaTreeGrower;
import org.shipwrights.enderkinesis.duck.TreeJobsHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelTreeGrowthRecheckMixin implements TreeJobsHolder {

    @Unique
    private Object enderkinesis$treeJobs;

    @Override
    public Object enderkinesis$treeJobs() {
        return enderkinesis$treeJobs;
    }

    @Override
    public void enderkinesis$setTreeJobs(Object data) {
        enderkinesis$treeJobs = data;
    }

    // Level.setBlock (Forge: markAndNotifyBlock) calls this once for each block state that changed.
    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void enderkinesis$treeGrowthRecheck(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        WohlonnogondoniaTreeGrower.onBlockChanged((ServerLevel) (Object) this, pos);
    }
}
