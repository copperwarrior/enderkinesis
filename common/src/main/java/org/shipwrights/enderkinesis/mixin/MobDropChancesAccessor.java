package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Mob.class)
public interface MobDropChancesAccessor {

    @Accessor("handDropChances")
    float[] enderkinesis$getHandDropChances();

    @Accessor("armorDropChances")
    float[] enderkinesis$getArmorDropChances();
}
