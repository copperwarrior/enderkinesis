package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.entity.CatalogerTomePath
import org.shipwrights.enderkinesis.registry.EKSounds

/** Sounds of the madness tome summon. They follow the same path math as [CatalogerTomeRender]. */
object PlayerTomeSummonSounds {

    private const val FLIGHT_VOLUME: Float = 0.8f
    private const val CUE_VOLUME: Float = 0.6f
    private const val READ_VOLUME: Float = 0.5f
    private const val READ_FADE_TICKS: Int = 10
    private const val DWELL_START: Int = Cataloger.TOME_OUTBOUND_TICKS
    private const val IN_START: Int = Cataloger.TOME_OUTBOUND_TICKS + Cataloger.TOME_DWELL_TICKS
    private const val CLOSE_TICK: Int = 345

    private class Track(val state: PlayerTomeSummonClient.State, var lastElapsed: Int)

    /** Client thread only. */
    private val tracks = HashMap<UUID, Track>()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        tracks.clear()
    }

    private fun tick(level: ClientLevel) {
        val active = PlayerTomeSummonClient.all()
        // A new BEGIN replaces the state object, so an identity check also catches a new summon.
        tracks.entries.removeIf { (uuid, track) -> active[uuid] !== track.state }
        if (active.isEmpty()) return
        val sounds = Minecraft.getInstance().soundManager
        for ((uuid, state) in active) {
            val player = level.getPlayerByUUID(uuid) ?: continue
            val elapsed = player.tickCount - state.startTick
            // A summon seen late gets no catch-up cues.
            val track = tracks.getOrPut(uuid) { Track(state, if (elapsed <= 1) -1 else elapsed - 1) }
            val last = track.lastElapsed
            track.lastElapsed = elapsed
            if (crossed(last, elapsed, 0)) sounds.play(FlightSound(player, state))
            if (crossed(last, elapsed, DWELL_START)) {
                playAtHold(player, EKSounds.SSELITH_TOME_OPEN.get())
                sounds.play(ReadLoop(player, state))
            }
            if (crossed(last, elapsed, CLOSE_TICK)) playAtHold(player, EKSounds.SSELITH_TOME_CLOSE.get())
            if (state.returnsToShelf && crossed(last, elapsed, IN_START)) sounds.play(FlightSound(player, state))
        }
    }

    private fun crossed(last: Int, now: Int, tick: Int): Boolean = last < tick && now >= tick

    private fun holdPoint(player: Player): Triple<Double, Double, Double> =
        CatalogerTomePath.holdPoint(player.x, player.eyeY, player.z, player.yHeadRot, player.xRot)

    private fun playAtHold(player: Player, event: SoundEvent) {
        val (x, y, z) = holdPoint(player)
        Minecraft.getInstance().soundManager.play(
            SimpleSoundInstance(
                event, SoundSource.PLAYERS, CUE_VOLUME, 1f, SoundInstance.createUnseededRandom(), x, y, z,
            ),
        )
    }

    private fun isCurrent(player: Player, state: PlayerTomeSummonClient.State): Boolean =
        !player.isRemoved && PlayerTomeSummonClient.get(player) === state

    /** One flight. It stays at its last position when the summon ends early. */
    private class FlightSound(
        private val player: Player,
        private val state: PlayerTomeSummonClient.State,
    ) : AbstractTickableSoundInstance(
        EKSounds.SSELITH_TOME_FLIGHT.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom(),
    ) {

        init {
            volume = FLIGHT_VOLUME
            move()
        }

        override fun tick() {
            if (isCurrent(player, state)) move()
        }

        private fun move() {
            val elapsed = (player.tickCount - state.startTick).toFloat()
            val phase = CatalogerTomePath.phaseOf(elapsed)
            val (holdX, holdY, holdZ) = holdPoint(player)
            val source = state.sourceBookshelf
            val target = state.returnBookshelf
            val (px, py, pz) = CatalogerTomePath.computePosition(
                phase, CatalogerTomePath.progressInPhase(phase, elapsed),
                source.x + 0.5, source.y + 0.5, source.z + 0.5,
                target.x + 0.5, target.y + 0.5, target.z + 0.5,
                holdX, holdY, holdZ,
                player.x, player.eyeY, player.z,
            )
            x = px
            y = py
            z = pz
        }
    }

    private class ReadLoop(
        private val player: Player,
        private val state: PlayerTomeSummonClient.State,
    ) : AbstractTickableSoundInstance(
        EKSounds.SSELITH_TOME_READ_LOOP.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom(),
    ) {

        init {
            looping = true
            delay = 0
            volume = 0f
            move()
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val elapsed = player.tickCount - state.startTick
            if (isCurrent(player, state) && elapsed < IN_START) {
                move()
                val fadeIn = (elapsed - DWELL_START).toFloat() / READ_FADE_TICKS
                val fadeOut = (IN_START - elapsed).toFloat() / READ_FADE_TICKS
                volume = READ_VOLUME * minOf(fadeIn, fadeOut).coerceIn(0f, 1f)
                return
            }
            // After an early end, fade out at the same rate.
            volume -= READ_VOLUME / READ_FADE_TICKS
            if (volume <= 0f) stop()
        }

        private fun move() {
            val (hx, hy, hz) = holdPoint(player)
            x = hx
            y = hy
            z = hz
        }
    }
}
