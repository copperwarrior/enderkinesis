package org.shipwrights.enderkinesis.mixin;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.shipwrights.enderkinesis.client.SlotClipping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The scissor stays on after renderSlot so the hover highlight drawn next is clipped too.
 * Forge draws that highlight through its own overload, so this does not hook the highlight.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenSlotClipMixin {

    @Unique
    private ScreenRectangle enderkinesis$slotClip;

    @Inject(method = "renderSlot", at = @At("HEAD"))
    private void enderkinesis$clipSlot(GuiGraphics graphics, Slot slot, CallbackInfo ci) {
        ScreenRectangle wanted = (Object) this instanceof SlotClipping clipping ? clipping.slotClip(slot) : null;
        if (Objects.equals(wanted, enderkinesis$slotClip)) return;
        if (enderkinesis$slotClip != null) graphics.disableScissor();
        if (wanted != null) graphics.enableScissor(wanted.left(), wanted.top(), wanted.right(), wanted.bottom());
        enderkinesis$slotClip = wanted;
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void enderkinesis$closeSlotClip(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (enderkinesis$slotClip == null) return;
        graphics.disableScissor();
        enderkinesis$slotClip = null;
    }
}
