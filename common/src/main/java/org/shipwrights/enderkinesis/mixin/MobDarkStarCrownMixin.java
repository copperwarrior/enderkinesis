package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.shipwrights.enderkinesis.item.CrownOfTheDarkStar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Mob.class)
public abstract class MobDarkStarCrownMixin {

    // Some callers (zombie reinforcements, raids, pack calls) set the target without a TargetingConditions check.
    @ModifyVariable(method = "setTarget", at = @At("HEAD"), argsOnly = true)
    private LivingEntity enderkinesis$crownUntargetable(LivingEntity target) {
        return CrownOfTheDarkStar.isWorn(target) ? null : target;
    }
}
