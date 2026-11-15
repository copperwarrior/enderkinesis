package org.shipwrights.enderkinesis.construction

import net.minecraft.world.level.block.state.properties.Property

/** A block whose listed power properties the player sets, so a built plan keeps their saved values. */
interface PlayerSetPower {
    val playerSetPower: Set<Property<*>>
}
