package org.shipwrights.enderkinesis.ritual

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent

/** Ritual effects that run for a number of server ticks. Server thread only; not saved, so a restart ends them. */
object RitualTasks {

    private class Task(val ticks: Int, val step: (tick: Int) -> Unit) {
        var done = 0
    }

    private val tasks = ArrayList<Task>()

    fun init() {
        TickEvent.SERVER_POST.register { _ -> tick() }
        LifecycleEvent.SERVER_STOPPED.register { _ -> tasks.clear() }
    }

    /** Calls [step] once a tick, with ticks 0 until [ticks]. */
    fun run(ticks: Int, step: (tick: Int) -> Unit) {
        require(ticks > 0) { "a ritual task needs at least one tick" }
        tasks.add(Task(ticks, step))
    }

    private fun tick() {
        if (tasks.isEmpty()) return
        val iterator = tasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            task.step(task.done++)
            if (task.done >= task.ticks) iterator.remove()
        }
    }
}
