package org.shipwrights.enderkinesis.forge.mixin;

import java.util.function.Consumer;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.shipwrights.enderkinesis.forge.client.QuasarOfPurityForgeExtensions;
import org.shipwrights.enderkinesis.item.QuasarOfPurityBlockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Item.class)
public abstract class ItemForgeQuasarOfPurityMixin {

    // initializeClient is a Forge patch, not vanilla, so there is nothing to remap.
    @Inject(method = "initializeClient", at = @At("HEAD"), cancellable = true, remap = false)
    private void enderkinesis$dispatchQuasarOfPurityRenderer(
        Consumer<IClientItemExtensions> consumer,
        CallbackInfo ci
    ) {
        if ((Object) this instanceof QuasarOfPurityBlockItem) {
            consumer.accept(QuasarOfPurityForgeExtensions.INSTANCE);
            ci.cancel();
        }
    }
}
