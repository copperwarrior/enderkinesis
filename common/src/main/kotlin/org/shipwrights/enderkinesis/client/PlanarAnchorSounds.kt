package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.PlanarAnchorBlockEntity
import org.shipwrights.enderkinesis.blockentity.PlanarAnchorClientRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos

object PlanarAnchorSounds {

    private const val LOOP_VOLUME: Float = 1f
    private const val FADE_IN_TICKS: Int = 10
    private const val FADE_OUT_TICKS: Int = 5
    /** The latch in the fire sound lands at the end of the 6-tick extend. */
    private const val FADE_IN_DELAY_TICKS: Long = 6
    /** The range of a variable range event at volume 1, plus a small margin. */
    private const val START_RANGE: Double = 16.0 + 2.0
    private const val START_RANGE_SQR: Double = START_RANGE * START_RANGE

    /** Client thread only. */
    private val holdLoops = HashMap<PlanarAnchorBlockEntity, AnchorLoop>()
    private val portalLoops = HashMap<PlanarAnchorBlockEntity, AnchorLoop>()
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        holdLoops.values.forEach(sounds::stop)
        portalLoops.values.forEach(sounds::stop)
        holdLoops.clear()
        portalLoops.clear()
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        holdLoops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        portalLoops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        for (be in PlanarAnchorClientRegistry.all(level)) {
            if (!isHolding(be)) continue
            startIfAudible(holdLoops, level, be, player, portal = false)
            if (isPortalMode(be)) startIfAudible(portalLoops, level, be, player, portal = true)
        }
    }

    private fun startIfAudible(
        loops: MutableMap<PlanarAnchorBlockEntity, AnchorLoop>,
        level: ClientLevel, be: PlanarAnchorBlockEntity, player: Player, portal: Boolean,
    ) {
        if (be in loops) return
        locate(level, be, portal, scratch)
        if (player.distanceToSqr(scratch.x, scratch.y, scratch.z) > START_RANGE_SQR) return
        loops[be] = AnchorLoop(level, be, portal).also(Minecraft.getInstance().soundManager::play)
    }

    private fun isHolding(be: PlanarAnchorBlockEntity): Boolean = !be.isRemoved && be.isActive && !be.isRetracting

    /** The same test as the spiral in [PlanarAnchorRenderer]. */
    private fun isPortalMode(be: PlanarAnchorBlockEntity): Boolean =
        be.cloudWorld != null && !(be.tetheredToShip || be.chainTargetAnchorPos != null)

    /** On a ship, the block position is a shipyard position. `cloudWorld` is a world position. */
    private fun locate(level: ClientLevel, be: PlanarAnchorBlockEntity, portal: Boolean, out: Vector3d) {
        val cloud = be.cloudWorld
        if (portal && cloud != null) {
            out.set(cloud.x, cloud.y, cloud.z)
            return
        }
        val pos = be.blockPos
        out.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(out)
    }

    private class AnchorLoop(
        private val level: ClientLevel,
        private val be: PlanarAnchorBlockEntity,
        private val portal: Boolean,
    ) : AbstractTickableSoundInstance(
        (if (portal) EKSounds.PLANAR_ANCHOR_PORTAL else EKSounds.PLANAR_ANCHOR_HOLD).get(),
        SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        private val position = Vector3d()

        init {
            looping = true
            delay = 0
            volume = 0f
            move()
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            if (!isAudible()) {
                volume -= LOOP_VOLUME / FADE_OUT_TICKS
                if (volume <= 0f) stop()
                return
            }
            if (level.gameTime - be.clientPhaseStartTick < FADE_IN_DELAY_TICKS) return
            volume = (volume + LOOP_VOLUME / FADE_IN_TICKS).coerceAtMost(LOOP_VOLUME)
        }

        private fun isAudible(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            if (player.level() !== level || !isHolding(be) || (portal && !isPortalMode(be))) return false
            move()
            return player.distanceToSqr(x, y, z) <= START_RANGE_SQR
        }

        private fun move() {
            locate(level, be, portal, position)
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
