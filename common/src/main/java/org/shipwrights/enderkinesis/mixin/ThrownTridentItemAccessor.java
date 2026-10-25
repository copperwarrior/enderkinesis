package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The trident a thrown trident gives back, so its imbue can be stripped once the throw
 *  carries it. */
@Mixin(ThrownTrident.class)
public interface ThrownTridentItemAccessor {
    @Accessor("tridentItem")
    ItemStack enderkinesis$getTridentItem();
}
