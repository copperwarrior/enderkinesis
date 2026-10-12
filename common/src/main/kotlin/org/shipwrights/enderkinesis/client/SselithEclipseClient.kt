package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import java.lang.ref.WeakReference
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import org.shipwrights.enderkinesis.sselith.SselithEclipse

object SselithEclipseClient {

    // Bodies that Light Damage killed. A respawn reuses the entity id, so this holds the entity object.
    // Client thread only.
    private val dusted = ArrayList<WeakReference<Entity>>()

    @JvmStatic
    fun isDusted(entity: Entity): Boolean {
        if (dusted.isEmpty()) return false
        var found = false
        val it = dusted.iterator()
        while (it.hasNext()) {
            val body = it.next().get()
            if (body == null || body.isRemoved) it.remove() else if (body === entity) found = true
        }
        return found
    }

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithEclipse.TRIGGER_PACKET) { buf, _ ->
            val start = buf.readLong()
            SselithEclipse.setClientManualTrigger(start)
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithEclipse.LIGHT_DEATH_PACKET) { buf, ctx ->
            val id = buf.readVarInt()
            ctx.queue {
                val body = Minecraft.getInstance().level?.getEntity(id) ?: return@queue
                if (!isDusted(body)) dusted.add(WeakReference(body))
            }
        }
    }
}
