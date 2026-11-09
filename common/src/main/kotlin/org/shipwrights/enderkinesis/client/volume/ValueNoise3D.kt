package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.platform.GlStateManager
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil

/**
 * Tiling 3D value-noise lattice for the volume marches: one texel per lattice point, [PERIOD] a side, repeat wrap.
 * The shader does the smoothstep blend by remapping the texel fraction before one linear fetch, so the curve is exact,
 * not piecewise linear. Baked one time, on the render thread.
 */
object ValueNoise3D {
    const val PERIOD = 32
    private const val UNORM16 = 65535f

    private var tex = -1

    fun textureId(): Int {
        if (tex == -1) tex = bake()
        return tex
    }

    private fun bake(): Int {
        val out = MemoryUtil.memAllocShort(PERIOD * PERIOD * PERIOD)
        try {
            for (z in 0 until PERIOD) for (y in 0 until PERIOD) for (x in 0 until PERIOD) {
                out.put(x + PERIOD * (y + PERIOD * z), (hash(x, y, z) * UNORM16).toInt().toShort())
            }
            val id = GlStateManager._genTexture()
            VolumeTextures.beginUpload3D(id)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT)
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT)
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R16, PERIOD, PERIOD, PERIOD, 0, GL11.GL_RED, GL11.GL_UNSIGNED_SHORT, out)
            VolumeTextures.endUpload3D()
            return id
        } finally {
            MemoryUtil.memFree(out)
        }
    }

    /** Lattice value in [0, 1). */
    private fun hash(x: Int, y: Int, z: Int): Float {
        var h = x * HX + y * HY + z * HZ
        h = (h xor (h ushr 13)) * HZ
        h = h xor (h ushr 16)
        return (h ushr 8) / TWO_POW_24
    }

    private const val HX = 374761393
    private const val HY = 668265263
    private const val HZ = 1274126177
    private const val TWO_POW_24 = 16777216f
}
