package org.shipwrights.enderkinesis.construction

/** Plan counts, sizes and rates; the server reads them from [ServerBuildConfig]. Shaped after Create's schematic upload keys. */
data class PlanLimits(
    val maxFileMegabytes: Int = 500,
    val uploadPacketBytes: Int = PlanUploadStream.CHUNK,
    val uploadWindow: Int = 8,
    val uploadBytesPerTick: Int = 64 * 1024,
    val sendBytesPerTick: Int = 64 * 1024,
    val serverSectionCache: Int = 2048,
    val openPlans: Int = 16,
    val captureSectionsPerTick: Int = 8,
) {
    init {
        require(captureSectionsPerTick > 0) { "the capture rate must be positive" }
        require(openPlans > 0 && serverSectionCache > 0 && maxFileMegabytes > 0) { "plan size and caches must be positive" }
        require(uploadPacketBytes in MIN_PACKET..PlanUploadStream.CHUNK) { "bad upload packet size $uploadPacketBytes" }
        require(uploadWindow > 0) { "the upload window must be positive" }
        require(uploadBytesPerTick >= uploadPacketBytes && sendBytesPerTick > 0) { "plan rates must allow one packet a tick" }
    }

    val maxFileBytes: Long get() = maxFileMegabytes * BYTES_PER_MEGABYTE

    /** The refusal key of an upload of [size] bytes, or null when the size is allowed. Singleplayer passes [limitSize] false. */
    fun uploadRefusal(size: Long, limitSize: Boolean = true): String? = when {
        size < 1 || PlanUploadStream.chunkCount(size, uploadPacketBytes) > Int.MAX_VALUE -> "bad_file"
        limitSize && size > maxFileBytes -> "too_large"
        else -> null
    }

    companion object {
        /** Plans one player can store; not configurable. */
        const val QUOTA = 16

        /** Create's smallest packet size. */
        const val MIN_PACKET = 256
        const val BYTES_PER_MEGABYTE = 1_000_000L

        /** Rounded up, so a file just over the limit does not read as equal to it. */
        fun megabytes(size: Long): Long = (size + BYTES_PER_MEGABYTE - 1) / BYTES_PER_MEGABYTE
    }
}
