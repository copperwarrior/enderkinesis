package org.shipwrights.enderkinesis.physics

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.util.AerodynamicUtils
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.util.WindData
import org.valkyrienskies.core.api.util.WindProvider
import org.valkyrienskies.core.api.util.WindType
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID

/** Blows ships through VS2's drag: a wind provider that exists only while a Winds cast is live. */
@OptIn(VsBeta::class)
object WindGales {
    /** Wind speed inside a cone, m/s. */
    const val WIND_SPEED = 20.0

    private class Cone(
        val dim: String,
        val ox: Double, val oy: Double, val oz: Double,
        val dx: Double, val dy: Double, val dz: Double,
        val length: Double,
        val tanHalfAngle: Double,
        val apexRadius: Double,
        val tick: Long,
    ) {
        val wind = WindData(Vector3d(dx, dy, dz), WIND_SPEED, WindType.AIR)
    }

    private class Snapshot(val cones: Array<Cone>)

    private val noneAir = WindData.none(WindType.AIR)
    private val noneFluid = WindData.none(WindType.FLUID)

    @Volatile
    private var snapshot = Snapshot(emptyArray())

    private val live = HashMap<UUID, Cone>()
    private var registeredWith: AerodynamicUtils? = null

    // Physics thread calls this for each hull patch each tick: no allocation, no locks.
    private val provider = object : WindProvider {
        override fun getWindFor(type: WindType, dim: String): WindData = none(type)

        override fun getWindFor(type: WindType, x: Double, y: Double, z: Double, dim: String): WindData {
            if (type != WindType.AIR) return noneFluid
            for (cone in snapshot.cones) {
                if (cone.dim != dim) continue
                val rx = x - cone.ox
                val ry = y - cone.oy
                val rz = z - cone.oz
                val along = rx * cone.dx + ry * cone.dy + rz * cone.dz
                if (along < 0.0 || along > cone.length) continue
                val lateralSq = rx * rx + ry * ry + rz * rz - along * along
                val radius = along * cone.tanHalfAngle + cone.apexRadius
                if (lateralSq <= radius * radius) return cone.wind
            }
            return noneAir
        }

        private fun none(type: WindType): WindData = if (type == WindType.AIR) noneAir else noneFluid
    }

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tick)
        LifecycleEvent.SERVER_STOPPED.register { _ -> clearSession() }
    }

    @Suppress("LongParameterList") // Cone shape comes from the item's constants.
    fun cast(level: ServerLevel, caster: UUID, eye: Vec3, look: Vec3, length: Double, tanHalfAngle: Double, apexRadius: Double) {
        live[caster] = Cone(
            level.dimensionId, eye.x, eye.y, eye.z, look.x, look.y, look.z,
            length, tanHalfAngle, apexRadius, level.gameTime,
        )
    }

    // A cast that did not refresh this tick has ended; the release event is not reliable for this.
    private fun tick(level: ServerLevel) {
        if (live.isEmpty()) return
        val now = level.gameTime
        val dim = level.dimensionId
        live.values.removeIf { it.dim == dim && it.tick < now }
        snapshot = Snapshot(live.values.toTypedArray())
        if (live.isEmpty()) unregister() else register(level)
    }

    @OptIn(GameTickOnly::class)
    private fun register(level: ServerLevel) {
        if (registeredWith != null) return
        val aero = level.shipObjectWorld.aerodynamicUtils
        aero.registerWindProvider(provider)
        registeredWith = aero
    }

    private fun unregister() {
        registeredWith?.unregisterWindProvider(provider)
        registeredWith = null
    }

    private fun clearSession() {
        live.clear()
        snapshot = Snapshot(emptyArray())
        registeredWith = null
    }
}
