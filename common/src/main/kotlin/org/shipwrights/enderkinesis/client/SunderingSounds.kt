package org.shipwrights.enderkinesis.client

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingManager
import org.shipwrights.enderkinesis.registry.EKSounds

object SunderingSounds {

    private class Session(var stage: Int, var beamLoop: LoopSound, var impactLoop: LoopSound? = null)

    /** Client thread only. */
    private val sessions: MutableMap<Player, Session> = HashMap()

    fun tickWielder(level: ClientLevel, player: Player, elapsed: Int, trace: BeamTrace) {
        val stage = SunderingManager.stageOf(elapsed)
        val pos = player.eyePosition
        var session = sessions[player]
        if (session == null) {
            playOnce(level, player, EKSounds.SUNDERING_START)
            session = Session(stage, startLoop(player, beamLoopEvent(stage), pos, positional = !isListener(player)))
            sessions[player] = session
        } else if (stage != session.stage) {
            if (stage > session.stage) playOnce(level, player, EKSounds.SUNDERING_STAGE_UP)
            session.beamLoop.end()
            session.beamLoop = startLoop(player, beamLoopEvent(stage), pos, positional = !isListener(player))
            session.stage = stage
        } else {
            session.beamLoop.moveTo(pos)
        }
        tickImpact(player, session, elapsed, trace)
    }

    private fun tickImpact(player: Player, session: Session, elapsed: Int, trace: BeamTrace) {
        val canMine = SunderingManager.rampFactor(elapsed) >= SunderingManager.MINING_ENABLE_RAMP
        val impactPos = if (canMine && trace.finalBlockPos != null) trace.segments.lastOrNull()?.end else null
        val impact = session.impactLoop
        if (impactPos == null) {
            impact?.end()
            session.impactLoop = null
        } else if (impact == null) {
            session.impactLoop = startLoop(player, EKSounds.SUNDERING_IMPACT_LOOP.get(), impactPos, positional = true)
        } else {
            impact.moveTo(impactPos)
        }
    }

    fun endStaleSessions(level: ClientLevel) {
        if (sessions.isEmpty()) return
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val (player, session) = iterator.next()
            val present = !player.isRemoved && player.level() === level
            if (present && SunderingClient.isLocalWieldingSundering(player)) continue
            session.beamLoop.end()
            session.impactLoop?.end()
            if (present) playOnce(level, player, EKSounds.SUNDERING_STOP)
            iterator.remove()
        }
    }

    private fun beamLoopEvent(stage: Int): SoundEvent =
        EKSounds.SUNDERING_BEAM_LOOPS[(stage - 1).coerceIn(0, EKSounds.SUNDERING_BEAM_LOOPS.lastIndex)].get()

    private fun isListener(player: Player): Boolean = player === Minecraft.getInstance().cameraEntity

    // A source at the listener position pans at random, because the source moves each tick and the listener each frame.
    private fun playOnce(level: ClientLevel, player: Player, event: RegistrySupplier<SoundEvent>) {
        if (isListener(player)) {
            Minecraft.getInstance().soundManager.play(
                SimpleSoundInstance(
                    event.get().location, SoundSource.PLAYERS, 1f, 1f, SoundInstance.createUnseededRandom(),
                    false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
                )
            )
            return
        }
        val pos = player.eyePosition
        level.playLocalSound(pos.x, pos.y, pos.z, event.get(), SoundSource.PLAYERS, 1f, 1f, false)
    }

    private fun startLoop(owner: Player, event: SoundEvent, pos: Vec3, positional: Boolean): LoopSound {
        val loop = LoopSound(owner, event, pos, positional)
        Minecraft.getInstance().soundManager.play(loop)
        return loop
    }

    private class LoopSound(
        private val owner: Player, event: SoundEvent, pos: Vec3, private val positional: Boolean,
    ) : AbstractTickableSoundInstance(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom()) {

        init {
            looping = true
            delay = 0
            if (positional) {
                moveTo(pos)
            } else {
                relative = true
                attenuation = SoundInstance.Attenuation.NONE
            }
        }

        fun moveTo(pos: Vec3) {
            if (!positional) return
            x = pos.x
            y = pos.y
            z = pos.z
        }

        fun end() = stop()

        // No level tick runs after a disconnect, thus endStaleSessions cannot stop the loop.
        override fun tick() {
            if (owner.isRemoved) stop()
        }
    }
}
