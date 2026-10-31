package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.logging.LogUtils
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.physics.LatticeCatchZone
import org.shipwrights.enderkinesis.physics.LatticeShore
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The shore of one lattice ocean as a top-down R8 texture over the zone ellipse bounds: the
 * shore alpha (warped coast and falloff), from the same [LatticeShore] functions
 * that give the wave surface its edge.
 */
object CrepusculiteLatticeShoreMap {
    private val LOG = LogUtils.getLogger()

    private const val BLOCKS_PER_TEXEL = 1.25
    private const val MIN_SIZE = 32
    private const val MAX_SIZE = 256

    /** The coast moves slowly, so the map does not need the frame rate. */
    private const val REBUILD_TICKS = 4.0
    private const val EVICT_FRAMES = 600L
    private const val MAX_ENTRIES = 64

    class ShoreMap internal constructor() {
        internal var size = 0
        internal var buffer: ByteBuffer? = null          // the worker writes it; the render thread uploads it
        internal val pending = AtomicReference<ByteBuffer?>(null)
        @Volatile internal var building = false
        internal var lastBuildTime = Double.NEGATIVE_INFINITY
        internal var lastUsedFrame = 0L
        internal var ready = false
        internal var textureSize = 0

        var textureId = -1
            internal set
    }

    private val maps = Long2ObjectOpenHashMap<ShoreMap>()

    /** Returns the map of the lattice at [key] when its texture is ready. [time] is game time in ticks. */
    fun mapFor(key: Long, zone: LatticeCatchZone, time: Double, frame: Long): ShoreMap? {
        RenderSystem.assertOnRenderThread()
        var map = maps.get(key)
        if (map == null) {
            if (maps.size >= MAX_ENTRIES) return null
            map = ShoreMap()
            maps.put(key, map)
        }
        map.lastUsedFrame = frame
        map.pending.getAndSet(null)?.let { upload(map, it) }

        // A new build starts only after the last one is uploaded, so the worker never writes a buffer in use.
        val due = time - map.lastBuildTime >= REBUILD_TICKS || time < map.lastBuildTime
        if (due && !map.building && map.pending.get() == null) {
            val size = Math.ceil(2.0 * Math.max(zone.ax, zone.az) / BLOCKS_PER_TEXEL).toInt().coerceIn(MIN_SIZE, MAX_SIZE)
            if (size != map.size || map.buffer == null) {
                map.size = size
                map.buffer = BufferUtils.createByteBuffer(size * size)
            }
            val buffer = checkNotNull(map.buffer)
            map.building = true
            map.lastBuildTime = time
            CrepusculiteLatticeMeshRenderer.runOnWorker {
                try {
                    build(zone, size, buffer)
                    map.pending.set(buffer)
                } catch (t: Throwable) {
                    LOG.error("[EK] lattice shore map build failed", t)
                } finally {
                    map.building = false
                }
            }
        }
        return if (map.ready) map else null
    }

    /** Releases the textures that no ocean used for [EVICT_FRAMES] frames. */
    fun evict(frame: Long) {
        RenderSystem.assertOnRenderThread()
        val it = maps.values.iterator()
        while (it.hasNext()) {
            val map = it.next()
            if (frame - map.lastUsedFrame <= EVICT_FRAMES || map.building) continue
            if (map.textureId != -1) TextureUtil.releaseTextureId(map.textureId)
            it.remove()
        }
    }

    // Worker thread. Same shore math as the vertex loop of CrepusculiteLatticeMeshRenderer.
    private fun build(zone: LatticeCatchZone, size: Int, out: ByteBuffer) {
        val interiorSafe = LatticeShore.interiorSafeRadius(zone.ax, zone.az)
        val circumference = Math.PI * (zone.ax + zone.az)
        for (j in 0 until size) {
            val nz = (j + 0.5) / size * 2.0 - 1.0
            for (i in 0 until size) {
                val nx = (i + 0.5) / size * 2.0 - 1.0
                val rRaw = sqrt(nx * nx + nz * nz)
                val edge = when {
                    rRaw >= 1.0 -> 0f
                    rRaw <= interiorSafe -> 1f
                    else -> {
                        val theta = atan2(nz * zone.az, nx * zone.ax)
                        val rDir = LatticeShore.rDirAt(theta, zone.ax, zone.az)
                        val rWarped = rRaw / LatticeShore.rimWarpAt(theta, rDir, zone.waveTime, circumference)
                        LatticeShore.shoreEdgeAt(rWarped, rDir)
                    }
                }
                out.put(j * size + i, (edge.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            }
        }
    }

    private fun upload(map: ShoreMap, data: ByteBuffer) {
        if (map.textureId == -1) {
            map.textureId = GlStateManager._genTexture()
            GlStateManager._bindTexture(map.textureId)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        } else {
            GlStateManager._bindTexture(map.textureId)
        }
        val size = Math.round(sqrt(data.capacity().toDouble())).toInt()
        // NativeImage uploads leave unpack state set; a stale row length would shear this upload.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
        data.rewind()
        if (size == map.textureSize) {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, size, size, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data)
        } else {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, size, size, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data)
            map.textureSize = size
        }
        map.ready = true
    }
}
