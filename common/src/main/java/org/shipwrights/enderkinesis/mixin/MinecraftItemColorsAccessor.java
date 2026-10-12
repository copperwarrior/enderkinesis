package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read handle for {@code Minecraft.itemColors} (private, no vanilla getter) —
 * the Ga'Mylan item-cage tint derivation queries layer tints for dynamically
 * coloured items (spawn eggs, potions, tipped arrows, dyed gear).
 */
@Mixin(Minecraft.class)
public interface MinecraftItemColorsAccessor {

    @Accessor("itemColors")
    ItemColors enderkinesis$getItemColors();
}
