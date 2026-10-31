package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import kotlin.math.cos
import kotlin.math.sin

/** A world-aligned `POSITION` sphere around the camera, for sky shaders that colour by view direction. */
object SkySphereMesh {
    const val RADIUS = 100f
    private const val LATITUDE_RINGS = 32
    private const val LONGITUDE_SECTORS = 64

    // Built one time on the render thread, and kept for the life of the client.
    private var mesh: VertexBuffer? = null

    fun get(): VertexBuffer = mesh ?: build().also { mesh = it }

    private fun build(): VertexBuffer {
        val sinPhi = FloatArray(LATITUDE_RINGS + 1)
        val cosPhi = FloatArray(LATITUDE_RINGS + 1)
        for (r in 0..LATITUDE_RINGS) {
            val phi = (Math.PI / 2.0) - (r.toDouble() / LATITUDE_RINGS) * Math.PI
            sinPhi[r] = sin(phi).toFloat()
            cosPhi[r] = cos(phi).toFloat()
        }
        val sinTh = FloatArray(LONGITUDE_SECTORS + 1)
        val cosTh = FloatArray(LONGITUDE_SECTORS + 1)
        for (s in 0..LONGITUDE_SECTORS) {
            val th = (s.toDouble() / LONGITUDE_SECTORS) * Math.PI * 2.0
            sinTh[s] = sin(th).toFloat()
            cosTh[s] = cos(th).toFloat()
        }

        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION)
        for (r in 0 until LATITUDE_RINGS) {
            val yT = (sinPhi[r] * RADIUS).toDouble()
            val yB = (sinPhi[r + 1] * RADIUS).toDouble()
            val cosT = cosPhi[r] * RADIUS
            val cosB = cosPhi[r + 1] * RADIUS
            for (s in 0 until LONGITUDE_SECTORS) {
                val xT0 = (cosT * cosTh[s]).toDouble(); val zT0 = (cosT * sinTh[s]).toDouble()
                val xT1 = (cosT * cosTh[s + 1]).toDouble(); val zT1 = (cosT * sinTh[s + 1]).toDouble()
                val xB0 = (cosB * cosTh[s]).toDouble(); val zB0 = (cosB * sinTh[s]).toDouble()
                val xB1 = (cosB * cosTh[s + 1]).toDouble(); val zB1 = (cosB * sinTh[s + 1]).toDouble()

                builder.vertex(xT0, yT, zT0).endVertex()
                builder.vertex(xB0, yB, zB0).endVertex()
                builder.vertex(xB1, yB, zB1).endVertex()

                builder.vertex(xT0, yT, zT0).endVertex()
                builder.vertex(xB1, yB, zB1).endVertex()
                builder.vertex(xT1, yT, zT1).endVertex()
            }
        }
        return VertexBuffer(VertexBuffer.Usage.STATIC).also {
            it.bind()
            it.upload(builder.end())
            VertexBuffer.unbind()
        }
    }
}
