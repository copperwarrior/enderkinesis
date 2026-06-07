package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootingEnchantFunction;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import org.shipwrights.enderkinesis.item.DisintegrationLootingOverride;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects {@link org.shipwrights.enderkinesis.item.DisintegrationTomeOrbBehavior}'s
 * Looting level into vanilla loot-table rolls without needing a synthetic killer entity.
 *
 * <p>Vanilla {@link LootingEnchantFunction#run(ItemStack, LootContext)} pulls the Looting
 * level from {@code KILLER_ENTITY.mainHandItem}, which is empty for our entity-less beam.
 * Before our beam's killing-blow {@code hurt()} call, the disintegration code sets the
 * level on {@link DisintegrationLootingOverride}; this mixin reads it at the function's
 * head and applies the same vanilla math (one guaranteed-grow plus a fractional-chance
 * extra) using our injected level instead of the killer-entity-based lookup.
 *
 * <p>When the override is unset (any non-disintegration loot roll), the mixin no-ops and
 * vanilla's original {@code run} runs unchanged — so this doesn't affect normal mob death
 * drops anywhere else in the game.
 */
@Mixin(LootingEnchantFunction.class)
public abstract class LootingEnchantFunctionDisintegrationMixin {

    @Shadow
    @Final
    private NumberProvider value;

    @Inject(method = "run", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$applyDisintegrationLootingOverride(
        ItemStack stack, LootContext context, CallbackInfoReturnable<ItemStack> cir
    ) {
        Integer override = DisintegrationLootingOverride.get();
        if (override == null || override <= 0) return;
        // Mirror vanilla LootingEnchantFunction.run math with our injected level.
        float bonus = override.intValue() * this.value.getFloat(context);
        stack.grow(Math.round(bonus));
        if (context.getRandom().nextFloat() < bonus - (int) bonus) {
            stack.grow(1);
        }
        cir.setReturnValue(stack);
    }
}
