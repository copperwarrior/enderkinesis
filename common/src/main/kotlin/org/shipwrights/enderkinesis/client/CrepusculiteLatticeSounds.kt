package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.CrepusculiteLatticeBlock
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.physics.LatticeRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipManagingPos

object CrepusculiteLatticeSounds {

    private const val HUM_MAX_VOLUME: Float = 1f
    private const val FADE_TICKS: Int = 20
    private const val SCAN_INTERVAL_TICKS: Int = 10
    /** A variable-range event at volume 1 or less is silent beyond 16 blocks. */
    private const val EARSHOT: Double = 24.0
    private const val EARSHOT_SQR: Double = EARSHOT * EARSHOT
    private const val WAKE_MAX_VOLUME: Float = 1f
    /** Horizontal ship speed (blocks per second) at which the wake is at full volume. */
    private const val WAKE_FULL_SPEED: Double = 8.0
    private const val BED_MAX_VOLUME: Float = 1f
    /** Scale on the catch-zone ellipsoid. The ocean bed plays inside the scaled ellipsoid. */
    private const val BED_REACH: Double = 1.25
    private const val BED_REACH_SQR: Double = BED_REACH * BED_REACH

    /** Client thread only. */
    private val hums = HashMap<CrepusculiteLatticeBlockEntity, LatticeLoop>()
    private val wakes = HashMap<CrepusculiteLatticeBlockEntity, LatticeLoop>()
    private var bed: BedLoop? = null
    private var scanCooldown = 0

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        hums.values.forEach(sounds::stop)
        hums.clear()
        wakes.values.forEach(sounds::stop)
        wakes.clear()
        bed?.let(sounds::stop)
        bed = null
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        tickBed(level, player)
        if (--scanCooldown > 0) return
        scanCooldown = SCAN_INTERVAL_TICKS
        // A level change stops all sounds and does not call stop() on the instance.
        hums.values.removeIf { it.isStopped || !sounds.isActive(it) }
        wakes.values.removeIf { it.isStopped || !sounds.isActive(it) }

        // The registry holds one lattice for each ship, and no lattice that is not on a ship.
        for ((be, _) in LatticeRegistry.entries(level)) {
            if (be !in hums) start(hums, HumLoop(level, be))
            if (be !in wakes) start(wakes, WakeLoop(level, be))
        }
    }

    private fun tickBed(level: ClientLevel, player: Player) {
        val target = bedVolume(level, player)
        if (target <= 0f) return
        val sounds = Minecraft.getInstance().soundManager
        val existing = bed
        // A level change stops all sounds and does not call stop() on the instance.
        if (existing != null && !existing.isStopped && sounds.isActive(existing)) {
            existing.target = target
            return
        }
        existing?.let(sounds::stop)
        bed = BedLoop(target).also(sounds::play)
    }

    /** The loudest ocean that has the player in its reach. 0 when there is none. */
    private fun bedVolume(level: ClientLevel, player: Player): Float {
        var best = 0f
        for ((be, zone) in LatticeRegistry.entries(level)) {
            val nx = zone.localNx(player.x, player.z)
            val ny = (player.y - zone.cy) / zone.ay
            val nz = zone.localNz(player.x, player.z)
            if (nx * nx + ny * ny + nz * nz > BED_REACH_SQR) continue
            val volume = BED_MAX_VOLUME * strength(be) * zone.waveIntensity.toFloat()
            if (volume > best) best = volume
        }
        return best.coerceAtMost(BED_MAX_VOLUME)
    }

    private fun power(be: CrepusculiteLatticeBlockEntity): Int = be.blockState.getValue(BlockStateProperties.POWER)

    /** 1 at power 0. 0 at power 15. */
    private fun strength(be: CrepusculiteLatticeBlockEntity): Float {
        val off = CrepusculiteLatticeBlock.OFF_POWER
        return (off - power(be)).coerceAtLeast(0).toFloat() / off
    }

    private fun faded(volume: Float, target: Float, max: Float): Float {
        val step = max / FADE_TICKS
        return if (volume < target) (volume + step).coerceAtMost(target) else (volume - step).coerceAtLeast(target)
    }

    /** Not positional: the ocean is around the player. */
    private class BedLoop(var target: Float) : AbstractTickableSoundInstance(
        EKSounds.CREPUSCULITE_LATTICE_OCEAN_BED.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        init {
            looping = true
            delay = 0
            volume = 0f
            relative = true
            attenuation = SoundInstance.Attenuation.NONE
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            volume = faded(volume, target, BED_MAX_VOLUME)
            if (target <= 0f && volume <= 0f) stop()
            // [tickBed] sets the target again each tick while an ocean is in reach.
            target = 0f
        }
    }

    private fun start(loops: MutableMap<CrepusculiteLatticeBlockEntity, LatticeLoop>, loop: LatticeLoop) {
        if (!loop.audible() || loop.gain() <= 0f) return
        loops[loop.be] = loop
        Minecraft.getInstance().soundManager.play(loop)
    }

    private class HumLoop(level: ClientLevel, be: CrepusculiteLatticeBlockEntity) :
        LatticeLoop(level, be, EKSounds.CREPUSCULITE_LATTICE_HUM.get(), HUM_MAX_VOLUME) {

        override fun gain(): Float = HUM_MAX_VOLUME * strength(be)
    }

    private class WakeLoop(level: ClientLevel, be: CrepusculiteLatticeBlockEntity) :
        LatticeLoop(level, be, EKSounds.CREPUSCULITE_LATTICE_WAKE.get(), WAKE_MAX_VOLUME) {

        override fun gain(): Float {
            val velocity = ship?.velocity ?: return 0f
            val speed = Math.hypot(velocity.x(), velocity.z())
            return WAKE_MAX_VOLUME * (speed / WAKE_FULL_SPEED).coerceAtMost(1.0).toFloat()
        }
    }

    /** A loop at the world position of a lattice. It follows the ship of the lattice. */
    private abstract class LatticeLoop(
        private val level: ClientLevel,
        val be: CrepusculiteLatticeBlockEntity,
        event: SoundEvent,
        private val maxVolume: Float,
    ) : AbstractTickableSoundInstance(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom()) {

        private val scratch = Vector3d()
        protected var ship: Ship? = null
            private set

        init {
            looping = true
            delay = 0
            volume = 0f
            moveToBlock()
        }

        /** The volume when the loop is audible. */
        abstract fun gain(): Float

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val mc = Minecraft.getInstance()
            if (be.isRemoved || mc.level !== level) {
                fadeTo(0f)
                return
            }
            moveToBlock()
            fadeTo(if (audible()) gain() else 0f)
        }

        /** Call [moveToBlock] first. The range check uses the sound position. */
        fun audible(): Boolean {
            if (be.isRemoved || power(be) >= CrepusculiteLatticeBlock.OFF_POWER) return false
            if (ship == null || !LatticeRegistry.isPublished(level, be)) return false
            val player = Minecraft.getInstance().player ?: return false
            return player.distanceToSqr(x, y, z) <= EARSHOT_SQR
        }

        private fun fadeTo(target: Float) {
            volume = faded(volume, target, maxVolume)
            if (target <= 0f && volume <= 0f) stop()
        }

        private fun moveToBlock() {
            val pos = be.blockPos
            val managing = level.getShipManagingPos(pos)
            ship = managing
            scratch.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            managing?.shipToWorld?.transformPosition(scratch)
            x = scratch.x
            y = scratch.y
            z = scratch.z
        }
    }
}
