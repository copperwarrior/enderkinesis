package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArmorItem;
import org.shipwrights.enderkinesis.item.CrownItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;

// hurtArmor wears down only an ArmorItem. The crowns are not one, but they wear like a helmet.
@Mixin(Inventory.class)
public abstract class InventoryCrownWearMixin {

    @WrapOperation(method = "hurtArmor", constant = @Constant(classValue = ArmorItem.class))
    private boolean enderkinesis$crownsWearLikeHelmets(Object item, Operation<Boolean> original) {
        return original.call(item) || item instanceof CrownItem;
    }
}
