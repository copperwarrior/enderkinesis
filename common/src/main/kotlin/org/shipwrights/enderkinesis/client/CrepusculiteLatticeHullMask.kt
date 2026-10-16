package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.multiplayer.ClientLevel
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.shipwrights.enderkinesis.physics.LatticeHullVolume
import org.valkyrienskies.core.api.ships.ClientShip

/** The [LatticeHullVolume] flood result of each ship as an RGBA8 texture: one texel for each 32-bit word. */
object CrepusculiteLatticeHullMask {
    private const val EVICT_FRAMES = 600L

    /** Time, in ticks, for the cutout to go from the old hull shape to the new one. */
    private const val TRANSITION_TICKS = 20.0

    class Mask internal constructor() {
        internal var uploadedVersion = 0
        internal var lastUsedFrame = 0L

        var textureId = -1
            internal set
        var texMinX = 0
            internal set
        var texMinY = 0
            internal set
        var texMinZ = 0
            internal set
        var texW = 0
            internal set
        var texH = 0
            internal set
        var texD = 0
            internal set

        /** The texture before the last upload, or -1. The shader fades from it. */
        var prevTextureId = -1
            internal set
        var prevMinX = 0
            internal set
        var prevMinY = 0
            internal set
        var prevMinZ = 0
            internal set
        var prevW = 0
            internal set
        var prevH = 0
            internal set
        var prevD = 0
            internal set
        internal var transitionStart = Double.NEGATIVE_INFINITY

        /** 0 shows the previous texture, 1 shows the current one. [time] is game time in ticks. */
        fun blendAt(time: Double): Float {
            val t = (time - transitionStart) / TRANSITION_TICKS
            return if (prevTextureId == -1 || t < 0.0 || t >= 1.0) 1f else t.toFloat()
        }
    }

    private val masks = Long2ObjectOpenHashMap<Mask>()

    /** Returns the mask of [ship] when its texture is ready. [time] is game time in ticks. */
    fun maskFor(level: ClientLevel, ship: ClientShip, frame: Long, time: Double): Mask? {
        RenderSystem.assertOnRenderThread()
        val volume = LatticeHullVolume.volumeFor(level, ship) ?: return null
        val rows = volume.wetD.toLong() * ((volume.wetH + LatticeHullVolume.LEVELS_PER_WORD - 1) / LatticeHullVolume.LEVELS_PER_WORD)
        val maxSide = RenderSystem.maxSupportedTextureSize()
        if (volume.wetW > maxSide || rows > maxSide) return null

        val mask = masks.get(ship.id) ?: Mask().also { masks.put(ship.id, it) }
        mask.lastUsedFrame = frame
        if (mask.uploadedVersion != volume.wetVersion) upload(mask, volume, rows.toInt(), time)
        return mask
    }

    /** Releases the textures that no ocean used for [EVICT_FRAMES] frames. */
    fun evict(frame: Long) {
        RenderSystem.assertOnRenderThread()
        val it = masks.values.iterator()
        while (it.hasNext()) {
            val mask = it.next()
            if (frame - mask.lastUsedFrame <= EVICT_FRAMES) continue
            if (mask.textureId != -1) TextureUtil.releaseTextureId(mask.textureId)
            if (mask.prevTextureId != -1) TextureUtil.releaseTextureId(mask.prevTextureId)
            it.remove()
        }
    }

    private fun upload(mask: Mask, volume: LatticeHullVolume.Volume, rows: Int, time: Double) {
        // The current texture becomes the previous one. The new data goes into the old previous texture.
        val hadTexture = mask.textureId != -1
        val reuse = mask.prevTextureId
        if (hadTexture) {
            mask.prevTextureId = mask.textureId
            mask.prevMinX = mask.texMinX; mask.prevMinY = mask.texMinY; mask.prevMinZ = mask.texMinZ
            mask.prevW = mask.texW; mask.prevH = mask.texH; mask.prevD = mask.texD
            mask.transitionStart = time
        }
        mask.textureId = reuse
        if (mask.textureId == -1) {
            mask.textureId = GlStateManager._genTexture()
            GlStateManager._bindTexture(mask.textureId)
            // The default mipmapped min filter leaves a texture with no mips incomplete; texelFetch then reads 0.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        } else {
            GlStateManager._bindTexture(mask.textureId)
        }
        // NativeImage uploads leave unpack state set; a stale row length would shear this upload.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        // 8_8_8_8_REV puts bits 0-7 of each word in R on all platforms; the shader reads that order.
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, volume.wetW, rows, 0,
            GL11.GL_RGBA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, volume.wet,
        )
        mask.texMinX = volume.wetMinX; mask.texMinY = volume.wetMinY; mask.texMinZ = volume.wetMinZ
        mask.texW = volume.wetW; mask.texH = volume.wetH; mask.texD = volume.wetD
        mask.uploadedVersion = volume.wetVersion
    }
}
