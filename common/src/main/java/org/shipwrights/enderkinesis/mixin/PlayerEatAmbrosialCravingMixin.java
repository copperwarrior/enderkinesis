package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.item.AmbrosialCravingScaling;
import org.shipwrights.enderkinesis.registry.EKEffects;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Brackets {@link Player#eat(Level, ItemStack)} with the
 * {@link AmbrosialCravingScaling} thread-local flag whenever the player
 * is under Ambrosial Craving. The two paired mixins
 * ({@code FoodDataAmbrosialCravingScaleMixin} and
 * {@code LivingEntityAddEatEffectAmbrosialCravingScaleMixin}) consult the
 * flag while {@code Player.eat} is in flight and scale every food value
 * by {@link AmbrosialCravingScaling#SCALE} (= 0.2×).
 *
 * <p>Wey'ye fruit itself is intentionally exempt: it's the source of the
 * curse and is supposed to still fully refill the bar.
 */
@Mixin(Player.class)
public abstract class PlayerEatAmbrosialCravingMixin {

    @Inject(
        method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD")
    )
    private void enderkinesis$enterCraving(Level level, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        Player self = (Player) (Object) this;
        if (!self.hasEffect(EKEffects.ambrosialCravingEffect())) return;
        if (stack.getItem() == EKItems.weyyeFruit()) return;
        AmbrosialCravingScaling.enter();
    }

    @Inject(
        method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("RETURN")
    )
    private void enderkinesis$exitCraving(Level level, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        // Always clear — no harm clearing when never set; cheap remove().
        AmbrosialCravingScaling.exit();
    }
}
