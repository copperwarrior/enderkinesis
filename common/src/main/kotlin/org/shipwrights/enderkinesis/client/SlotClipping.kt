package org.shipwrights.enderkinesis.client

import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.world.inventory.Slot

/** A container screen that scissors some of its slots; read by `AbstractContainerScreenSlotClipMixin`. */
interface SlotClipping {
    /** Absolute screen rectangle to clip [slot] to, or null to draw it unclipped. */
    fun slotClip(slot: Slot): ScreenRectangle?
}
