package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.shipwrights.enderkinesis.item.VanillaCommandables;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Virtual redstone signal for Staff-of-Command piston pulses. Vanilla
 * re-evaluates {@code getNeighborSignal} on every neighbour update AND inside
 * the extend block event — a forced extend without a real signal is instantly
 * reconciled back to retracted ({@code checkIfExtend} sees "extended, no
 * signal" the moment the head placement pokes the piston). Reporting a signal
 * while the pulse is registered makes every vanilla path — extend event, stay
 * extended, retract on expiry — behave exactly as if a lever were on.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockCommandSignalMixin {

    @Inject(method = "getNeighborSignal", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$virtualPulseSignal(
        SignalGetter level, BlockPos pos, Direction facing,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (VanillaCommandables.isPistonPulsed(level, pos)) {
            cir.setReturnValue(true);
        }
    }
}
