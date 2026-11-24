package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.duck.AntleredCrownHolder;
import org.shipwrights.enderkinesis.item.AntleredCrown;
import org.shipwrights.enderkinesis.item.WoodHearts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The setHealth call comes after armor, Forge's LivingHurt/LivingDamage events and absorption, the same on both loaders.
@Mixin(Player.class)
public abstract class PlayerAntleredCrownMixin implements AntleredCrownHolder {

    @Unique
    private static final String enderkinesis$WOOD_KEY = "enderkinesis:wood_hearts";

    @Unique
    private final WoodHearts enderkinesis$woodHearts = new WoodHearts();

    @Override
    @Unique
    public WoodHearts enderkinesis$woodHearts() {
        return enderkinesis$woodHearts;
    }

    @WrapOperation(
        method = "actuallyHurt",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setHealth(F)V")
    )
    private void enderkinesis$woodInsteadOfHealth(Player self, float health, Operation<Void> original,
                                                 @Local(argsOnly = true) DamageSource source) {
        original.call(self, AntleredCrown.healthAfterHit(self, source, health));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void enderkinesis$saveWood(CompoundTag tag, CallbackInfo ci) {
        if (enderkinesis$woodHearts.getWood() > 0f) tag.putFloat(enderkinesis$WOOD_KEY, enderkinesis$woodHearts.getWood());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void enderkinesis$loadWood(CompoundTag tag, CallbackInfo ci) {
        enderkinesis$woodHearts.load(tag.getFloat(enderkinesis$WOOD_KEY));
    }
}
