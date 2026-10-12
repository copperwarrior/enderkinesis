package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.dimension.SselithBookshelfScan
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKParticles

object SselithInvocationGlyphs {
    private const val SCAN_INTERVAL_TICKS = 10L
    private const val MAX_SOURCE_SHELVES = 16
    private const val GLYPHS_PER_SCAN = 2
    private const val SPAWN_SPREAD = 1.2

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        if (level.dimension() == SselithRepertory.LEVEL_KEY) return
        if (level.gameTime % SCAN_INTERVAL_TICKS != 0L || Minecraft.getInstance().isPaused) return
        val rng = level.random
        val shelves = ArrayList<Vec3>()
        for (player in level.players()) {
            shelves.clear()
            val count = SselithBookshelfScan.collect(level, player.x, player.y, player.z, MAX_SOURCE_SHELVES, shelves)
            if (count < SselithBookshelfScan.MIN_BOOKSHELVES) continue
            repeat(GLYPHS_PER_SCAN) {
                val shelf = shelves[rng.nextInt(shelves.size)]
                level.addParticle(
                    EKParticles.sselithInvocationGlyph(),
                    shelf.x + (rng.nextDouble() - 0.5) * SPAWN_SPREAD,
                    shelf.y + (rng.nextDouble() - 0.5) * SPAWN_SPREAD,
                    shelf.z + (rng.nextDouble() - 0.5) * SPAWN_SPREAD,
                    player.id.toDouble(), 0.0, 0.0,
                )
            }
        }
    }
}
