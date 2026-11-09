package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.events.common.LifecycleEvent

/**
 * World-seed salt for each Wohlon worldgen hash. The hashes are static functions that runtime
 * code calls too (the world-root grower), so the salt cannot live on the
 * chunk generator instance.
 */
object WohlonWorldSeed {

    // Not volatile: the hashes read it in the hottest worldgen loops. The one write is on the
    // server thread before the first chunk task is queued, and the queue hand-off publishes it.
    @JvmField var salt: Int = 0

    fun init() {
        LifecycleEvent.SERVER_BEFORE_START.register { server ->
            val seed = server.worldData.worldGenOptions().seed()
            salt = (seed xor (seed ushr 32)).toInt() * 0x9E3779B1.toInt()
        }
        LifecycleEvent.SERVER_STOPPED.register { _ -> salt = 0 }
    }
}

/** The Wohlon worldgen hash of [a], [b] and [salt], mixed with the world-seed salt. */
fun wohlonHash32(a: Int, b: Int, salt: Int): Int {
    var h = a * 0x9E3779B1.toInt() xor (b * 0x85EBCA77.toInt()) xor (salt * 0xC2B2AE3D.toInt()) xor WohlonWorldSeed.salt
    h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
    h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
    h = h xor (h ushr 15)
    return h
}

/** [wohlonHash32] as a value in 0 until 1. */
fun wohlonHash01(a: Int, b: Int, salt: Int): Double = (wohlonHash32(a, b, salt) and 0x7FFFFFFF) / 2147483648.0
