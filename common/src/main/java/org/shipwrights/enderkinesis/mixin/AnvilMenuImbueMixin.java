package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.MenuType;
import org.shipwrights.enderkinesis.spell.Imbues;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anything put in the anvil's second slot combines two stacks, so the result never carries
 *  an imbue. Renaming alone keeps it. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuImbueMixin extends ItemCombinerMenu {

    private AnvilMenuImbueMixin(MenuType<?> type, int id, Inventory inventory, ContainerLevelAccess access) {
        super(type, id, inventory, access);
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void enderkinesis$stripImbue(CallbackInfo ci) {
        if (inputSlots.getItem(1).isEmpty()) return;
        resultSlots.getItem(0).removeTagKey(Imbues.NBT);
    }
}
