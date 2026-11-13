package org.shipwrights.enderkinesis.vine

import java.util.concurrent.ConcurrentHashMap
import dev.architectury.event.events.common.LifecycleEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import org.joml.Vector3d
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.physics.physicsStepSeconds
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.particles.PhysVsParticle
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.dimensionId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Peak wind speed along X, in m/s. With [WIND_DRAG_RATE] a vine at rest leans atan(0.53 * 6 / 9.81) = 18 degrees. */
internal const val WIND_MAX = 6.0
/** Drag per kg of node per m/s of wind; 1 / rate = 1.9 s is how fast a node takes up the wind speed. */
internal const val WIND_DRAG_RATE = 0.53
private const val PREVAIL = 0.15
private const val DRIFT_WEIGHT = 0.55
private const val GUST_WEIGHT = 0.25
private const val FLUTTER_WEIGHT = 0.05
private const val DRIFT_PERIOD = 45.0
private const val GUST_PERIOD = 10.0
private const val GUST_WIDTH = 24.0
private const val FLUTTER_PERIOD = 4.0
private const val FLUTTER_WIDTH = 10.0
/** Blocks per second that gusts move along X, so vines in a row lean one after the other. */
private const val GUST_TRAVEL = 4.0
/** Vines whose home chunk is farther from every player than this get no wind and can sleep. */
private const val WIND_CHUNK_RADIUS = 8
private const val MAX_WIND_CHAINS = 4096
/** Under this push, in m/s², a node gets no wind, so a vine in a calm can sleep. */
private const val MIN_ACCEL = 0.01
private const val SECONDS_PER_TICK_WIND = 0.05

/**
 * Wind speed along +X (east) in m/s at world point [x], [z]; always in [-WIND_MAX, WIND_MAX].
 * Mostly a light east wind that slowly changes, sometimes turns west, with gusts that move east along X.
 */
fun wohlonWindSpeed(seed: Long, seconds: Double, x: Double, z: Double): Double {
    val travel = seconds - x / GUST_TRAVEL
    val drift = valueNoise(seed, seconds / DRIFT_PERIOD, 0.0)
    val gust = valueNoise(seed + 1, travel / GUST_PERIOD, z / GUST_WIDTH)
    val flutter = valueNoise(seed + 2, travel / FLUTTER_PERIOD, z / FLUTTER_WIDTH)
    val raw = PREVAIL + DRIFT_WEIGHT * drift + GUST_WEIGHT * gust + FLUTTER_WEIGHT * flutter
    return WIND_MAX * raw.coerceIn(-1.0, 1.0)
}

/**
 * Drag acceleration along X on a node that moves at [velocityX] in [wind], with [exposure] in 0..1.
 * The relative speed is held between 0 and the wind speed: a node never gets more push than at rest,
 * and still air never brakes it, so a throw or a swing in Wohlon stays the same as elsewhere.
 */
fun windDragAccel(wind: Double, velocityX: Double, exposure: Double): Double {
    val relative = wind - velocityX
    val held = if (wind >= 0.0) relative.coerceIn(0.0, wind) else relative.coerceIn(wind, 0.0)
    return WIND_DRAG_RATE * exposure * held
}

/** Smooth noise in [-1, 1]: quintic blend of lattice values, so the wind has no sudden step. */
internal fun valueNoise(seed: Long, u: Double, v: Double): Double {
    val fu = floor(u); val fv = floor(v)
    val i = fu.toLong(); val j = fv.toLong()
    val su = fade(u - fu); val sv = fade(v - fv)
    val a = lerp(lattice(seed, i, j), lattice(seed, i + 1, j), su)
    val b = lerp(lattice(seed, i, j + 1), lattice(seed, i + 1, j + 1), su)
    return lerp(a, b, sv)
}

private fun fade(t: Double) = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)

private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

private fun lattice(seed: Long, i: Long, j: Long): Double {
    var h = seed xor (i * -0x61c8864680b583ebL) xor (j * 0x632be59bd9b4e019L)
    h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
    h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
    h = h xor (h ushr 31)
    return (h ushr 11) * (2.0 / (1L shl 53)) - 1.0
}

