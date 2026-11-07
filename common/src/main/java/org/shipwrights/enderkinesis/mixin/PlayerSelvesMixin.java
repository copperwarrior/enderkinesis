package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.ritual.Selves;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerSelvesMixin {

    @ModifyReturnValue(method = "getDimensions", at = @At("RETURN"))
    private EntityDimensions enderkinesis$selvesSize(EntityDimensions original, Pose pose) {
        return Selves.dimensions((Player) (Object) this, original);
    }

    @ModifyReturnValue(method = "getStandingEyeHeight", at = @At("RETURN"))
    private float enderkinesis$selvesEyes(float original, Pose pose, EntityDimensions dimensions) {
        return Selves.eyeHeight((Player) (Object) this, original);
    }
}
