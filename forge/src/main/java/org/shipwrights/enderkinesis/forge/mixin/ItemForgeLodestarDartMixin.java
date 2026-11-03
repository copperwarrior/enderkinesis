package org.shipwrights.enderkinesis.forge.mixin;

import java.util.function.Consumer;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.shipwrights.enderkinesis.forge.client.LodestarDartForgeExtensions;
import org.shipwrights.enderkinesis.item.LodestarDartItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge-only mixin that wires {@link LodestarDartForgeExtensions} onto the Lodestar Dart
 * item. Same pattern as {@code ItemForgeWyllandTomeMixin} — see that mixin for the
 * reasoning behind the HEAD inject + {@code remap=false} on the Forge-patched
 * {@code initializeClient} method.
 */
@Mixin(Item.class)
public abstract class ItemForgeLodestarDartMixin {

    @Inject(
        method = "initializeClient",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void enderkinesis$dispatchLodestarDartRenderer(
        Consumer<IClientItemExtensions> consumer,
        CallbackInfo ci
    ) {
        if ((Object) this instanceof LodestarDartItem) {
            consumer.accept(LodestarDartForgeExtensions.INSTANCE);
            ci.cancel();
        }
    }
}
