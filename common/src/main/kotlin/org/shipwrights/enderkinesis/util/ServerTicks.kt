package org.shipwrights.enderkinesis.util

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import java.util.PriorityQueue
import net.minecraft.server.MinecraftServer

/**
 * Runs work after a real number of server ticks. A vanilla `TickTask` is not a delay: the
 * server runs it as soon as it has spare time, and uses its tick only to force old tasks.
 */
object ServerTicks {
    private val LOG = LogUtils.getLogger()

    private class Entry(val dueTick: Int, val order: Long, val action: () -> Unit)

    // Game thread only.
    private val queue = PriorityQueue<Entry>(compareBy<Entry>({ it.dueTick }, { it.order }))
    private var nextOrder = 0L

    /** The server tick count, for physics-thread staleness tests. A wall-clock test fails
     *  when the tick rate is low or when ticks catch up. */
    @Volatile
    @JvmStatic
    var now: Int = 0
        private set

    fun init() {
        TickEvent.SERVER_PRE.register(TickEvent.Server { server -> now = server.tickCount })
        TickEvent.SERVER_POST.register(TickEvent.Server(::runDue))
        LifecycleEvent.SERVER_STOPPING.register { queue.clear() }
    }

    /** Game thread only. [ticks] is at least 1, so a run never picks up work it queued itself. */
    fun runAfter(server: MinecraftServer, ticks: Int, action: () -> Unit) {
        require(ticks >= 1) { "delay must be at least one tick, was $ticks" }
        check(server.isSameThread) { "ServerTicks.runAfter must be called on the server thread" }
        queue.add(Entry(server.tickCount + ticks, nextOrder++, action))
    }

    private fun runDue(server: MinecraftServer) {
        val now = server.tickCount
        while (true) {
            val next = queue.peek() ?: return
            if (next.dueTick > now) return
            queue.poll()
            try {
                next.action()
            } catch (e: Exception) {
                LOG.error("Scheduled server task failed", e)
            }
        }
    }
}
