package org.shipwrights.enderkinesis.entity

import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import org.shipwrights.enderkinesis.EnderkinesisMod

val HAND_FEED_MEATS: TagKey<Item> = TagKey.create(Registries.ITEM, EnderkinesisMod.id("hand_feed_meats"))

/** Same rule as Animal.usePlayerItem, for mobs that are not an Animal. */
fun Player.useOneHeld(hand: InteractionHand) {
    if (!abilities.instabuild) getItemInHand(hand).shrink(1)
}
