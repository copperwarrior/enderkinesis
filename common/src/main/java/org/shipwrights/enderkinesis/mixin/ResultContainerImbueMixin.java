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

/**
 * No crafting, smithing, stonecutting or cartography result carries an imbue. Vanilla's book
 * and map cloning, shield banners, armour dye and smithing all copy the input's NBT into the
 * result, which would copy the imbue too (and a cloned map stack splits into two). Anvils and
 * grindstones mark their result containers as keeping imbues (see
 * {@link ResultContainerImbueKeepMixin}); they strip merges themselves.
 *
 * <p>The stack put in the slot is replaced with a stripped copy rather than changed in place,
 * in case a mod hands over one of its inputs.
 */
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
