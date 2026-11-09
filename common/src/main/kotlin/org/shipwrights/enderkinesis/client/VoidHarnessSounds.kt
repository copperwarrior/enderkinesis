package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.VoidHarnessBlock
import org.shipwrights.enderkinesis.blockentity.VoidHarnessBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHarnessClientRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.blockCentreInWorld

object VoidHarnessSounds {

    /** The hum starts after the spin-up, so the two sounds overlap. */
    private const val HUM_START_DELAY_TICKS: Long = 20
    private const val HUM_FADE_OUT_TICKS: Int = 5
    /** The fraction of the remaining pitch and volume change that the hum does in one tick. */
    private const val HUM_GLIDE: Float = 0.25f
    private const val STRAIN_FADE_TICKS: Int = 20
    /** A small margin on the audible range, so the loop is at volume before the player hears it. */
    private const val START_RANGE: Double = EKSounds.VOID_HARNESS_LOOP_RANGE + 2.0
    private const val START_RANGE_SQR: Double = START_RANGE * START_RANGE
    /** Many harnesses on one ship play the same loops, and more copies only blur them. */
    private const val MAX_LOOPS = 4
    /** A playing harness counts as this much nearer, so two at near the same distance do not swap each tick. */
    private const val PLAYING_BIAS_SQR = 0.8

    /** Client thread only. */
    private val hums = HashMap<VoidHarnessBlockEntity, HumLoop>()
    private val strains = HashMap<VoidHarnessBlockEntity, StrainLoop>()
    private val lastPower = HashMap<VoidHarnessBlockEntity, Int>()
    private val nearest = NearestLoopSelection<VoidHarnessBlockEntity>(MAX_LOOPS, PLAYING_BIAS_SQR)
    /** Harnesses that turned on this tick. */
    private val justPowered = HashSet<VoidHarnessBlockEntity>()
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        hums.values.forEach(sounds::stop)
        strains.values.forEach(sounds::stop)
        hums.clear()
        strains.clear()
        lastPower.clear()
        nearest.clear()
        justPowered.clear()
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        hums.values.removeIf { it.isStopped || !sounds.isActive(it) }
        strains.values.removeIf { it.isStopped || !sounds.isActive(it) }
        lastPower.keys.removeIf { it.isRemoved }
        nearest.beginTick()
        justPowered.clear()
        for (be in VoidHarnessClientRegistry.all(level)) {
            if (be.isRemoved) continue
            val power = power(be)
            val previous = lastPower.put(be, power)
            if (power <= 0 || !inRange(level, be, player)) continue
            if (previous == 0) justPowered.add(be)
            nearest.offer(be, player.distanceToSqr(scratch.x, scratch.y, scratch.z), be in hums)
        }
        nearest.select()
        for (be in nearest.selected()) {
            if (be !in hums) {
                val delay = if (be in justPowered) HUM_START_DELAY_TICKS else 0L
                hums[be] = HumLoop(level, be, level.gameTime + delay).also(sounds::play)
            }
            if (be.pulling && be !in strains) strains[be] = StrainLoop(level, be).also(sounds::play)
        }
    }

    private fun power(be: VoidHarnessBlockEntity): Int = be.blockState.getValue(VoidHarnessBlock.POWER)

    private fun inRange(level: ClientLevel, be: VoidHarnessBlockEntity, player: Player): Boolean {
        level.blockCentreInWorld(be.blockPos, scratch)
        return player.distanceToSqr(scratch.x, scratch.y, scratch.z) <= START_RANGE_SQR
    }

    private class HumLoop(
        level: ClientLevel,
        be: VoidHarnessBlockEntity,
        private val startTick: Long,
    ) : HarnessLoop(level, be, EKSounds.VOID_HARNESS_HUM.get()) {

        init {
            pitch = VoidHarnessBlock.humPitch(power(be))
        }

        override fun tick() {
            val power = if (isAudible() && be in nearest) power(be) else 0
            if (power <= 0) {
                volume -= 1f / HUM_FADE_OUT_TICKS
                if (volume <= 0f) stop()
                return
            }
            if (level.gameTime < startTick) return
            volume += (0.5f + 0.5f * power / 15f - volume) * HUM_GLIDE
            pitch += (VoidHarnessBlock.humPitch(power) - pitch) * HUM_GLIDE
        }
    }

    private class StrainLoop(level: ClientLevel, be: VoidHarnessBlockEntity) :
        HarnessLoop(level, be, EKSounds.VOID_HARNESS_STRAIN.get()) {

        override fun tick() {
            val target = if (isAudible() && be.pulling && be in nearest) power(be) / 15f else 0f
            val step = 1f / STRAIN_FADE_TICKS
            volume = if (volume < target) {
                (volume + step).coerceAtMost(target)
            } else {
                (volume - step).coerceAtLeast(target)
            }
            if (target <= 0f && volume <= 0f) stop()
        }
    }

    /** A loop at the world position of a harness. It follows the ship of the harness. */
    private abstract class HarnessLoop(
        protected val level: ClientLevel,
        protected val be: VoidHarnessBlockEntity,
        event: SoundEvent,
    ) : AbstractTickableSoundInstance(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom()) {

        private val position = Vector3d()

        init {
            looping = true
            delay = 0
            volume = 0f
            move()
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        protected fun isAudible(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            if (player.level() !== level || be.isRemoved) return false
            move()
            return player.distanceToSqr(x, y, z) <= START_RANGE_SQR
        }

        private fun move() {
            level.blockCentreInWorld(be.blockPos, position)
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
