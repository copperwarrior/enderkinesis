package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.spell.BloodPacts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Bloodbound for players: {@code Player.actuallyHurt} replaces {@code LivingEntity}'s
 *  without calling it, so {@code LivingEntitySpellEffectsMixin} doesn't reach it. */
@Mixin(Player.class)
public abstract class PlayerSpellEffectsMixin {

    @ModifyVariable(method = "actuallyHurt", at = @At("HEAD"), argsOnly = true)
    private float enderkinesis$bloodbound(float amount) {
        return BloodPacts.redirect((LivingEntity) (Object) this, amount);
    }
}
