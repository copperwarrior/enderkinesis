package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.platform.GlStateManager
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.nio.ByteBuffer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.SectionPos
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.chunk.DataLayer
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil

/**
 * R8 block light, level × 17, in a toroidal window of sections around the camera; a camera move
 * rebuilds only the sections that come into the window. Texel axes are (x, z, y), the [DataLayer]
 * order. Sections with no light data read as 0. Render thread only.
 */
class SectionLightVolume(private val windowChunks: Int, private val windowSections: Int) {

    init {
        // The shader wraps volume coords with repeat; that is only exact when the window divides the block grid.
        require(windowChunks > 0 && windowChunks and (windowChunks - 1) == 0) {
            "windowChunks must be a power of two, was $windowChunks"
        }
        require(windowSections > 0 && windowSections and (windowSections - 1) == 0) {
            "windowSections must be a power of two, was $windowSections"
        }
    }

    val sizeXZ = windowChunks * 16
    val sizeY = windowSections * 16
    private val radiusXZ = windowChunks / 2 - 1
    private val radiusY = windowSections / 2 - 1

    /** Every section in the window lies at least this far from the camera on each axis. */
    val maxMarch = minOf(radiusXZ, radiusY) * 16f

    var textureId = -1
        private set

    private val slotKeys = LongArray(windowChunks * windowChunks * windowSections) { UNSET }
    private val dirtySections = LongOpenHashSet()
    private val scratch: ByteBuffer = BufferUtils.createByteBuffer(SECTION_TEXELS)
    private var boundLevel: ClientLevel? = null
    private var windowX = Int.MIN_VALUE
    private var windowY = Int.MIN_VALUE
    private var windowZ = Int.MIN_VALUE
    private var uploadBound = false

    /** Block coordinate in volume texels. Absolute coordinates lose block precision in a float past 2^24. */
    fun wrapXZ(block: Int): Int = block and (sizeXZ - 1)

    fun wrapY(block: Int): Int = block and (sizeY - 1)

    fun markDirty(sectionX: Int, sectionY: Int, sectionZ: Int) {
        // A section outside the window gets a full rebuild when the window reaches it.
        if (boundLevel != null && inWindow(sectionX, sectionY, sectionZ)) {
            dirtySections.add(SectionPos.asLong(sectionX, sectionY, sectionZ))
        }
    }

    fun refresh(level: ClientLevel, camSectionX: Int, camSectionY: Int, camSectionZ: Int) {
        if (textureId == -1) {
            textureId = GlStateManager._genTexture()
            VolumeTextures.allocateR8x3D(textureId, sizeXZ, sizeXZ, sizeY, GL11.GL_LINEAR)
        }
        if (level !== boundLevel) bind(level)
        uploadBound = false
        if (camSectionX != windowX || camSectionY != windowY || camSectionZ != windowZ) {
            windowX = camSectionX
            windowY = camSectionY
            windowZ = camSectionZ
            for (sy in camSectionY - radiusY until camSectionY - radiusY + windowSections) {
                for (sz in camSectionZ - radiusXZ until camSectionZ - radiusXZ + windowChunks) {
                    for (sx in camSectionX - radiusXZ until camSectionX - radiusXZ + windowChunks) {
                        if (slotKeys[slotOf(sx, sy, sz)] != SectionPos.asLong(sx, sy, sz)) rebuildSlot(level, sx, sy, sz)
                    }
                }
            }
        }
        val keys = dirtySections.iterator()
        while (keys.hasNext()) {
            val key = keys.nextLong()
            val sx = SectionPos.x(key)
            val sy = SectionPos.y(key)
            val sz = SectionPos.z(key)
            if (inWindow(sx, sy, sz)) rebuildSlot(level, sx, sy, sz)
        }
        dirtySections.clear()
        if (uploadBound) VolumeTextures.endUpload3D()
    }

    private fun inWindow(sx: Int, sy: Int, sz: Int): Boolean =
        sx - windowX + radiusXZ in 0 until windowChunks &&
            sz - windowZ + radiusXZ in 0 until windowChunks &&
            sy - windowY + radiusY in 0 until windowSections

    fun unbind() {
        if (boundLevel == null) return
        boundLevel = null
        dirtySections.clear()
    }

    private fun bind(level: ClientLevel) {
        boundLevel = level
        slotKeys.fill(UNSET)
        windowX = Int.MIN_VALUE
        windowY = Int.MIN_VALUE
        windowZ = Int.MIN_VALUE
        dirtySections.clear()
    }

    private fun slotOf(sx: Int, sy: Int, sz: Int): Int =
        (sx and (windowChunks - 1)) + ((sz and (windowChunks - 1)) + (sy and (windowSections - 1)) * windowChunks) * windowChunks

    private fun rebuildSlot(level: ClientLevel, sx: Int, sy: Int, sz: Int) {
        slotKeys[slotOf(sx, sy, sz)] = SectionPos.asLong(sx, sy, sz)
        val layer = if (sy in level.minSection until level.maxSection) {
            level.lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(sx, sy, sz))
        } else {
            null
        }
        fillScratch(layer)
        if (!uploadBound) {
            VolumeTextures.beginUpload3D(textureId)
            uploadBound = true
        }
        VolumeTextures.uploadR8x3D(
            (sx and (windowChunks - 1)) * 16, (sz and (windowChunks - 1)) * 16, (sy and (windowSections - 1)) * 16,
            16, 16, 16, scratch,
        )
    }

    private fun fillScratch(layer: DataLayer?) {
        // getData() allocates a backing array on a homogeneous layer, so read those with get().
        if (layer == null || layer.isDefinitelyHomogenous) {
            val value = ((layer?.get(0, 0, 0) ?: 0) * LEVEL_SCALE).toByte()
            MemoryUtil.memSet(MemoryUtil.memAddress0(scratch), value.toInt(), SECTION_TEXELS.toLong())
            return
        }
        val nibbles = layer.data
        for (i in nibbles.indices) {
            val packed = nibbles[i].toInt()
            scratch.put(2 * i, ((packed and 0xF) * LEVEL_SCALE).toByte())
            scratch.put(2 * i + 1, ((packed ushr 4 and 0xF) * LEVEL_SCALE).toByte())
        }
    }

    private companion object {
        // Maps level 15 to 255, so the shader reads level / 15.
        const val LEVEL_SCALE = 17
        const val SECTION_TEXELS = 16 * 16 * 16
        const val UNSET = Long.MIN_VALUE
    }
}
