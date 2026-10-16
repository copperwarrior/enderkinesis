package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.dimension.SselithBookshelfScan
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKSounds

object SselithInvocationGlyphs {
    private const val SCAN_INTERVAL_TICKS = 10L
    private const val MAX_SOURCE_SHELVES = 16
    private const val GLYPHS_PER_SCAN = 2
    private const val SPAWN_SPREAD = 1.2
    private const val LOOP_VOLUME = 0.4f
    private const val LOOP_FADE_TICKS = 20

    /** Client thread only. True while glyphs spawn for the local player. */
    private var localActive = false
    private var loop: FlatFadeLoop? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    fun onDisconnect() {
        loop?.let(Minecraft.getInstance().soundManager::stop)
        loop = null
        localActive = false
    }

    private fun tick(level: ClientLevel) {
        if (level.dimension() == SselithRepertory.LEVEL_KEY) {
            localActive = false
            return
        }
        if (level.gameTime % SCAN_INTERVAL_TICKS != 0L || Minecraft.getInstance().isPaused) return
        val rng = level.random
        val shelves = ArrayList<Vec3>()
        localActive = false
        for (player in level.players()) {
            shelves.clear()
            val count = SselithBookshelfScan.collect(level, player.x, player.y, player.z, MAX_SOURCE_SHELVES, shelves)
            if (count < SselithBookshelfScan.MIN_BOOKSHELVES) continue
            if (player.isLocalPlayer) localActive = true
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
        if (localActive && loop?.isPlaying() != true) {
            loop = FlatFadeLoop(EKSounds.SSELITH_INVOCATION.get(), LOOP_VOLUME, LOOP_FADE_TICKS, LOOP_FADE_TICKS) {
                if (localActive) 1f else 0f
            }.also(Minecraft.getInstance().soundManager::play)
        }
    }
}
