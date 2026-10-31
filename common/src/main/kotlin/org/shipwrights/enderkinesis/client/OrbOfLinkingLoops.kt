package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.OrbOfLinkingBlock
import org.shipwrights.enderkinesis.block.OrbRole
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingClientRegistry
import org.shipwrights.enderkinesis.item.DisintegrationTomeOrbBehavior
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.PI
import kotlin.math.abs

object OrbOfLinkingLoops {

    private const val PULSE_PERIOD_TICKS: Double = 2.0 * PI / OrbOfLinkingHazeRenderer.PULSE_FREQ
    /** The haze peak. hum_loop starts on its swell. */
    private const val PULSE_PEAK_TICKS: Double = PULSE_PERIOD_TICKS / 4.0
    /** A small margin on the 16-block audible range, so a loop is at volume before the player hears it. */
    private const val RANGE: Double = 18.0
    private const val RANGE_SQR: Double = RANGE * RANGE
    private const val FADE_TICKS: Int = 5

    private enum class Kind { HUM, BEAM, GRIND }

    private val KINDS = Kind.values()

    /** Client thread only. Indexed by [Kind.ordinal]. */
    private val loops = Array(KINDS.size) { HashMap<OrbOfLinkingBlockEntity, OrbLoop>() }
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        for (map in loops) {
            map.values.forEach(sounds::stop)
            map.clear()
        }
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        for (map in loops) map.values.removeIf { it.isStopped || !sounds.isActive(it) }
        val humStart = abs(level.gameTime % PULSE_PERIOD_TICKS - PULSE_PEAK_TICKS) < 0.5
        for (be in OrbOfLinkingClientRegistry.sendOrbs(level)) {
            if (be.isRemoved) continue
            worldCentre(level, be, scratch)
            if (player.distanceToSqr(scratch.x, scratch.y, scratch.z) > RANGE_SQR) continue
            for (kind in KINDS) {
                val map = loops[kind.ordinal]
                if (be in map || !wanted(be, kind) || (kind == Kind.HUM && !humStart)) continue
                map[be] = OrbLoop(level, be, kind).also(sounds::play)
            }
        }
    }

    private fun wanted(be: OrbOfLinkingBlockEntity, kind: Kind): Boolean = when (kind) {
        Kind.HUM -> be.blockState.getValue(OrbOfLinkingBlock.ORB_ROLE) != OrbRole.UNBOUND
        Kind.BEAM -> be.outgoingPeers(DisintegrationTomeOrbBehavior.tomeKind).isNotEmpty()
        Kind.GRIND -> be.grinding && wanted(be, Kind.BEAM)
    }

    /** On a ship, the block position is a shipyard position. */
    private fun worldCentre(level: ClientLevel, be: OrbOfLinkingBlockEntity, out: Vector3d) {
        val pos = be.blockPos
        out.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(out)
    }

    private class OrbLoop(
        private val level: ClientLevel,
        private val be: OrbOfLinkingBlockEntity,
        private val kind: Kind,
    ) : AbstractTickableSoundInstance(
        when (kind) {
            Kind.HUM -> EKSounds.ORB_OF_LINKING_HUM_LOOP.get()
            Kind.BEAM -> EKSounds.ORB_OF_LINKING_BEAM_LOOP.get()
            Kind.GRIND -> EKSounds.ORB_OF_LINKING_GRIND_LOOP.get()
        },
        SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        private val position = Vector3d()

        init {
            looping = true
            delay = 0
            volume = 1f
            move()
        }

        override fun tick() {
            val step = 1f / FADE_TICKS
            volume = if (isWanted()) (volume + step).coerceAtMost(1f) else volume - step
            if (volume <= 0f) stop()
        }

        private fun isWanted(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            if (player.level() !== level || be.isRemoved || !wanted(be, kind)) return false
            move()
            return player.distanceToSqr(x, y, z) <= RANGE_SQR
        }

        private fun move() {
            worldCentre(level, be, position)
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
