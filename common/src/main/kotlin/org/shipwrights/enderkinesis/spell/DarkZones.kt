package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/** Call [hold] each tick on each side that the zone must stand on. A zone not held for one tick goes.
 *  Each client change rebuilds chunks, so change [Zone.levels] in steps, not every tick. */
object DarkZones {
    class Zone(val dimension: ResourceKey<Level>, val x: Double, val y: Double, val z: Double, val radius: Double, val levels: Int)

    // Fraction of the radius that is fully dark.
    private const val FULL = 0.6

    // Light and chunk-build threads read [zones], so replace it, never change it. The rest is game thread only.
    private class Side {
        @Volatile var zones: List<Zone> = emptyList()
        val held = HashMap<Any, Zone>()
        val heldThisTick = HashSet<Any>()
    }

    private val server = Side()
    private val client = Side()

    var onClientChange: ((Zone) -> Unit)? = null

    fun init() {
        TickEvent.SERVER_POST.register { expire(server, null) }
        LifecycleEvent.SERVER_STOPPED.register { clear(server) }
    }

    fun clientTick(inLevel: Boolean) {
        if (!inLevel) { clear(client); return }
        expire(client, onClientChange)
    }

    fun hold(level: Level, key: Any, centre: Vec3, radius: Double, levels: Int) {
        val side = if (level.isClientSide) client else server
        val zone = Zone(level.dimension(), Math.floor(centre.x) + 0.5, Math.floor(centre.y) + 0.5, Math.floor(centre.z) + 0.5, radius, levels.coerceIn(0, 15))
        side.heldThisTick += key
        val old = side.held[key]
        if (old != null && same(old, zone)) return
        side.held[key] = zone
        if (level.isClientSide) {
            old?.let { onClientChange?.invoke(it) }
            onClientChange?.invoke(zone)
        }
        publish(side)
    }

    private fun same(a: Zone, b: Zone) =
        a.dimension == b.dimension && a.x == b.x && a.y == b.y && a.z == b.z && a.radius == b.radius && a.levels == b.levels

    private fun expire(side: Side, changed: ((Zone) -> Unit)?) {
        val gone = side.held.keys.filter { it !in side.heldThisTick }
        for (key in gone) side.held.remove(key)?.let { changed?.invoke(it) }
        if (gone.isNotEmpty()) publish(side)
        side.heldThisTick.clear()
    }

    private fun clear(side: Side) {
        side.held.clear()
        side.heldThisTick.clear()
        publish(side)
    }

    private fun publish(side: Side) {
        side.zones = side.held.values.filter { it.levels > 0 }.toList()
    }

    @JvmStatic
    fun isEmpty(clientSide: Boolean): Boolean = (if (clientSide) client else server).zones.isEmpty()

    @JvmStatic
    fun levelsAt(level: Level, x: Double, y: Double, z: Double): Int {
        val list = (if (level.isClientSide) client else server).zones
        if (list.isEmpty()) return 0
        val dimension = level.dimension()
        var take = 0.0
        for (zone in list) {
            if (zone.dimension != dimension) continue
            val dx = x - zone.x; val dy = y - zone.y; val dz = z - zone.z
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= zone.radius * zone.radius) continue
            val t = Math.sqrt(d2) / zone.radius
            val f = if (t <= FULL) 1.0 else 1.0 - (t - FULL) / (1.0 - FULL)
            take = maxOf(take, zone.levels * f)
        }
        return Math.round(take).toInt()
    }
}
