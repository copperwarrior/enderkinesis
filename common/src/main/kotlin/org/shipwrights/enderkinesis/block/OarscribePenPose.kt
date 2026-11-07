package org.shipwrights.enderkinesis.block

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
    companion object {
        val DEFAULT = OarscribePenPose(10f, 7.6f, 10.5f, -60f, 15f, 25f, 0f, 16f)
    }
}
