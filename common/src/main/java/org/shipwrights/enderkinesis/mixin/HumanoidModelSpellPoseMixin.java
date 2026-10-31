package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.client.SpellArmPoses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Mystic Scroll's arm motions in third person ({@link SpellArmPoses}), over vanilla's pose.
 *  On {@code HumanoidModel}, so sleeves and armour, copied from the arms afterwards, follow. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelSpellPoseMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void enderkinesis$spellPose(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                        float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (entity instanceof Player player) SpellArmPoses.pose((HumanoidModel<?>) (Object) this, player, ageInTicks);
    }
}
