package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.spell.SpellDeaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mob a spell kills keeps its body as long as its death takes ({@link SpellDeaths}), and
 *  only goes in vanilla's white puff when that's how it dies. */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySpellDeathMixin {

    @ModifyConstant(method = "tickDeath", constant = @Constant(intValue = 20))
    private int enderkinesis$deathTicks(int vanilla) {
        return SpellDeaths.deathTicks((LivingEntity) (Object) this, vanilla);
    }

    @Inject(method = "makePoofParticles", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noPoof(CallbackInfo ci) {
        if (!SpellDeaths.keepsPoof((LivingEntity) (Object) this)) ci.cancel();
    }
}
