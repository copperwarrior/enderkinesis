package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.shipwrights.enderkinesis.client.IncubationGlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenIncubationGlowMixin {

    @Inject(method = "renderEntityInInventory", at = @At("TAIL"))
    private static void enderkinesis$incubationGlow(
        GuiGraphics graphics, int x, int y, int scale, Quaternionf pose, Quaternionf camera, LivingEntity entity,
        CallbackInfo ci
    ) {
        IncubationGlow.render(graphics, x, y, scale, pose, entity);
    }
}
