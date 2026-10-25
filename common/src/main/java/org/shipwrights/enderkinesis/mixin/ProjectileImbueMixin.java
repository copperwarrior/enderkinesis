package org.shipwrights.enderkinesis.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.duck.ImbuedProjectile;
import org.shipwrights.enderkinesis.spell.Imbues;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives every projectile an imbued-spell slot that saves with it, and sets the spell off on
 *  the projectile's first impact. */
@Mixin(Projectile.class)
public abstract class ProjectileImbueMixin implements ImbuedProjectile {

    @Unique
    private @Nullable CompoundTag enderkinesis$imbue;

    @Override
    public @Nullable CompoundTag enderkinesis$getImbue() {
        return enderkinesis$imbue;
    }

    @Override
    public void enderkinesis$setImbue(@Nullable CompoundTag imbue) {
        enderkinesis$imbue = imbue;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void enderkinesis$saveImbue(CompoundTag tag, CallbackInfo ci) {
        if (enderkinesis$imbue != null) tag.put(Imbues.NBT, enderkinesis$imbue);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void enderkinesis$readImbue(CompoundTag tag, CallbackInfo ci) {
        enderkinesis$imbue = tag.contains(Imbues.NBT, Tag.TAG_COMPOUND) ? tag.getCompound(Imbues.NBT) : null;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void enderkinesis$imbueTick(CallbackInfo ci) {
        if (enderkinesis$imbue != null) Imbues.onProjectileTick((Projectile) (Object) this);
    }

    @Inject(method = "onHit", at = @At("HEAD"))
    private void enderkinesis$imbueHit(HitResult result, CallbackInfo ci) {
        if (enderkinesis$imbue != null) Imbues.onProjectileHit((Projectile) (Object) this, result);
    }
}
