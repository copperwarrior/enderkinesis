package org.shipwrights.enderkinesis.forge.mixin;

import java.util.function.Consumer;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.shipwrights.enderkinesis.forge.client.WyllandTomeForgeExtensions;
import org.shipwrights.enderkinesis.item.WyllandTomeItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge-only mixin that wires {@link WyllandTomeForgeExtensions}
 * onto the Wylland Tome item. The common-side
 * {@code WyllandTomeItem} class can't reference
 * {@code IClientItemExtensions} directly (it's a Forge-added
 * extension to {@code Item}, missing from Fabric's Item), so we
 * intercept the base {@code Item.initializeClient} at HEAD,
 * dispatch only for instances of {@code WyllandTomeItem}, and
 * cancel to short-circuit any super-class default.
 *
 * <p>{@code remap = false} on the {@code @Inject} because
 * {@code initializeClient} is a Forge patch, not a vanilla
 * obfuscation target — leaving remap on would have the mixin
 * processor look for an SRG-mapped name that doesn't exist.
 */
@Mixin(Item.class)
public abstract class ItemForgeWyllandTomeMixin {

    @Inject(
        method = "initializeClient",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void enderkinesis$dispatchWyllandTomeRenderer(
        Consumer<IClientItemExtensions> consumer,
        CallbackInfo ci
    ) {
        if ((Object) this instanceof WyllandTomeItem) {
            consumer.accept(WyllandTomeForgeExtensions.INSTANCE);
            ci.cancel();
        }
    }
}
