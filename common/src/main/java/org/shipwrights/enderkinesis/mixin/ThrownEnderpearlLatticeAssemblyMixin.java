package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Make a thrown ender pearl that impacts a crepusculite lattice trigger the same
 * flood-fill ship assembly as right-clicking the lattice with a pearl in-hand —
 * the pearl is the catalyst either way. The pearl is consumed (entity discarded)
 * and the usual teleport is suppressed, so throwing a pearl at the lattice can
 * never double-dip as both an assembly trigger AND a teleport.
 *
 * Owner-less pearls (dispensers) still work; they just don't get a chat-bar
 * message back.
 */
@Mixin(ThrownEnderpearl.class)
public abstract class ThrownEnderpearlLatticeAssemblyMixin {

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$assembleOnLatticeHit(HitResult hitResult, CallbackInfo ci) {
        if (!(hitResult instanceof BlockHitResult bhr) || bhr.getType() == HitResult.Type.MISS) return;
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        Level level = self.level();
        if (!(level instanceof ServerLevel server)) return;
        BlockPos pos = bhr.getBlockPos();
        BlockState state = server.getBlockState(pos);
        if (!(state.getBlock() instanceof CrepusculiteLatticeBlock)) return;

        CrepusculiteLatticeBlock.AssembleOutcome outcome =
            CrepusculiteLatticeBlock.assembleConnectedShip(server, pos);
        Entity owner = self.getOwner();
        if (owner instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.translatable(outcome.getMessageKey()), true);
        }
        self.discard();
        ci.cancel();
    }
}
