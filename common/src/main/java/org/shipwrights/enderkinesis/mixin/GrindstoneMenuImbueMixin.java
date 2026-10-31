package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.GrindstoneMenu;
import org.shipwrights.enderkinesis.spell.Imbues;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Grinding a single item is not a merge, so it keeps the imbue. */
@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneMenuImbueMixin {

    @Shadow @Final Container repairSlots;
    @Shadow @Final private Container resultSlots;

    @Inject(method = "createResult", at = @At("TAIL"))
    private void enderkinesis$stripImbue(CallbackInfo ci) {
        if (repairSlots.getItem(0).isEmpty() || repairSlots.getItem(1).isEmpty()) return;
        resultSlots.getItem(0).removeTagKey(Imbues.NBT);
    }
}
