package org.shipwrights.enderkinesis.compat.distanthorizons

// Render thread only: written in FogRenderer.setupFog, read in DH's fog event in the same frame.
object TerrainFogSnapshot {

    var start = 0f
        private set
    var end = Float.MAX_VALUE
        private set

    @JvmStatic
    fun record(start: Float, end: Float) {
        this.start = start
        this.end = end
    }
}
