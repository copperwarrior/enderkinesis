package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.shipwrights.enderkinesis.item.MysticScrollItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A scroll being cast is held as any item is, not drawn like a bow: the cast's own arm motion
 *  ({@code SpellArmPoses}) starts from the plain item-holding arms. */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererSpellArmPoseMixin {

    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private static void enderkinesis$scrollHeldPlain(AbstractClientPlayer player, InteractionHand hand,
                                                     CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        if (player.isUsingItem() && player.getUsedItemHand() == hand && player.getItemInHand(hand).getItem() instanceof MysticScrollItem) {
            cir.setReturnValue(HumanoidModel.ArmPose.ITEM);
        }
    }
}