/** Written by the server thread; [chains] is replaced, never changed. The phys fields belong to the physics thread. */
private class WindState(val seed: Long) {
    @Volatile var chains: Array<ChainId> = emptyArray()
    @Volatile var gameTick = 0L
    val nearby = ArrayList<ChainId>()
    var physTick = -1L
    var physOffset = 0.0
    val particles = ArrayList<PhysVsParticle?>()
    var positions = DoubleArray(0)
}

private val windStates = ConcurrentHashMap<DimensionId, WindState>()

@OptIn(VsBeta::class, PhysTickOnly::class)
fun registerWohlonWind() {
    vsApi.physTickEvent.on { event, _ -> event.world.blowWohlonWind(physicsStepSeconds(event.delta)) }
    LifecycleEvent.SERVER_LEVEL_UNLOAD.register { level -> windStates.remove(level.dimensionId) }
    LifecycleEvent.SERVER_STOPPED.register { windStates.clear() }
}

/** Sends the vines near players in Wohlon, and the game time, to the physics thread. */
internal fun ServerLevel.publishWohlonWind(data: WoggakVineData) {
    if (dimension() != Wohlonnogondonia.LEVEL_KEY) return
    val state = windStates.getOrPut(dimensionId) { WindState(seed) }
    state.gameTick = gameTime
    val nearby = state.nearby
    nearby.clear()
    val players = players()
    for (vine in data.vines) {
        if (nearby.size >= MAX_WIND_CHAINS) break
        val cx = ChunkPos.getX(vine.home); val cz = ChunkPos.getZ(vine.home)
        for (player in players) {
            val pos = player.chunkPosition()
            if (abs(pos.x - cx) <= WIND_CHUNK_RADIUS && abs(pos.z - cz) <= WIND_CHUNK_RADIUS) {
                nearby.add(vine.chain)
                break
            }
        }
    }
    val old = state.chains
    if (old.size != nearby.size || nearby.indices.any { old[it] != nearby[it] }) state.chains = nearby.toTypedArray()
}

@OptIn(PhysTickOnly::class)
private fun PhysLevel.blowWohlonWind(dt: Double) {
    val state = windStates[dimension] ?: return
    val chains = state.chains
    if (chains.isEmpty()) return
    val tick = state.gameTick
    if (tick != state.physTick) { state.physTick = tick; state.physOffset = 0.0 }
    val seconds = tick * SECONDS_PER_TICK_WIND + state.physOffset
    state.physOffset = (state.physOffset + dt).coerceAtMost(SECONDS_PER_TICK_WIND)
    for (id in chains) blowChain(state, id, seconds, dt)
}

@OptIn(PhysTickOnly::class)
private fun PhysLevel.blowChain(state: WindState, id: ChainId, seconds: Double, dt: Double) {
    val ids = getChainById(id)?.nodeIds ?: return
    val n = ids.size
    if (n == 0) return
    if (state.positions.size < n * 3) state.positions = DoubleArray(n * 3)
    val p = state.positions
    val particles = state.particles
    particles.clear()
    for (k in 0 until n) {
        val particle = getParticleById(ids[k])
        particles.add(particle)
        val at = particle?.position ?: continue
        p[k * 3] = at.x(); p[k * 3 + 1] = at.y(); p[k * 3 + 2] = at.z()
    }
    for (k in 0 until n) {
        val particle = particles[k] ?: continue
        val x = p[k * 3]
        val wind = wohlonWindSpeed(state.seed, seconds, x, p[k * 3 + 2])
        val accel = windDragAccel(wind, particle.velocity.x(), exposure(p, maxOf(k - 1, 0), minOf(k + 1, n - 1)))
        if (abs(accel) < MIN_ACCEL) continue
        if (particle.isSleeping) particle.wake()
        particle.applyWorldImpulse(Vector3d(particle.mass * accel * dt, 0.0, 0.0))
    }
}

/** The share of the link from node [a] to node [b] that faces an X wind; a link along X faces none. */
private fun exposure(p: DoubleArray, a: Int, b: Int): Double {
    val tx = p[b * 3] - p[a * 3]; val ty = p[b * 3 + 1] - p[a * 3 + 1]; val tz = p[b * 3 + 2] - p[a * 3 + 2]
    val length2 = tx * tx + ty * ty + tz * tz
    return if (length2 < 1e-12) 1.0 else sqrt((ty * ty + tz * tz) / length2)
}
