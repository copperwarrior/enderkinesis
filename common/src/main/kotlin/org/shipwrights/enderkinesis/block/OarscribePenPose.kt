package org.shipwrights.enderkinesis.block

import net.minecraft.network.FriendlyByteBuf

/**
 * The held pen of the Oarscribe, in model pixels and degrees. [rotX], [rotY] and [rotZ] are a Blockbench rotation
 * about [centre], applied X first, then Y, then Z. [spin] turns the pen about its own length after that.
 */
data class OarscribePenPose(
    val centreX: Float, val centreY: Float, val centreZ: Float,
    val rotX: Float, val rotY: Float, val rotZ: Float,
    val spin: Float,
    val length: Float,
) {
    fun write(buf: FriendlyByteBuf) {
        listOf(centreX, centreY, centreZ, rotX, rotY, rotZ, spin, length).forEach(buf::writeFloat)
    }

    fun describe(): String =
        "centre $centreX $centreY $centreZ, rotation $rotX $rotY $rotZ, spin $spin, length $length"

    companion object {
        val DEFAULT = OarscribePenPose(10f, 7.6f, 10.5f, -60f, 15f, 25f, 0f, 16f)

        fun read(buf: FriendlyByteBuf): OarscribePenPose = OarscribePenPose(
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(),
            buf.readFloat(),
        )
    }
}
