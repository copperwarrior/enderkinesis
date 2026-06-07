package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.dimension.AlmanacData;
import org.shipwrights.enderkinesis.item.AlmanacOfEverywhereItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stamp a dropped Almanac item-entity with the dimensions it travels through. Companion
 * to {@code AlmanacOfEverywhereItem.inventoryTick}, which handles inventory-held almanacs.
 */
@Mixin(Entity.class)
public class EntityChangeDimensionMixin {

    @Inject(method = "changeDimension", at = @At("HEAD"))
    private void enderkinesis$stampOrigin(ServerLevel destination, CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            if (stack.getItem() instanceof AlmanacOfEverywhereItem) {
                AlmanacData.INSTANCE.addVisited(stack, self.level().dimension().location());
            }
        }
    }

    @Inject(method = "changeDimension", at = @At("RETURN"))
    private void enderkinesis$stampDestination(ServerLevel destination, CallbackInfoReturnable<Entity> cir) {
        if (cir.getReturnValue() instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            if (stack.getItem() instanceof AlmanacOfEverywhereItem) {
                AlmanacData.INSTANCE.addVisited(stack, destination.dimension().location());
            }
        }
    }
}
