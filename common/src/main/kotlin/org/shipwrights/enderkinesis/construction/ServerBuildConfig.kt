package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.platform.Platform
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Loads [BuildConfig] from `config/enderkinesis-server.properties` at server start; read [current] anywhere. */
object ServerBuildConfig {
    private val LOG = LogUtils.getLogger()
    const val FILE_NAME = "enderkinesis-server.properties"

    // Set once per server start on the server thread; workers read the immutable snapshot.
    @Volatile
    private var loaded: BuildConfig = BuildConfig.DEFAULT

    val current: BuildConfig get() = loaded

    /** Registers the load. Call before any other SERVER_STARTING listener that reads [current]. */
    fun init() {
        LifecycleEvent.SERVER_STARTING.register { loaded = load(Platform.getConfigFolder().resolve(FILE_NAME)) }
        LifecycleEvent.SERVER_STOPPED.register { loaded = BuildConfig.DEFAULT }
    }

    /** A missing or damaged file gets the corrected values written back; a read failure keeps the defaults. */
    fun load(file: Path): BuildConfig {
        val values = HashMap<String, String>()
        if (Files.isRegularFile(file)) {
            try {
                Files.newBufferedReader(file).use { reader -> Properties().also { it.load(reader) } }
                    .forEach { (key, value) -> values[key.toString()] = value.toString() }
            } catch (e: IOException) {
                LOG.error("Cannot read {}; using the defaults", file, e)
                return BuildConfig.DEFAULT
            } catch (e: IllegalArgumentException) {
                LOG.error("Cannot read {}; using the defaults", file, e)
                return BuildConfig.DEFAULT
            }
        }
        val parsed = parseBuildConfig(values)
        if (values.isNotEmpty()) for (problem in parsed.problems) LOG.warn("{}: {}", FILE_NAME, problem)
        if (parsed.problems.isNotEmpty()) write(file, parsed.config)
        return parsed.config
    }

    private fun write(file: Path, config: BuildConfig) {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, config.toFileText())
        } catch (e: IOException) {
            LOG.error("Cannot write {}", file, e)
        }
    }
}
