package org.shipwrights.enderkinesis.forge.mixin;

import java.util.function.Consumer;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.shipwrights.enderkinesis.item.StatueBlockItem;
import org.shipwrights.enderkinesis.forge.client.StatueForgeExtensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge-only mixin that wires {@link StatueForgeExtensions} onto every
 * {@link StatueBlockItem} — one mixin covers all statues. Same HEAD inject + {@code remap=false}
 * pattern as {@code ItemForgeMagicMissileMixin}.
 */
@Mixin(Item.class)
public abstract class ItemForgeStatueMixin {

    @Inject(
        method = "initializeClient",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void enderkinesis$dispatchStatueRenderer(
        Consumer<IClientItemExtensions> consumer,
        CallbackInfo ci
    ) {
        // Runs from the Item constructor, before BlockItem.block is set; test the class.
        if ((Object) this instanceof StatueBlockItem) {
            consumer.accept(StatueForgeExtensions.INSTANCE);
            ci.cancel();
        }
    }
}
