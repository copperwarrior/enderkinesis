package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.TerrainParticle
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.ritual.RootTrail

/**
 * Winding roots behind a projectile that fired a rite: a few strands of mangrove root bits coil round its path
 * and stay a moment after it goes by. Client thread only.
 */
object RitualRootTrail {

    private const val STRANDS = 3
    private const val SPACING = 0.12
    // Turns of the coil per block of flight, in radians.
    private const val TWIST = 1.3
    private const val COIL_RADIUS = 0.3
    private const val WANDER = 0.12
    private const val MAX_TICKS = 100
    // The add-entity packet can come after the trail packet, so the trail waits this long for its projectile.
    private const val WAIT_TICKS = 20
    private const val MIN_MOVE_SQR = 1.0e-4
    // A tick of flight can be long; past this many bits the rest of the tick is skipped.
    private const val MAX_BITS_PER_TICK = 160

    private class Trail(val id: Int) {
        var prev: Vec3? = null
        var along = 0.0
        var age = 0
    }

    private val trails = ArrayList<Trail>()
    private var trailLevel: ClientLevel? = null

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, RootTrail.S2C_TRAIL) { buf, _ ->
            val id = buf.readVarInt()
            Minecraft.getInstance().execute { trails.add(Trail(id)) }
        }
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        if (level !== trailLevel) {
            trails.clear()
            trailLevel = level
        }
        if (trails.isEmpty()) return
        val iterator = trails.iterator()
        while (iterator.hasNext()) {
            val trail = iterator.next()
            trail.age++
            val entity = level.getEntity(trail.id)
            if (entity == null) {
                if (trail.prev != null || trail.age > WAIT_TICKS) iterator.remove()
                continue
            }
            val now = entity.position()
            val prev = trail.prev
            trail.prev = now
            if (prev == null) continue
            if (entity.isRemoved || trail.age > MAX_TICKS || now.distanceToSqr(prev) < MIN_MOVE_SQR) {
                iterator.remove()
                continue
            }
            spawnAlong(level, trail, prev, now)
        }
    }

    private fun spawnAlong(level: ClientLevel, trail: Trail, from: Vec3, to: Vec3) {
        val path = to.subtract(from)
        val length = path.length()
        val dir = path.scale(1.0 / length)
        // Any side vector works; the coil only needs two axes square to the path.
        val side = (if (Math.abs(dir.y) < 0.9) dir.cross(Vec3(0.0, 1.0, 0.0)) else dir.cross(Vec3(1.0, 0.0, 0.0))).normalize()
        val up = dir.cross(side)
        val state = Blocks.MANGROVE_ROOTS.defaultBlockState()
        val engine = Minecraft.getInstance().particleEngine
        val steps = minOf((length / SPACING).toInt(), MAX_BITS_PER_TICK / STRANDS)
        for (step in 0 until steps) {
            val t = step * SPACING
            val s = trail.along + t
            for (strand in 0 until STRANDS) {
                val phase = s * TWIST + strand * Mth.TWO_PI / STRANDS
                val radius = COIL_RADIUS + WANDER * Mth.sin((s * 1.7 + strand * 2.1).toFloat())
                val ox = (side.x * Math.cos(phase) + up.x * Math.sin(phase)) * radius
                val oy = (side.y * Math.cos(phase) + up.y * Math.sin(phase)) * radius
                val oz = (side.z * Math.cos(phase) + up.z * Math.sin(phase)) * radius
                engine.add(RootBit(level, from.x + dir.x * t + ox, from.y + dir.y * t + oy, from.z + dir.z * t + oz, state))
            }
        }
        trail.along += length
    }

    private class RootBit(level: ClientLevel, x: Double, y: Double, z: Double, state: BlockState) :
        TerrainParticle(level, x, y, z, 0.0, 0.0, 0.0, state) {

        init {
            xd = 0.0
            yd = 0.0
            zd = 0.0
            gravity = 0f
            hasPhysics = false
            quadSize = BIT_SIZE
            lifetime = LIFE_TICKS + random.nextInt(LIFE_JITTER)
        }

        // Shrinks away over its last ticks; terrain particles are cutout, so they cannot fade.
        override fun getQuadSize(partialTick: Float): Float {
            val left = (lifetime - age - partialTick) / SHRINK_TICKS
            return quadSize * left.coerceIn(0f, 1f)
        }

        companion object {
            private const val BIT_SIZE = 0.09f
            private const val LIFE_TICKS = 40
            private const val LIFE_JITTER = 12
            private const val SHRINK_TICKS = 10f
        }
    }
}
