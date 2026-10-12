package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityDropEquipmentInvoker {

    @Invoker("dropEquipment")
    void enderkinesis$dropEquipment();
}
