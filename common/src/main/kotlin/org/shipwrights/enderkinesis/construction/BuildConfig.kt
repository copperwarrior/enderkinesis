package org.shipwrights.enderkinesis.construction

/** Constriliar build tuning, per job. */
data class BuildTuning(
    val picksPerTick: Int = 8,
    val maxLinkedSupplies: Int = 8,
    val orderBudget: Int = 4096,
) {
    init {
        require(picksPerTick > 0 && maxLinkedSupplies > 0 && orderBudget > 0) { "build tuning values must be positive" }
    }
}

/** The server config: plan limits and build tuning. Read at server start from [ServerBuildConfig.FILE_NAME]. */
data class BuildConfig(
    val plans: PlanLimits = PlanLimits(),
    val build: BuildTuning = BuildTuning(),
) {
    companion object {
        val DEFAULT = BuildConfig()
    }
}

/** A parsed config and one line per value that was missing, unknown, not a number, or out of range. */
data class ParsedBuildConfig(val config: BuildConfig, val problems: List<String>)

private class IntKey(
    val name: String,
    val comment: String,
    val min: Int,
    val max: Int,
    val read: (BuildConfig) -> Int,
)

private const val KIB = 1024
private const val MIB = KIB * KIB

// The order is the order in the file.
private val KEYS = listOf(
    IntKey("plans.maxFileMegabytes", "Largest structure file a player can upload, in megabytes (1,000,000 bytes). Singleplayer has no limit.", 1, 1_000_000) {
        it.plans.maxFileMegabytes
    },
    IntKey("plans.uploadPacketBytes", "Size of one upload packet, in bytes.", PlanLimits.MIN_PACKET, PlanUploadStream.CHUNK) { it.plans.uploadPacketBytes },
    IntKey("plans.uploadPacketsInFlight", "Upload packets a client can send before the server acknowledges them.", 1, 64) {
        it.plans.uploadWindow
    },
    IntKey("plans.uploadBytesPerTick", "Upload bytes the server writes per player per tick. At least one packet.", PlanLimits.MIN_PACKET, 16 * MIB) {
        it.plans.uploadBytesPerTick
    },
    IntKey("plans.sendBytesPerTick", "Plan bytes the server sends per player per tick. At least one packet goes.", KIB, 16 * MIB) {
        it.plans.sendBytesPerTick
    },
    IntKey("plans.serverSectionCache", "Decoded 16x16x16 plan sections the server keeps in memory.", 64, 65536) { it.plans.serverSectionCache },
    IntKey("plans.openFiles", "Plan files the server keeps open.", 1, 256) { it.plans.openPlans },
    IntKey("plans.captureSectionsPerTick", "Plan sections (16x16x16 blocks) the server reads per tick, shared by all saves.", 1, 256) {
        it.plans.captureSectionsPerTick
    },
    IntKey("build.picksPerTick", "Block picks per tick per build job, shared by its workers.", 1, 256) { it.build.picksPerTick },
    IntKey("build.maxLinkedSupplies", "Supply containers one build can link.", 1, 64) { it.build.maxLinkedSupplies },
    IntKey("build.orderBudget", "Cells per tick per build job that the build order visits.", 256, 65536) { it.build.orderBudget },
)

/** Pure: reads the values, keeps defaults for bad ones, and lists each problem. */
fun parseBuildConfig(values: Map<String, String>): ParsedBuildConfig {
    val problems = ArrayList<String>()
    val known = KEYS.mapTo(HashSet()) { it.name }
    for (name in values.keys) if (name !in known) problems += "unknown key $name"
    val ints = KEYS.associate { key -> key.name to readKey(key, values[key.name]?.trim(), problems) }
    val packet = ints.getValue("plans.uploadPacketBytes")
    var uploadRate = ints.getValue("plans.uploadBytesPerTick")
    if (uploadRate < packet) {
        problems += "plans.uploadBytesPerTick $uploadRate is below plans.uploadPacketBytes; using $packet"
        uploadRate = packet
    }
    val config = BuildConfig(
        PlanLimits(
            maxFileMegabytes = ints.getValue("plans.maxFileMegabytes"),
            uploadPacketBytes = packet,
            uploadWindow = ints.getValue("plans.uploadPacketsInFlight"),
            uploadBytesPerTick = uploadRate,
            sendBytesPerTick = ints.getValue("plans.sendBytesPerTick"),
            serverSectionCache = ints.getValue("plans.serverSectionCache"),
            openPlans = ints.getValue("plans.openFiles"),
            captureSectionsPerTick = ints.getValue("plans.captureSectionsPerTick"),
        ),
        BuildTuning(
            picksPerTick = ints.getValue("build.picksPerTick"),
            maxLinkedSupplies = ints.getValue("build.maxLinkedSupplies"),
            orderBudget = ints.getValue("build.orderBudget"),
        ),
    )
    return ParsedBuildConfig(config, problems)
}

private fun readKey(key: IntKey, text: String?, problems: MutableList<String>): Int {
    val fallback = key.read(BuildConfig.DEFAULT)
    if (text == null) {
        problems += "missing ${key.name}; using $fallback"
        return fallback
    }
    val value = text.toIntOrNull()
    if (value == null) {
        problems += "${key.name} '$text' is not a whole number; using $fallback"
        return fallback
    }
    val clamped = value.coerceIn(key.min, key.max)
    if (clamped != value) problems += "${key.name} $value is outside ${key.min}..${key.max}; using $clamped"
    return clamped
}

/** Pure: the file text, a comment with the range above each key. */
fun BuildConfig.toFileText(): String = buildString {
    append("# Enderkinesis server config. The server reads it at start.\n")
    for (key in KEYS) {
        append('\n')
        append("# ").append(key.comment).append(" Range ").append(key.min).append("..").append(key.max)
        append(", default ").append(key.read(BuildConfig.DEFAULT)).append(".\n")
        append(key.name).append('=').append(key.read(this@toFileText)).append('\n')
    }
}
