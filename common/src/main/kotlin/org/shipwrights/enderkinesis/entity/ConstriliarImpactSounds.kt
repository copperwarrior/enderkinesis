package org.shipwrights.enderkinesis.entity

import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.physics.ConstriliarContacts
import org.shipwrights.enderkinesis.registry.EKSounds
import org.joml.Vector3d
import org.valkyrienskies.core.api.events.CollisionEvent
import org.valkyrienskies.core.api.physics.ContactPoint
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import kotlin.math.abs
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.VsBeta

@OptIn(GameTickOnly::class, VsBeta::class)
object ConstriliarImpactSounds {

    /** Dimension id → (body ship id → walker). Written on the server thread, read on the physics thread. */
    private val bodies = ConcurrentHashMap<String, ConcurrentHashMap<Long, ConstriliarEntity>>()

    /** One frame's hardest contact of a walker body: VS2 approach speed, world point and the other body. */
    private class Hit(val speed: Double, val x: Double, val y: Double, val z: Double, val other: Long)

    /** Dimension id → (body ship id → hardest hit). Written on the physics thread, drained on the server. */
    private val pending = ConcurrentHashMap<String, ConcurrentHashMap<Long, Hit>>()
    private val harder = BiFunction<Hit, Hit, Hit> { a, b -> if (b.speed > a.speed) b else a }

    /** Server thread only. */
    private val lastSoundAt = HashMap<Long, Long>()

    @Volatile private var registered = false

    fun register() {
        if (registered) return
        registered = true
        @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
        vsApi.collisionStartEvent.on { event, _ -> onCollision(event) }
        vsApi.collisionPersistEvent.on { event, _ -> onCollision(event) }
        TickEvent.SERVER_LEVEL_POST.register(::drain)
    }

    fun track(dimensionId: String, shipId: Long, walker: ConstriliarEntity) {
        bodies.computeIfAbsent(dimensionId) { ConcurrentHashMap() }[shipId] = walker
    }

    /** The walker whose body is ship [shipId], or null. Server thread. */
    fun walkerOf(dimensionId: String, shipId: Long): ConstriliarEntity? = bodies[dimensionId]?.get(shipId)

    fun untrack(dimensionId: String, shipId: Long) {
        bodies[dimensionId]?.remove(shipId)
        lastSoundAt.remove(shipId)
    }

    @Suppress("OPT_IN_USAGE") // CollisionEvent is @VsBeta
    private fun onCollision(event: CollisionEvent) {
        var peak = 0.0
        var at: ContactPoint? = null
        for (contact in event.contactPoints) {
            if (contact.separation > ConstriliarContacts.TOUCH_SEPARATION) continue
            val v = contact.velocity
            val n = contact.normal
            // VS2 does not fix which body is A, so the sign of v·n means nothing.
            val approach = abs(v.x() * n.x() + v.y() * n.y() + v.z() * n.z())
            if (approach > peak) {
                peak = approach
                at = contact
            }
        }
        // Persist events come every frame for every resting contact: the speed gate goes first.
        if (peak < MIN_SPEED || at == null) return
        val dim = event.dimensionId
        val walkers = bodies[dim] ?: return
        val aIsBody = walkers.containsKey(event.shipIdA)
        val bIsBody = walkers.containsKey(event.shipIdB)
        if (!aIsBody && !bIsBody) return
        val p = at.position
        val hits = pending.computeIfAbsent(dim) { ConcurrentHashMap() }
        if (aIsBody) hits.merge(event.shipIdA, Hit(peak, p.x(), p.y(), p.z(), event.shipIdB), harder)
        if (bIsBody) hits.merge(event.shipIdB, Hit(peak, p.x(), p.y(), p.z(), event.shipIdA), harder)
    }

    private fun drain(level: ServerLevel) {
        val hits = pending[level.dimensionId] ?: return
        if (hits.isEmpty()) return
        val walkers = bodies[level.dimensionId]
        val now = level.gameTime
        val it = hits.entries.iterator()
        while (it.hasNext()) {
            val (shipId, hit) = it.next()
            it.remove()
            val speed = hit.speed
            val walker = walkers?.get(shipId) ?: continue
            if (walker.isRemoved) continue
            if (now - (lastSoundAt[shipId] ?: Long.MIN_VALUE / 2) < COOLDOWN_TICKS) {
                if (ConstriliarMoveLog.ENABLED) ConstriliarMoveLog.bump(walker, speed, "cooldown ${where(level, shipId, hit)}")
                continue
            }
            lastSoundAt[shipId] = now
            val strength = ((speed - MIN_SPEED) / (FULL_SPEED - MIN_SPEED)).coerceIn(0.0, 1.0).toFloat()
            val plate = walker.bodyBlockState().soundType
            level.playSound(
                null, hit.x, hit.y, hit.z, plate.hitSound, SoundSource.NEUTRAL,
                plate.volume * (MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * strength), plate.pitch,
            )
            val voiced = speed >= VOICE_SPEED && !walker.landSoundNear(now, LAND_OVERLAP_TICKS)
            if (ConstriliarMoveLog.ENABLED) {
                ConstriliarMoveLog.bump(walker, speed, "${if (voiced) "plate+voice" else "plate"} ${where(level, shipId, hit)}")
            }
            if (!voiced) continue
            val voice = if (speed >= HEAVY_SPEED) EKSounds.CONSTRILIAR_IMPACT_HEAVY else EKSounds.CONSTRILIAR_IMPACT
            walker.impactVoice(voice.get())
        }
    }

    /** The walker block that took [hit] and the body it met, for the bump log. */
    private fun where(level: ServerLevel, shipId: Long, hit: Hit): String {
        val other = level.shipObjectWorld.allShips.getById(hit.other)
        val against = if (other == null) "body ${hit.other} (terrain or a non-ship body)" else "ship ${hit.other} ${other.slug}"
        val ship = level.shipObjectWorld.allShips.getById(shipId) ?: return "against $against"
        // VS2 puts the point on either surface: step it toward the centre of mass until it meets a block.
        val local = ship.transform.worldToShip.transformPosition(Vector3d(hit.x, hit.y, hit.z))
        val com = ship.inertiaData.centerOfMass
        val step = Vector3d(com).sub(local)
        if (step.lengthSquared() > 1.0e-8) step.normalize(WHERE_STEP)
        val cell = BlockPos.MutableBlockPos()
        repeat(WHERE_STEPS) {
            cell.set(local.x, local.y, local.z)
            val state = level.getBlockState(cell)
            if (!state.isAir) return "at ${BuiltInRegistries.BLOCK.getKey(state.block).path} " +
                "${state.values.entries.joinToString(",") { e -> "${e.key.name}=${e.value}" }} against $against"
            local.add(step)
        }
        return "at ? against $against"
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        bodies.clear()
        pending.clear()
        lastSoundAt.clear()
    }

    private const val WHERE_STEP = 0.1
    private const val WHERE_STEPS = 6

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
