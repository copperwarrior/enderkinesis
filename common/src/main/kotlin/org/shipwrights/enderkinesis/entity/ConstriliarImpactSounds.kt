package org.shipwrights.enderkinesis.entity

import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.concurrent.ConcurrentHashMap
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.VsBeta

@OptIn(GameTickOnly::class, VsBeta::class)
object ConstriliarImpactSounds {

    /** Dimension id → (body ship id → walker). Written on the server thread, read on the physics thread. */
    private val bodies = ConcurrentHashMap<String, ConcurrentHashMap<Long, ConstriliarEntity>>()

    /** Dimension id → (body ship id → peak approach speed). Written on the physics thread, drained on the server. */
    private val pending = ConcurrentHashMap<String, ConcurrentHashMap<Long, Double>>()

    /** Server thread only. */
    private val lastSoundAt = HashMap<Long, Long>()

    @Volatile private var registered = false

    fun register() {
        if (registered) return
        registered = true
        @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
        vsApi.collisionStartEvent.on { event, _ -> onCollisionStart(event) }
        TickEvent.SERVER_LEVEL_POST.register(::drain)
    }

    fun track(dimensionId: String, shipId: Long, walker: ConstriliarEntity) {
        bodies.computeIfAbsent(dimensionId) { ConcurrentHashMap() }[shipId] = walker
    }

    fun untrack(dimensionId: String, shipId: Long) {
        bodies[dimensionId]?.remove(shipId)
        lastSoundAt.remove(shipId)
    }

    @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
    private fun onCollisionStart(event: CollisionEvent) {
        val dim = event.dimensionId
        val walkers = bodies[dim] ?: return
        val aIsBody = walkers.containsKey(event.shipIdA)
        val bIsBody = walkers.containsKey(event.shipIdB)
        if (!aIsBody && !bIsBody) return
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
        val hits = pending.computeIfAbsent(dim) { ConcurrentHashMap() }
        if (aIsBody) hits.merge(event.shipIdA, peak, Math::max)
        if (bIsBody) hits.merge(event.shipIdB, peak, Math::max)
    }

    private fun drain(level: ServerLevel) {
        val hits = pending[level.dimensionId] ?: return
        if (hits.isEmpty()) return
        val walkers = bodies[level.dimensionId]
        val ships = level.shipObjectWorld.allBodies
        val now = level.gameTime
        val it = hits.entries.iterator()
        while (it.hasNext()) {
            val (shipId, speed) = it.next()
            it.remove()
            val walker = walkers?.get(shipId) ?: continue
            if (walker.isRemoved || now - (lastSoundAt[shipId] ?: Long.MIN_VALUE / 2) < COOLDOWN_TICKS) continue
            val pos = ships.getById(shipId)?.kinematics?.position ?: continue
            lastSoundAt[shipId] = now
            val strength = ((speed - MIN_SPEED) / (FULL_SPEED - MIN_SPEED)).coerceIn(0.0, 1.0).toFloat()
            val plate = walker.bodyBlockState().soundType
            level.playSound(
                null, pos.x(), pos.y(), pos.z(), plate.hitSound, SoundSource.NEUTRAL,
                plate.volume * (MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * strength), plate.pitch,
            )
            if (speed < VOICE_SPEED || walker.landSoundNear(now, LAND_OVERLAP_TICKS)) continue
            val voice = if (speed >= HEAVY_SPEED) EKSounds.CONSTRILIAR_IMPACT_HEAVY else EKSounds.CONSTRILIAR_IMPACT
            walker.impactVoice(voice.get())
        }
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        bodies.clear()
        pending.clear()
        lastSoundAt.clear()
    }

    private const val SEPARATION_EPSILON = 0.05f

    /** Approach speeds in m/s. Below the minimum is a rest contact; light hits play only the plate. */
    private const val MIN_SPEED = 1.0
    private const val VOICE_SPEED = 4.0
    private const val HEAVY_SPEED = 8.0
    private const val FULL_SPEED = 8.0
    private const val MIN_VOLUME = 0.3f
    private const val MAX_VOLUME = 1.0f
    private const val COOLDOWN_TICKS = 10L
    private const val LAND_OVERLAP_TICKS = 10L
}
