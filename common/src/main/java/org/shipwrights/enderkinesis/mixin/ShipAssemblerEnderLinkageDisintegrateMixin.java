package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.shipwrights.enderkinesis.block.EnderLinkageBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.assembly.ShipAssembler;
import org.valkyrienskies.mod.common.assembly.ShipAssembler.AssembleContext;

import java.util.Set;

/**
 * Ender Linkage disintegrates after being transported into a ship. At assembly
 * HEAD we walk the input set and flip every linkage from {@code stable=true} to
 * {@code stable=false}; VS2's StructureTemplate copy preserves block state, so
 * the linkages arrive on the new ship already-unstable. Each unstable linkage's
 * {@code onPlace} schedules the first disintegration tick, and the block's
 * tick() handler uses a global one-per-tick gate so a cluster of N linkages
 * disintegrates one block at a time over N ticks.
 *
 * Targets the canonical {@code assembleToShipFull} — every other public
 * {@code assembleToShip} overload funnels through it, so one hook covers them all.
 */
@Mixin(ShipAssembler.class)
public abstract class ShipAssemblerEnderLinkageDisintegrateMixin {

    @Inject(method = "assembleToShipFull", at = @At("HEAD"))
    private static void enderkinesis$markLinkagesUnstable(
            ServerLevel level, Set<BlockPos> blocks, double scale,
            CallbackInfoReturnable<AssembleContext> cir) {
        for (BlockPos pos : blocks) {
            EnderLinkageBlock.markUnstable(level, pos);
        }
    }
}
