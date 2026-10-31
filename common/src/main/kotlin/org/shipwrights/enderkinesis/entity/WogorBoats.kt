package org.shipwrights.enderkinesis.entity

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.entity.vehicle.ChestBoat
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.registry.EKItems

// Boat.Type is a closed enum, so the wogor boats are their own entity types; the variant stays OAK.
class WogorBoat(type: EntityType<out Boat>, level: Level) : Boat(type, level) {
    override fun getDropItem(): Item = EKItems.WOGOR_BOAT.get()
}

class WogorChestBoat(type: EntityType<out Boat>, level: Level) : ChestBoat(type, level) {
    override fun getDropItem(): Item = EKItems.WOGOR_CHEST_BOAT.get()
}
