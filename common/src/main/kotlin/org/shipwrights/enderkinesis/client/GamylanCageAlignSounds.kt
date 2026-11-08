package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.util.GameTickOnly

@OptIn(GameTickOnly::class)
object GamylanCageAlignSounds {

    private const val MAX_VOLUME: Float = 1f
    private const val FADE_TICKS: Int = 5
    private const val NEAR_PITCH: Float = 1f
    private const val FAR_PITCH: Float = 0.6f
    private const val FAR_DISTANCE: Double = 3.0
    private const val PITCH_SMOOTHING: Float = 0.2f

    /** The capture or conjure sound covers the 30-tick fade of a new cage. */
    private const val NEW_CAGE_SILENT_TICKS: Long = 30

    /** Client thread only. */
    private val loops: MutableMap<Long, AlignLoop> = HashMap()

    fun onServerUpdate(entry: GamylanCageClientRegistry.Entry, target: GamylanCageClientRegistry.AlignTarget?) {
        entry.alignTarget = target
        if (target == null) return
        val sounds = Minecraft.getInstance().soundManager
        val existing = loops[entry.bodyId]
        // A level change stops all sounds and does not call stop() on the instance.
        if (existing != null && existing.entry === entry && !existing.isStopped && sounds.isActive(existing)) return
        existing?.let(sounds::stop)
        loops[entry.bodyId] = AlignLoop(entry).also(sounds::play)
    }

    fun reset() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        loops.clear()
    }

    private class AlignLoop(val entry: GamylanCageClientRegistry.Entry) : AbstractTickableSoundInstance(
        EKSounds.CRYSTAL_PEDESTAL_ALIGN.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        private val targetWorld = Vector3d()

        init {
            looping = true
            delay = 0
            volume = 0f
            pitch = FAR_PITCH
            val live = moveToCage()
            targetPitch(live)?.let { pitch = it }
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val level = Minecraft.getInstance().level
            val target = entry.alignTarget
            val registered = level != null &&
                GamylanCageClientRegistry.entryFor(level.dimension(), entry.bodyId) === entry
            if (level == null || !registered || target == null) {
                volume -= MAX_VOLUME / FADE_TICKS
                if (volume <= 0f) {
                    loops.remove(entry.bodyId, this)
                    stop()
                }
                return
            }
            val live = moveToCage()
            targetPitch(live)?.let { pitch += (it - pitch) * PITCH_SMOOTHING }
            if (level.gameTime - entry.addedAtGameTime < NEW_CAGE_SILENT_TICKS) return
            volume = (volume + MAX_VOLUME / FADE_TICKS).coerceAtMost(MAX_VOLUME)
        }

        private fun moveToCage(): Vector3d {
            val live = GamylanCageClientRegistry.livePosition(entry)
            x = live.x
            y = live.y
            z = live.z
            return live
        }

        /** Null when there is no target or the host ship is not on the client. */
        private fun targetPitch(live: Vector3d): Float? {
            val target = entry.alignTarget ?: return null
            targetWorld.set(target.hoverInFrame)
            val shipId = target.shipId
            if (shipId != null) {
                val level = Minecraft.getInstance().level ?: return null
                val ship = level.shipObjectWorld.allShips.getById(shipId) ?: return null
                ship.transform.shipToWorld.transformPosition(targetWorld)
            }
            val far = Mth.clamp(live.distance(targetWorld) / FAR_DISTANCE, 0.0, 1.0).toFloat()
            return Mth.lerp(far, NEAR_PITCH, FAR_PITCH)
        }
    }
}
