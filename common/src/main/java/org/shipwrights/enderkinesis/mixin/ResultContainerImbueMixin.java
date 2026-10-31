package org.shipwrights.enderkinesis.mixin;

import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.duck.ImbueKeepingResult;
import org.shipwrights.enderkinesis.spell.Imbues;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Vanilla results copy the input NBT, and with it the imbue.
 *  Put a stripped copy in the slot, not a changed stack, because a mod can give one of its inputs. */
@Mixin(ResultContainer.class)
public abstract class ResultContainerImbueMixin implements ImbueKeepingResult {

    @Unique
    private boolean enderkinesis$keepsImbues;

    @Override
    public void enderkinesis$keepImbues() {
        enderkinesis$keepsImbues = true;
    }

    @ModifyVariable(method = "setItem", at = @At("HEAD"), argsOnly = true)
    private ItemStack enderkinesis$stripImbue(ItemStack stack) {
        if (enderkinesis$keepsImbues || stack.getTag() == null || !stack.getTag().contains(Imbues.NBT, Tag.TAG_COMPOUND)) return stack;
        ItemStack stripped = stack.copy();
        stripped.removeTagKey(Imbues.NBT);
        return stripped;
    }
}
