package org.shipwrights.enderkinesis.forge.mixin;

import java.util.function.Consumer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.shipwrights.enderkinesis.block.StatueBlock;
import org.shipwrights.enderkinesis.forge.client.StatueForgeExtensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge-only mixin that wires {@link StatueForgeExtensions} onto every BlockItem
 * whose block is a {@link StatueBlock} — one mixin covers all 7 statues without
 * needing a custom Item subclass per kind. Same HEAD inject + {@code remap=false}
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
        if ((Object) this instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof StatueBlock) {
            consumer.accept(StatueForgeExtensions.INSTANCE);
            ci.cancel();
        }
    }
}
