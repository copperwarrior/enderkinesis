package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.shipwrights.enderkinesis.util.ShipMoves;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.assembly.ShipWelder;

// ShipWelder.Plan is private, so each handler coerces the plan to Object.
@Mixin(value = ShipWelder.class, remap = false)
public class ShipWelderMoveMixin {

    @Inject(method = "merge", at = @At("HEAD"))
    private void enderkinesis$beginWeld(ServerLevel level, LoadedServerShip target, BlockPos targetPos,
                                        Direction targetFace, LoadedServerShip source, BlockPos sourcePos,
                                        @Coerce Object plan, CallbackInfoReturnable<String> cir) {
        ShipMoves.beginWeld(level, plan);
    }

    @Inject(method = "merge", at = @At("RETURN"))
    private void enderkinesis$endWeld(ServerLevel level, LoadedServerShip target, BlockPos targetPos,
                                      Direction targetFace, LoadedServerShip source, BlockPos sourcePos,
                                      @Coerce Object plan, CallbackInfoReturnable<String> cir) {
        ShipMoves.endMove();
    }

    @Inject(
        method = "merge",
        at = @At(
            value = "INVOKE",
            target = "Lorg/valkyrienskies/core/internal/world/VsiServerShipWorld;deleteShip(Lorg/valkyrienskies/core/api/ships/ServerShip;)V"
        )
    )
    private void enderkinesis$recordWeld(ServerLevel level, LoadedServerShip target, BlockPos targetPos,
                                         Direction targetFace, LoadedServerShip source, BlockPos sourcePos,
                                         @Coerce Object plan, CallbackInfoReturnable<String> cir) {
        ShipMoves.onWeld(level, target, targetPos, targetFace, source, sourcePos, plan);
    }
}
