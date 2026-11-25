package org.shipwrights.enderkinesis.client

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.entity.SwarmFlyEntity
import org.shipwrights.enderkinesis.entity.swarmDenseVolume
import org.shipwrights.enderkinesis.entity.swarmModeScale
import org.shipwrights.enderkinesis.entity.swarmSparseVolume
import org.shipwrights.enderkinesis.registry.EKSounds

private const val SCALE_EASE_TICKS = 20f

private class SwarmLoopPair(val sparse: SwarmFlyLoop, val dense: SwarmFlyLoop)

/** Client thread only. */
private val swarmLoops = WeakHashMap<SwarmFlyEntity, SwarmLoopPair>()

/** Call each client tick. Starts the loop pair once; the loops stop themselves when the swarm is removed. */
fun SwarmFlyEntity.tickSwarmLoops() {
    if (isSilent || !isAlive) return
    val sounds = Minecraft.getInstance().soundManager
    val existing = swarmLoops[this]
    // A level change stops all sounds and does not call stop() on the instance.
    if (existing != null && !existing.sparse.isStopped && sounds.isActive(existing.sparse)) return
    existing?.let {
        sounds.stop(it.sparse)
        sounds.stop(it.dense)
    }
    val pair = SwarmLoopPair(
        SwarmFlyLoop(this, EKSounds.SWARM_FLY_SPARSE_LOOP.get(), dense = false),
        SwarmFlyLoop(this, EKSounds.SWARM_FLY_DENSE_LOOP.get(), dense = true),
    )
    swarmLoops[this] = pair
    sounds.play(pair.sparse)
    sounds.play(pair.dense)
}

// The loop holds its swarm weakly; a strong value would keep the WeakHashMap key and its old level alive.
private class SwarmFlyLoop(swarm: SwarmFlyEntity, event: SoundEvent, val dense: Boolean) :
    AbstractTickableSoundInstance(event, SoundSource.AMBIENT, SoundInstance.createUnseededRandom()) {
    private val ref = WeakReference(swarm)

    // Both loops of a pair ease the same way, so the pair stays in step.
    private var modeScale = swarmModeScale(swarm.mode)

    init {
        looping = true
        delay = 0
        update(swarm)
    }

    // A loop at volume 0 would not start, and the crossfade can reach 0.
    override fun canStartSilent(): Boolean = true

    override fun canPlaySound(): Boolean = ref.get()?.isSilent == false

    override fun tick() {
        val swarm = ref.get()
        if (swarm == null || swarm.isRemoved || !swarm.isAlive) {
            stop()
            return
        }
        update(swarm)
    }

    private fun update(swarm: SwarmFlyEntity) {
        x = swarm.x
        y = swarm.y
        z = swarm.z
        val target = swarmModeScale(swarm.mode)
        val step = 1f / SCALE_EASE_TICKS
        modeScale += (target - modeScale).coerceIn(-step, step)
        volume = if (dense) swarmDenseVolume(swarm.flyCount, modeScale) else swarmSparseVolume(swarm.flyCount, modeScale)
    }
}
