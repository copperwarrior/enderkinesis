package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * Places a spell has darkened, for real: the light there is lower, on the server as on the
 * client, so monsters spawn in it, the undead stop burning in it, plants grow as in the dark,
 * and terrain, entities and particles are lit by it. Inside a zone block and sky light drop by
 * up to its [Zone.levels] (of 15), fully over the inner part of its radius and less toward its
 * edge, wherever the light engine is asked (`LightEngineSpellDarknessMixin`; Sodium's mesher,
 * which reads light itself, through `WorldSliceSpellDarknessMixin`).
 *
 * A spell keeps a zone with [hold] every tick it should stand, on each side it should stand on
 * (the server's tick for the server's light, the client's for the client's); one not held for
 * a tick is gone. On the client, chunks under a zone are rebuilt when it appears, changes or
 * goes ([onClientChange]), so change a zone's levels in whole steps rather than every tick.
 */
object DarkZones {
    /** A darkened place in [dimension]: [levels] of light taken away round its middle, out to
     *  [radius]. */
    class Zone(val dimension: ResourceKey<Level>, val x: Double, val y: Double, val z: Double, val radius: Double, val levels: Int)

    /** How much of the radius is fully dark before it eases off toward the edge. */
    private const val FULL = 0.6

    /** One side's zones. [zones] is read from light and chunk-building threads: replaced,
     *  never changed. The rest is that side's game thread only. */
    private class Side {
        @Volatile var zones: List<Zone> = emptyList()
        val held = HashMap<Any, Zone>()
        val heldThisTick = HashSet<Any>()
    }

    private val server = Side()
    private val client = Side()

    /** Rebuilds the client's chunks under a zone that appeared, changed or went. Set by the client. */
    var onClientChange: ((Zone) -> Unit)? = null

    fun init() {
        TickEvent.SERVER_POST.register { expire(server, null) }
        LifecycleEvent.SERVER_STOPPED.register { clear(server) }
    }

    /** The client's end of a tick: zones not held this tick go. */
    fun clientTick(inLevel: Boolean) {
        if (!inLevel) { clear(client); return }
        expire(client, onClientChange)
    }

    /** Keeps the zone [key] standing this tick on [level]'s side, at [centre], [radius] across,
     *  taking [levels] of light away. */
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

    /** Whether no zone stands on [clientSide]'s side: the light hooks' fast way out. */
    @JvmStatic
    fun isEmpty(clientSide: Boolean): Boolean = (if (clientSide) client else server).zones.isEmpty()

    /** How many levels of light are taken away at a point of [level]: 0 outside every zone. */
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
