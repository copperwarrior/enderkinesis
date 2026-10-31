package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.item.MysticScrollItem;
import org.shipwrights.enderkinesis.spell.BloodPacts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bloodbound for players: {@code Player.actuallyHurt} replaces {@code LivingEntity}'s
 *  without calling it, so {@code LivingEntitySpellEffectsMixin} doesn't reach it. And a
 *  scroll dropped mid-cast (thrown, or dropped on death) fizzles as it leaves the hand. */
@Mixin(Player.class)
public abstract class PlayerSpellEffectsMixin {

    @ModifyVariable(method = "actuallyHurt", at = @At("HEAD"), argsOnly = true)
    private float enderkinesis$bloodbound(float amount) {
        return BloodPacts.redirect((LivingEntity) (Object) this, amount);
    }

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"))
    private void enderkinesis$fizzleDroppedCast(ItemStack stack, boolean dropAround, boolean includeThrowerName, CallbackInfoReturnable<ItemEntity> cir) {
        MysticScrollItem.Companion.dropped((Player) (Object) this, stack);
    }
}
