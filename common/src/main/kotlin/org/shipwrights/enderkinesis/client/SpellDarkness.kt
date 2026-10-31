package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.spell.DarkZones

/**
 * The client's end of [DarkZones]: holds zones in the client's world, expires them each client
 * tick, and has the chunks under a zone rebuilt with their new light when it appears, changes
 * or goes.
 */
object SpellDarkness {
    fun init() {
        DarkZones.onClientChange = ::dirty
        ClientTickEvent.CLIENT_POST.register { DarkZones.clientTick(Minecraft.getInstance().level != null) }
    }

    /** Keeps the zone [key] standing in the client's world this tick (see [DarkZones.hold]). */
    fun hold(key: Any, centre: Vec3, radius: Double, levels: Int) {
        val level = Minecraft.getInstance().level ?: return
        DarkZones.hold(level, key, centre, radius, levels)
    }

    /** Has the chunks under [zone] rebuilt with their new light. */
    private fun dirty(zone: DarkZones.Zone) {
        val r = Math.ceil(zone.radius).toInt() + 1
        val x = Mth.floor(zone.x); val y = Mth.floor(zone.y); val z = Mth.floor(zone.z)
        Minecraft.getInstance().levelRenderer.setBlocksDirty(x - r, y - r, z - r, x + r, y + r, z + r)
    }
}
