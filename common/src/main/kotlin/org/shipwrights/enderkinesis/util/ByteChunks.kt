package org.shipwrights.enderkinesis.util

/** Joins the chunks in index order, or null while a chunk is missing. */
fun Array<ByteArray?>.joinChunksOrNull(): ByteArray? {
    var size = 0
    for (part in this) size += part?.size ?: return null
    val out = ByteArray(size)
    var at = 0
    for (part in this) {
        requireNotNull(part).copyInto(out, at)
        at += part.size
    }
    return out
}
