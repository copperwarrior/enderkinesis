package org.shipwrights.enderkinesis.gametest

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.shipwrights.enderkinesis.construction.BuildLog
import org.shipwrights.enderkinesis.entity.ConstriliarMoveLog

/** The last `[ConstriliarBuild]` and `[ConstriliarMove]` lines, so a failed test says why the build stopped. Any thread. */
internal class BuildLogTail private constructor() : AbstractAppender(NAME, null, null, true, Property.EMPTY_ARRAY) {
    private val lock = Any()
    private val jobLines = ArrayDeque<String>()
    private val workerLines = ArrayDeque<String>()
    private val moveLines = ArrayDeque<String>()
    private var lastStall: String? = null
    private var lastJobEnd: String? = null

    override fun append(event: LogEvent) {
        val logger = event.loggerName
        if (logger != BUILD_LOGGER && logger != MOVE_LOGGER) return
        val text = event.message?.formattedMessage ?: return
        synchronized(lock) {
            when {
                logger == MOVE_LOGGER -> keep(moveLines, text)
                WORKER_MARK in text -> keep(workerLines, text)
                else -> {
                    keep(jobLines, text)
                    if (STALL_MARK in text) lastStall = text
                    if (END_MARKS.any { it in text }) lastJobEnd = text
                }
            }
        }
    }

    fun lastJobEnd(): String = synchronized(lock) { lastJobEnd ?: "no job end line" }

    fun describe(): String = synchronized(lock) {
        buildString {
            appendLine("last SECTION_STALLED: ${lastStall ?: "none"}")
            appendLines("last job lines", jobLines)
            appendLines("last worker and carrier lines", workerLines)
            appendLines("last walker move lines", moveLines)
        }
    }

    fun uninstall() {
        (LogManager.getRootLogger() as? Logger)?.removeAppender(this)
        stop()
    }

    private fun keep(lines: ArrayDeque<String>, text: String) {
        if (lines.size == KEEP) lines.removeFirst()
        lines.addLast(text)
    }

    private fun StringBuilder.appendLines(title: String, lines: Collection<String>) {
        appendLine("$title:")
        if (lines.isEmpty()) appendLine("  none")
        for (line in lines) appendLine("  $line")
    }

    companion object {
        private const val NAME = "EnderkinesisGameTestTail"
        private const val KEEP = 10
        private const val WORKER_MARK = " worker="
        private const val STALL_MARK = "reason=SECTION_STALLED"
        private val END_MARKS = listOf("reason=JOB_ENDED", "reason=JOB_CANCELLED", "reason=JOB_FINISHED")
        private val BUILD_LOGGER = BuildLog::class.java.name
        private val MOVE_LOGGER = ConstriliarMoveLog::class.java.name

        /** Null when the logging back end is not log4j core; the report then has no log lines. */
        fun install(): BuildLogTail? {
            val root = LogManager.getRootLogger() as? Logger ?: return null
            return BuildLogTail().also {
                it.start()
                root.addAppender(it)
            }
        }
    }
}
