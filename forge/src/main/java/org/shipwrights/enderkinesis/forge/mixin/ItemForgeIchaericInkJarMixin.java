package org.shipwrights.enderkinesis.forge.mixin;

import java.util.function.Consumer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.shipwrights.enderkinesis.block.IchaericInkJarBlock;
import org.shipwrights.enderkinesis.forge.client.IchaericInkJarForgeExtensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The common item cannot name Forge's client extension type, so the renderer is attached here. */
@Mixin(Item.class)
public abstract class ItemForgeIchaericInkJarMixin {

    // initializeClient is a Forge patch, not vanilla, so there is nothing to remap.
    @Inject(method = "initializeClient", at = @At("HEAD"), cancellable = true, remap = false)
    private void enderkinesis$dispatchIchaericInkJarRenderer(
        Consumer<IClientItemExtensions> consumer,
        CallbackInfo ci
    ) {
        if ((Object) this instanceof BlockItem blockItem
            && blockItem.getBlock() instanceof IchaericInkJarBlock) {
            consumer.accept(IchaericInkJarForgeExtensions.INSTANCE);
            ci.cancel();
        }
    }
}
