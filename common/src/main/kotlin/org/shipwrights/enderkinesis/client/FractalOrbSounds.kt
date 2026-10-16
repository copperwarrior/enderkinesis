package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.shipwrights.enderkinesis.body.ClientOrbRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.bodies.ClientVsBody
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld

/** One thrum loop for each Orb of Potential near the local player. Client thread only. */
object FractalOrbSounds {

    private const val RANGE: Double = EKSounds.FRACTAL_ORB_LOOP_RANGE.toDouble()
    private const val RANGE_SQR: Double = RANGE * RANGE
    private const val VOLUME = 0.8f
    private const val PITCH_MIN = 0.94f
    private const val PITCH_SPAN = 0.12f

    private val loops = Long2ObjectOpenHashMap<OrbLoop>()
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        for (loop in loops.values) sounds.stop(loop)
        loops.clear()
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        // A level change stops all sounds and does not call stop() on the instance.
        val it = loops.values.iterator()
        while (it.hasNext()) {
            val loop = it.next()
            if (loop.isStopped || !mc.soundManager.isActive(loop)) it.remove()
        }
        for (bodyId in ClientOrbRegistry.near(level)) {
            if (loops.containsKey(bodyId)) continue
            val body = liveBody(level, bodyId) ?: continue
            val pos = body.kinematics.position
            if (player.distanceToSqr(pos.x(), pos.y(), pos.z()) > RANGE_SQR) continue
            loops.put(bodyId, OrbLoop(bodyId, pitchFor(bodyId)).also(mc.soundManager::play))
        }
    }

    /** VS2 keeps bodies of each dimension the client loaded, so an orb of another dimension can still resolve. */
    private fun liveBody(level: ClientLevel, bodyId: Long): ClientVsBody? {
        val body = level.shipObjectWorld.allBodies.getById(bodyId) as? ClientVsBody ?: return null
        return if (body.dimension == level.dimensionId) body else null
    }

    private fun pitchFor(bodyId: Long): Float {
        val bits = (bodyId * -0x61c8864680b583ebL) ushr 40
        return PITCH_MIN + PITCH_SPAN * bits.toFloat() / (1L shl 24).toFloat()
    }

    private class OrbLoop(private val bodyId: Long, pitch: Float) : AbstractTickableSoundInstance(
        EKSounds.FRACTAL_ORB_LOOP.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        init {
            looping = true
            delay = 0
            volume = VOLUME
            this.pitch = pitch
            follow()
        }

        override fun tick() {
            if (!follow()) stop()
        }

        /** False when the orb is gone or out of range. */
        private fun follow(): Boolean {
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return false
            val player = mc.player ?: return false
            val body = liveBody(level, bodyId) ?: return false
            val pos = body.renderTransform.toWorld.transformPosition(0.0, 0.0, 0.0, scratch)
            x = pos.x
            y = pos.y
            z = pos.z
            return player.distanceToSqr(x, y, z) <= RANGE_SQR
        }
    }
}
