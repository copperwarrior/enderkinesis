package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.Slot;
import org.shipwrights.enderkinesis.duck.ImbueKeepingResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs on client and server, so the client copy of the menu agrees. */
@Mixin({AnvilMenu.class, GrindstoneMenu.class})
public abstract class ResultContainerImbueKeepMixin {

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V", at = @At("RETURN"))
    private void enderkinesis$keepImbues(CallbackInfo ci) {
        for (Slot slot : ((AbstractContainerMenu) (Object) this).slots) {
            if (slot.container instanceof ImbueKeepingResult result) result.enderkinesis$keepImbues();
        }
    }
}
