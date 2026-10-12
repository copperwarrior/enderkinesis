package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.dimension.Sureibjin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Companion to {@link LivingEntitySureibjinSwimMixin}. In the swim pose, {@code Player.travel} lifts
 * the player along the look direction, which does not go through {@code jumpInLiquid}.
 * Runs on the client and the server, so it is in the common list.
 */
@Mixin(Player.class)
public abstract class PlayerSureibjinSwimMixin {

    @ModifyExpressionValue(
        method = "travel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getLookAngle()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 enderkinesis$noSwimUpInSureibjin(Vec3 look) {
        return Sureibjin.swimLook((Player) (Object) this, look);
    }
}
