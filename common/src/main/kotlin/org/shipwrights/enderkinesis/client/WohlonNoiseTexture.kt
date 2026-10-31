package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import net.minecraft.util.Mth
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.shipwrights.enderkinesis.client.volume.VolumeTextures

/**
 * Tiling 3D value-noise fBm for the Wohlon fog volumes, so their shaders need no hash noise for each step.
 * [prepare] computes the texels on a worker thread; the GL upload stays on the render thread.
 */
object WohlonNoiseTexture {
    private const val SIZE_3D = 64
    private const val LATTICE_3D = 8
    private const val OCTAVES_3D = 2

    // Render thread only.
    private var texture3D = -1
    @Volatile private var pending: java.util.concurrent.CompletableFuture<java.nio.ByteBuffer>? = null

    /** Starts the texel build off the render thread; on the render thread it cost a frame of about 30 ms. */
    fun prepare() {
        if (pending == null) pending = java.util.concurrent.CompletableFuture.supplyAsync(::texels, net.minecraft.Util.backgroundExecutor())
    }

    /** A 64³ R8 3D texture with repeat wrap. Bind it with [VolumeTextures.bind3D]. Render thread only. */
    fun id3D(): Int {
        if (texture3D == -1) {
            val data = pending?.join() ?: texels()
            pending = null
            texture3D = upload(data)
        }
        return texture3D
    }

    private fun texels(): java.nio.ByteBuffer {
        val rng = java.util.Random(0x3D_F06L)
        val grids = Array(OCTAVES_3D) { o ->
            val n = LATTICE_3D shl o
            FloatArray(n * n * n) { rng.nextFloat() }
        }
        val data = BufferUtils.createByteBuffer(SIZE_3D * SIZE_3D * SIZE_3D)
        for (pz in 0 until SIZE_3D) {
            for (py in 0 until SIZE_3D) {
                for (px in 0 until SIZE_3D) {
                    var sum = 0f
                    var amp = 0.5f
                    var norm = 0f
                    for (o in 0 until OCTAVES_3D) {
                        val n = LATTICE_3D shl o
                        val k = n.toFloat() / SIZE_3D
                        sum += amp * latticeNoise3D(grids[o], n, px * k, py * k, pz * k)
                        norm += amp
                        amp *= 0.5f
                    }
                    data.put((pz * SIZE_3D + py) * SIZE_3D + px, ((sum / norm) * 255f).toInt().coerceIn(0, 255).toByte())
                }
            }
        }
        return data
    }

    private fun upload(data: java.nio.ByteBuffer): Int {
        val tex = GlStateManager._genTexture()
        VolumeTextures.allocateR8x3D(tex, SIZE_3D, SIZE_3D, SIZE_3D, GL11.GL_LINEAR)
        VolumeTextures.beginUpload3D(tex)
        VolumeTextures.uploadR8x3D(0, 0, 0, SIZE_3D, SIZE_3D, SIZE_3D, data)
        VolumeTextures.endUpload3D()
        return tex
    }

    private fun latticeNoise3D(grid: FloatArray, n: Int, fx: Float, fy: Float, fz: Float): Float {
        val x0 = fx.toInt(); val y0 = fy.toInt(); val z0 = fz.toInt()
        // Quintic fade: a smoothstep fade leaves creases along the lattice, which read as cubes.
        val tx = quintic(fx - x0); val ty = quintic(fy - y0); val tz = quintic(fz - z0)
        val x1 = (x0 + 1) % n; val y1 = (y0 + 1) % n; val z1 = (z0 + 1) % n
        fun at(x: Int, y: Int, z: Int) = grid[(z * n + y) * n + x]
        val a = Mth.lerp(ty, Mth.lerp(tx, at(x0, y0, z0), at(x1, y0, z0)), Mth.lerp(tx, at(x0, y1, z0), at(x1, y1, z0)))
        val b = Mth.lerp(ty, Mth.lerp(tx, at(x0, y0, z1), at(x1, y0, z1)), Mth.lerp(tx, at(x0, y1, z1), at(x1, y1, z1)))
        return Mth.lerp(tz, a, b)
    }

    private fun quintic(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)
}
