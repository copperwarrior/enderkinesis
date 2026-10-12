package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.shipwrights.enderkinesis.block.HeartOfTheWildBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;

import java.util.Set;

// Each public assembleToShip overload goes through assembleToShipFull.
@Mixin(value = ShipAssembler.class, remap = false)
public abstract class ShipAssemblerMotherHeartMixin {

    @ModifyVariable(method = "assembleToShipFull", at = @At("HEAD"), argsOnly = true)
    private static Set<BlockPos> enderkinesis$leaveMotherHeart(Set<BlockPos> blocks, ServerLevel level) {
        return HeartOfTheWildBlock.withoutMotherHeart(level, blocks);
    }
}
