package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.registry.EKParticles
import kotlin.math.sqrt

/** Sparse dust motes that drift slowly both ways along the line between a tome summoner
 *  (Cataloger or player) and the bookshelf of the current phase. */
object TomeSummonBeam {
    private const val SPAWN_INTERVAL_TICKS = 4
    private const val MOTE_SPEED = 0.02
    private const val SPREAD = 0.5
    private const val INBOUND_START_TICKS = Cataloger.TOME_OUTBOUND_TICKS + Cataloger.TOME_DWELL_TICKS

    fun clientTick(level: ClientLevel) {
        if (Minecraft.getInstance().isPaused) return
        for (entity in level.entitiesForRendering()) {
            // The entity id staggers summoners so their motes do not all spawn on one tick.
            val phase = entity.tickCount + entity.id
            if (phase % SPAWN_INTERVAL_TICKS != 0) continue
            val shelf = when (entity) {
                is Cataloger -> catalogerShelf(entity)
                is Player -> playerShelf(entity)
                else -> null
            } ?: continue
            spawnMote(level, entity, shelf, towardShelf = (phase / SPAWN_INTERVAL_TICKS) % 2 == 0)
        }
    }

    private fun catalogerShelf(cataloger: Cataloger): BlockPos? {
        val source = cataloger.tomeSummonBookshelf ?: return null
        val start = cataloger.clientTomeSummonStartTick
        val inbound = start >= 0 && cataloger.tickCount - start >= INBOUND_START_TICKS
        return if (inbound) cataloger.tomeReturnBookshelf ?: source else source
    }

    private fun playerShelf(player: Player): BlockPos? {
        val state = PlayerTomeSummonClient.get(player) ?: return null
        val inbound = player.tickCount - state.startTick >= INBOUND_START_TICKS
        return if (inbound) state.returnBookshelf else state.sourceBookshelf
    }

    private fun spawnMote(level: ClientLevel, holder: Entity, shelf: BlockPos, towardShelf: Boolean) {
        val dx = shelf.x + 0.5 - holder.x
        val dy = shelf.y + 0.5 - holder.eyeY
        val dz = shelf.z + 0.5 - holder.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1.0e-3) return
        val random = level.random
        val along = random.nextDouble()
        val speed = (if (towardShelf) MOTE_SPEED else -MOTE_SPEED) / length
        level.addParticle(
            EKParticles.sselithBeamDust(),
            holder.x + dx * along + (random.nextDouble() - 0.5) * 2.0 * SPREAD,
            holder.eyeY + dy * along + (random.nextDouble() - 0.5) * 2.0 * SPREAD,
            holder.z + dz * along + (random.nextDouble() - 0.5) * 2.0 * SPREAD,
            dx * speed, dy * speed, dz * speed,
        )
    }
}
