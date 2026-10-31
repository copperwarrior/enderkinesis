package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.ParticleStatus
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.world.level.LightLayer
import org.shipwrights.enderkinesis.dimension.WohlonBiomePresence
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Client-side ambient firefly spawner for Wohlon biome cells, in the dimension and in infected areas.
 *
 * One attempt each [TICK_INTERVAL] ticks at a random voxel near the player. The voxel must be air,
 * in a Wohlon cell, open to the sky, and next to a block. Most attempts fail that test, and at most
 * [MAX_ALIVE] fireflies are alive at one time.
 */
object WohlonnogondoniaFireflies {

    private const val SPAWN_RADIUS_XZ: Int = 12
    private const val SPAWN_RADIUS_Y: Int = 6
    private const val TICK_INTERVAL: Int = 2

    /** 10 attempts/s; a success lives 15–30 s, so this cap is what holds the count in an open swamp. */
    private const val MAX_ALIVE: Int = 32

    /** Leaves dim sky light by one per layer, so a canopy keeps some; a cave reads 0. */
    private const val MIN_SKY_LIGHT: Int = 4

    /** Vanilla daylight factor, before weather, above which an overworld area is too bright. */
    private const val MAX_DAYLIGHT: Float = 0.5f

    private val DIRECTIONS: Array<Direction> = Direction.values()

    // Client thread only.
    private var tickCounter: Int = 0
    private val pos = BlockPos.MutableBlockPos()
    private val neighbour = BlockPos.MutableBlockPos()
    private val alive = ArrayList<Particle>(MAX_ALIVE)
    private var aliveLevel: ClientLevel? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        if (!WohlonBiomePresence.nearby) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (++tickCounter < TICK_INTERVAL) return
        tickCounter = 0
        if (!pruneAlive(level)) return
        if (!isDarkEnough(level)) return

        val rng = level.random
        val wx = player.blockX + rng.nextInt(SPAWN_RADIUS_XZ * 2 + 1) - SPAWN_RADIUS_XZ
        val wy = player.blockY + rng.nextInt(SPAWN_RADIUS_Y * 2 + 1) - SPAWN_RADIUS_Y
        val wz = player.blockZ + rng.nextInt(SPAWN_RADIUS_XZ * 2 + 1) - SPAWN_RADIUS_XZ
        pos.set(wx, wy, wz)
        // An unloaded chunk reads as air, so the neighbour test below rejects it.
        if (!level.getBlockState(pos).isAir) return
        if (!hasBlockNeighbour(level)) return
        if (level.dimensionType().hasSkyLight() && level.getBrightness(LightLayer.SKY, pos) < MIN_SKY_LIGHT) return
        if (!level.getBiome(pos).`is`(Wohlonnogondonia.BIOME_KEY)) return
        if (!particleSettingAllows(mc, rng.nextInt(3))) return

        // Jitter inside the voxel, or two spawns in one block trace identical orbits.
        val particle = mc.particleEngine.createParticle(
            EKParticles.WOHLON_FIREFLY.get() as SimpleParticleType,
            wx + 0.2 + rng.nextDouble() * 0.6,
            wy + 0.2 + rng.nextDouble() * 0.6,
            wz + 0.2 + rng.nextDouble() * 0.6,
            0.0, 0.0, 0.0,
        ) ?: return
        alive.add(particle)
    }

    /** False at the cap. A level change clears the particle engine without removing its particles. */
    private fun pruneAlive(level: ClientLevel): Boolean {
        if (level !== aliveLevel) {
            alive.clear()
            aliveLevel = level
        }
        alive.removeIf { !it.isAlive }
        return alive.size < MAX_ALIVE
    }

    /** Uses the real day time: the client time-of-day snaps to Wohlon dusk inside the biome. */
    fun isDarkEnough(level: ClientLevel): Boolean {
        val type = level.dimensionType()
        if (type.hasFixedTime() || !type.hasSkyLight()) return true
        val timeOfDay = WohlonBiomeSkyState.unsnappedTimeOfDayFromDayTime(level.dayTime)
        val daylight = (Math.cos(timeOfDay * Math.PI * 2.0) * 2.0 + 0.2).coerceIn(0.0, 1.0)
        return daylight < MAX_DAYLIGHT
    }

    private fun hasBlockNeighbour(level: ClientLevel): Boolean {
        for (dir in DIRECTIONS) {
            if (!level.getBlockState(neighbour.setWithOffset(pos, dir)).isAir) return true
        }
        return false
    }

    // ParticleEngine.createParticle skips the particle setting that LevelRenderer.addParticle applies.
    internal fun particleSettingAllows(mc: Minecraft, roll: Int): Boolean = when (mc.options.particles().get()) {
        ParticleStatus.MINIMAL -> false
        ParticleStatus.DECREASED -> roll != 0
        else -> true
    }
}
