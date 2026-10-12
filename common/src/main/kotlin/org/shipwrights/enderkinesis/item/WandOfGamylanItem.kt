package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.Item

/** Wand of Ga'Mylan — right-click on a living entity to capture it.
 *  Capture is routed through [InteractionEvent.INTERACT_ENTITY] in
 *  [GamylanCageManager], so it preempts every entity's default
 *  right-click behaviour (villager trade UI, horse mount, etc.) — the
 *  override happens before vanilla even calls `entity.interact`.
 *  An [Item.interactLivingEntity] override would not be sufficient:
 *  some interactions bypass that path. */
class WandOfGamylanItem(properties: Properties) : Item(properties)
