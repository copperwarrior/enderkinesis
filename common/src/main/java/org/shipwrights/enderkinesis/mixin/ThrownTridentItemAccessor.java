package org.shipwrights.enderkinesis.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ThrownTrident.class)
public interface ThrownTridentItemAccessor {
    @Accessor("tridentItem")
    ItemStack enderkinesis$getTridentItem();

    @Accessor("ID_FOIL")
    static EntityDataAccessor<Boolean> enderkinesis$idFoil() {
        throw new AssertionError();
    }
}
