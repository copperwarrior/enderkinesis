package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
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

    // Synched data reaches every tracking client, also players who start tracking later.
    @Unique
    private static final EntityDataAccessor<Float> enderkinesis$VEIN_GROWTH =
        SynchedEntityData.defineId(Player.class, EntityDataSerializers.FLOAT);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void enderkinesis$defineVeinGrowth(CallbackInfo ci) {
        ((Player) (Object) this).getEntityData().define(enderkinesis$VEIN_GROWTH, 0f);
    }

    @Override
    @Unique
    public float enderkinesis$veinGrowth() {
        return ((Player) (Object) this).getEntityData().get(enderkinesis$VEIN_GROWTH);
    }

    @Override
    @Unique
    public void enderkinesis$setVeinGrowth(float growth) {
        ((Player) (Object) this).getEntityData().set(enderkinesis$VEIN_GROWTH, growth);
    }

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
