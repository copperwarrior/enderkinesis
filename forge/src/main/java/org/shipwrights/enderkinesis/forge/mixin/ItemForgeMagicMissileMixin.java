package org.shipwrights.enderkinesis.forge.mixin;

import java.util.function.Consumer;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.shipwrights.enderkinesis.forge.client.MagicMissileForgeExtensions;
import org.shipwrights.enderkinesis.item.MagicMissileItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge-only mixin that wires {@link MagicMissileForgeExtensions} onto the Magic Missile
 * item. Same pattern as {@code ItemForgeWyllandTomeMixin} — see that mixin for the
 * reasoning behind the HEAD inject + {@code remap=false} on the Forge-patched
 * {@code initializeClient} method.
 */
@Mixin(Item.class)
public abstract class ItemForgeMagicMissileMixin {

    @Inject(
        method = "initializeClient",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void enderkinesis$dispatchMagicMissileRenderer(
        Consumer<IClientItemExtensions> consumer,
        CallbackInfo ci
    ) {
        if ((Object) this instanceof MagicMissileItem) {
            consumer.accept(MagicMissileForgeExtensions.INSTANCE);
            ci.cancel();
        }
    }
}
