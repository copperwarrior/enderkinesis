package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.VoidHookBlock
import org.shipwrights.enderkinesis.blockentity.VoidHookBlockEntity
import org.shipwrights.enderkinesis.blockentity.VoidHookClientRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos

object VoidHookSounds {

    private const val FADE_OUT_TICKS: Int = 5
    /** The fraction of the remaining pitch and volume change that the loop does in one tick. */
    private const val GLIDE: Float = 0.25f
    private const val POWER_SOUND_VOLUME: Float = 0.8f
    /** A small margin on the audible range, so the loop is at volume before the player hears it. */
    private const val START_RANGE: Double = EKSounds.VOID_HOOK_LOOP_RANGE + 2.0
    private const val START_RANGE_SQR: Double = START_RANGE * START_RANGE
    /** Many hooks on one ship play the same loop, and more copies only blur it. */
    private const val MAX_LOOPS = 4
    /** A playing hook counts as this much nearer, so two hooks at near the same distance do not swap each tick. */
    private const val PLAYING_BIAS_SQR = 0.8

    /** Client thread only. */
    private val loops = HashMap<VoidHookBlockEntity, HookLoop>()
    private val lastPower = HashMap<VoidHookBlockEntity, Int>()
    private val nearest = NearestLoopSelection<VoidHookBlockEntity>(MAX_LOOPS, PLAYING_BIAS_SQR)
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        loops.clear()
        lastPower.clear()
        nearest.clear()
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        loops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        lastPower.keys.removeIf { it.isRemoved }
        nearest.beginTick()
        for (be in VoidHookClientRegistry.all(level)) {
            if (be.isRemoved) continue
            val power = power(be)
            val previous = lastPower.put(be, power)
            val inRange = inRange(level, be, player)
            // No sound for a hook seen for the first time, so a chunk load is silent.
            if (previous != null && (previous == 0) != (power == 0) && inRange) {
                val event = if (power > 0) EKSounds.VOID_HOOK_POWER_ON else EKSounds.VOID_HOOK_POWER_OFF
                sounds.play(
                    SimpleSoundInstance(
                        event.get(), SoundSource.BLOCKS, POWER_SOUND_VOLUME, 1f, SoundInstance.createUnseededRandom(),
                        scratch.x, scratch.y, scratch.z,
                    ),
                )
            }
            if (power > 0 && inRange) nearest.offer(be, player.distanceToSqr(scratch.x, scratch.y, scratch.z), be in loops)
        }
        nearest.select()
        for (be in nearest.selected()) {
            if (be !in loops) loops[be] = HookLoop(level, be).also(sounds::play)
        }
    }

    private fun power(be: VoidHookBlockEntity): Int = be.blockState.getValue(VoidHookBlock.POWER)

    private fun targetVolume(power: Int): Float = 0.25f + 0.5f * power / 15f

    private fun targetPitch(power: Int): Float = 0.85f + 0.15f * power / 15f

    /** On a ship, the block position is a shipyard position. */
    private fun worldCentre(level: ClientLevel, be: VoidHookBlockEntity, out: Vector3d) {
        val pos = be.blockPos
        out.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(out)
    }

    /** Leaves the hook's world centre in [scratch]. */
    private fun inRange(level: ClientLevel, be: VoidHookBlockEntity, player: Player): Boolean {
        worldCentre(level, be, scratch)
        return player.distanceToSqr(scratch.x, scratch.y, scratch.z) <= START_RANGE_SQR
    }

    /** A loop at the world position of a hook. It follows the ship of the hook. */
    private class HookLoop(
        private val level: ClientLevel,
        private val be: VoidHookBlockEntity,
    ) : AbstractTickableSoundInstance(EKSounds.VOID_HOOK_LOOP.get(), SoundSource.BLOCKS,
        SoundInstance.createUnseededRandom()) {

        private val position = Vector3d()

        init {
            looping = true
            delay = 0
            val power = power(be)
            volume = targetVolume(power)
            pitch = targetPitch(power)
            move()
        }

        override fun tick() {
            val power = if (isAudible() && be in nearest) power(be) else 0
            if (power <= 0) {
                volume -= 1f / FADE_OUT_TICKS
                if (volume <= 0f) stop()
                return
            }
            volume += (targetVolume(power) - volume) * GLIDE
            pitch += (targetPitch(power) - pitch) * GLIDE
        }

        private fun isAudible(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            if (player.level() !== level || be.isRemoved) return false
            move()
            return player.distanceToSqr(x, y, z) <= START_RANGE_SQR
        }

        private fun move() {
            worldCentre(level, be, position)
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
