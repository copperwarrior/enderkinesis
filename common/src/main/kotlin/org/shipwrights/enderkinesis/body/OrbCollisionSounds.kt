package org.shipwrights.enderkinesis.body

import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.concurrent.ConcurrentHashMap

/** Knock sound for an Orb of Potential that strikes something. */
object OrbCollisionSounds {

    /** Dimension id → (orb body id → peak approach speed). Written on the physics thread, drained on the server. */
    private val pending = ConcurrentHashMap<String, ConcurrentHashMap<Long, Double>>()

    /** Server thread only. */
    private val lastSoundAt = Long2LongOpenHashMap().apply { defaultReturnValue(Long.MIN_VALUE / 2) }

    /** Dimension id → orb body ids at a registry version. Each set is not changed after it is published. */
    private val orbIds = ConcurrentHashMap<String, OrbIds>()

    private class OrbIds(val version: Int, val ids: LongOpenHashSet)

    @Volatile private var registered = false

    fun register() {
        if (registered) return
        registered = true
        @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
        vsApi.collisionStartEvent.on { event, _ -> onCollisionStart(event) }
        TickEvent.SERVER_LEVEL_POST.register(::drain)
    }

    @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
    private fun onCollisionStart(event: CollisionEvent) {
        val dim = event.dimensionId
        val ids = orbIdsIn(dim)
        val aIsOrb = ids.contains(event.shipIdA)
        val bIsOrb = ids.contains(event.shipIdB)
        if (!aIsOrb && !bIsOrb) return
        var peak = 0.0
        for (contact in event.contactPoints) {
            if (contact.separation > SEPARATION_EPSILON) continue
            val v = contact.velocity
            val n = contact.normal
            // VS2 does not fix which body is A, so the sign of v·n means nothing.
            val approach = Math.abs(v.x() * n.x() + v.y() * n.y() + v.z() * n.z())
            if (approach > peak) peak = approach
        }
        if (peak < MIN_SPEED) return
        val orbs = pending.computeIfAbsent(dim) { ConcurrentHashMap() }
        if (aIsOrb) orbs.merge(event.shipIdA, peak, Math::max)
        if (bIsOrb) orbs.merge(event.shipIdB, peak, Math::max)
    }

    /** Read the version first: then the set is never older than the version it is stored with. */
    private fun orbIdsIn(dim: String): LongOpenHashSet {
        val version = OrbBodyRegistry.version
        val cached = orbIds[dim]
        if (cached != null && cached.version == version) return cached.ids
        val ids = LongOpenHashSet()
        OrbBodyRegistry.forEachIn(dim) { id, _ -> ids.add(id) }
        orbIds[dim] = OrbIds(version, ids)
        return ids
    }

    private fun drain(level: ServerLevel) {
        val orbs = pending[level.dimensionId] ?: return
        if (orbs.isEmpty()) return
        val bodies = level.shipObjectWorld.allBodies
        val now = level.gameTime
        val it = orbs.entries.iterator()
        while (it.hasNext()) {
            val (bodyId, speed) = it.next()
            it.remove()
            if (now - lastSoundAt.get(bodyId) < COOLDOWN_TICKS) continue
            val pos = bodies.getById(bodyId)?.kinematics?.position ?: continue
            lastSoundAt.put(bodyId, now)
            val strength = ((speed - MIN_SPEED) / (FULL_SPEED - MIN_SPEED)).coerceIn(0.0, 1.0).toFloat()
            level.playSound(
                null, pos.x(), pos.y(), pos.z(), EKSounds.FRACTAL_ORB_COLLIDE.get(), SoundSource.BLOCKS,
                MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * strength, 1f,
            )
        }
        // An entry past the cooldown acts the same as no entry.
        val stale = lastSoundAt.long2LongEntrySet().fastIterator()
        while (stale.hasNext()) if (now - stale.next().longValue >= COOLDOWN_TICKS) stale.remove()
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        pending.clear()
        lastSoundAt.clear()
        orbIds.clear()
    }

    private const val SEPARATION_EPSILON = 0.05f

    /** Approach speeds in m/s. Below the minimum is a rest contact, not a knock. */
    private const val MIN_SPEED = 0.5
    private const val FULL_SPEED = 8.0
    private const val MIN_VOLUME = 0.3f
    private const val MAX_VOLUME = 1.0f
    private const val COOLDOWN_TICKS = 5L
}
