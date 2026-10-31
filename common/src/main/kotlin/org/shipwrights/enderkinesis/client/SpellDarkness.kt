package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.spell.DarkZones

object SpellDarkness {
    fun init() {
        DarkZones.onClientChange = ::dirty
        ClientTickEvent.CLIENT_POST.register { DarkZones.clientTick(Minecraft.getInstance().level != null) }
    }

    fun hold(key: Any, centre: Vec3, radius: Double, levels: Int) {
        val level = Minecraft.getInstance().level ?: return
        DarkZones.hold(level, key, centre, radius, levels)
    }

    private fun dirty(zone: DarkZones.Zone) {
        val r = Math.ceil(zone.radius).toInt() + 1
        val x = Mth.floor(zone.x); val y = Mth.floor(zone.y); val z = Mth.floor(zone.z)
        Minecraft.getInstance().levelRenderer.setBlocksDirty(x - r, y - r, z - r, x + r, y + r, z + r)
    }
}
