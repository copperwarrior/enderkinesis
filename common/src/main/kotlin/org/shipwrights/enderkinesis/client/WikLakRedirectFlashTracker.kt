package org.shipwrights.enderkinesis.client

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.Minecraft
import org.shipwrights.enderkinesis.entity.WikLakRedirectFlashNetwork

/** Client-side store of "player X just got Wik-Lak-redirected, draw the
 *  wik-lak skin fade on them." Maps player UUID → client-side game-time
 *  tick the fade started. Read every frame by
 *  [WikLakRedirectFlashLayer] to compute the overlay alpha. */
object WikLakRedirectFlashTracker {

    private val startTicks: ConcurrentHashMap<UUID, Long> = ConcurrentHashMap()

    /** Receiver-side trigger. Stamps the start tick from the local
     *  client's game time so the fade interpolates in the client's frame
     *  of reference (drifting from the server's tick by a few ms is
     *  fine — the visual is just a 1.5 s overlay). */
    fun startFade(uuid: UUID) {
        Minecraft.getInstance().execute {
            val gameTime = Minecraft.getInstance().level?.gameTime ?: return@execute
            startTicks[uuid] = gameTime
        }
    }

    /** Returns alpha for the wik-lak overlay on [uuid] this frame: 1.0
     *  at fade start, 0.0 (or no entry) when expired. Entries auto-evict
     *  once they roll past [WikLakRedirectFlashNetwork.FADE_TICKS] so the
     *  map size never accumulates. */
    fun alpha(uuid: UUID): Float {
        val start = startTicks[uuid] ?: return 0f
        val level = Minecraft.getInstance().level ?: return 0f
        val elapsed = level.gameTime - start
        if (elapsed < 0L) return 0f
        if (elapsed >= WikLakRedirectFlashNetwork.FADE_TICKS) {
            startTicks.remove(uuid)
            return 0f
        }
        return 1f - (elapsed.toFloat() / WikLakRedirectFlashNetwork.FADE_TICKS.toFloat())
    }
}
