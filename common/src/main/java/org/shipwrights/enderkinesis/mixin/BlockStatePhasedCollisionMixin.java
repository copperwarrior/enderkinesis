package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.shipwrights.enderkinesis.spell.AbyssalPockets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A block phased by an Abyssal Pocket keeps its state but loses its collision on the server,
 *  so entities fall through it. Clients hide it by replacing it locally. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStatePhasedCollisionMixin {

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("HEAD"), cancellable = true)
    private void enderkinesis$phased(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        if (AbyssalPockets.isPhased(level, pos)) cir.setReturnValue(Shapes.empty());
    }

    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("HEAD"), cancellable = true)
    private void enderkinesis$phasedWithContext(BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (AbyssalPockets.isPhased(level, pos)) cir.setReturnValue(Shapes.empty());
    }
}
