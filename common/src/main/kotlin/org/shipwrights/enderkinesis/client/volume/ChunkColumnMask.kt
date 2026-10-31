package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.platform.GlStateManager
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.nio.ByteBuffer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

/**
 * R8 mask of block columns in a toroidal window of chunks around the camera; a camera move
 * rebuilds only the chunks that come into the window. Texel bits: [FLAG_BIT] from [fill], plus
 * bit `size` (2, 4, 8, 16) when the aligned size×size block has one flag value, so a march can
 * step over the largest such block. Unloaded chunks read as flag 0. Render thread only.
 *
 * With [raw], the texels are the bytes of [fill] as they are, and unloaded chunks read as
 * [unloadedValue].
 */
class ChunkColumnMask(
    private val windowChunks: Int,
    private val fill: ColumnFill,
    private val raw: Boolean = false,
    private val unloadedValue: Int = ALL_UNIFORM,
) {

    /** Writes [FLAG_BIT] or 0 (any byte with [raw]) to `out[z * 16 + x]` for each column of the chunk. */
    fun interface ColumnFill {
        fun fill(chunk: LevelChunk, out: ByteBuffer)
    }

    init {
        // The shader wraps mask coords with `& (size - 1)`.
        require(windowChunks > 0 && windowChunks and (windowChunks - 1) == 0) {
            "windowChunks must be a power of two, was $windowChunks"
        }
    }

    val size = windowChunks * 16
    private val radius = windowChunks / 2 - 1

    /** Every chunk in the window lies at least this far from the camera on X and Z. */
    val maxMarch = radius * 16f

    var textureId = -1
        private set

    private val slotKeys = LongArray(windowChunks * windowChunks) { UNSET }
    private val dirtyChunks = LongOpenHashSet()
    private val scratch: ByteBuffer = BufferUtils.createByteBuffer(16 * 16)
    private var boundLevel: ClientLevel? = null
    private var windowChunkX = Int.MIN_VALUE
    private var windowChunkZ = Int.MIN_VALUE

    /** Block coordinate in mask texels. Absolute coordinates lose block precision in a float past 2^24. */
    fun wrap(block: Int): Int = block and (size - 1)

    fun markDirty(chunkX: Int, chunkZ: Int) {
        if (boundLevel != null) dirtyChunks.add(ChunkPos.asLong(chunkX, chunkZ))
    }

    fun refresh(level: ClientLevel, camChunkX: Int, camChunkZ: Int) {
        if (textureId == -1) {
            textureId = GlStateManager._genTexture()
            VolumeTextures.allocate(textureId, GL30.GL_R8, size, size, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, GL11.GL_NEAREST)
        }
        if (level !== boundLevel) bind(level)
        if (camChunkX != windowChunkX || camChunkZ != windowChunkZ) {
            windowChunkX = camChunkX
            windowChunkZ = camChunkZ
            for (cz in camChunkZ - radius until camChunkZ - radius + windowChunks) {
                for (cx in camChunkX - radius until camChunkX - radius + windowChunks) {
                    if (slotKeys[slotOf(cx, cz)] != ChunkPos.asLong(cx, cz)) rebuildSlot(level, cx, cz)
                }
            }
        }
        if (dirtyChunks.isEmpty()) return
        val keys = dirtyChunks.iterator()
        while (keys.hasNext()) {
            val key = keys.nextLong()
            val cx = ChunkPos.getX(key)
            val cz = ChunkPos.getZ(key)
            if (cx - windowChunkX + radius !in 0 until windowChunks) continue
            if (cz - windowChunkZ + radius !in 0 until windowChunks) continue
            rebuildSlot(level, cx, cz)
        }
        dirtyChunks.clear()
    }

    fun unbind() {
        if (boundLevel == null) return
        boundLevel = null
        dirtyChunks.clear()
    }

    private fun bind(level: ClientLevel) {
        boundLevel = level
        slotKeys.fill(UNSET)
        windowChunkX = Int.MIN_VALUE
        windowChunkZ = Int.MIN_VALUE
        dirtyChunks.clear()
    }

    private fun slotOf(cx: Int, cz: Int): Int =
        (cx and (windowChunks - 1)) + (cz and (windowChunks - 1)) * windowChunks

    private fun rebuildSlot(level: ClientLevel, cx: Int, cz: Int) {
        val slot = slotOf(cx, cz)
        slotKeys[slot] = ChunkPos.asLong(cx, cz)
        val chunk = level.chunkSource.getChunkNow(cx, cz)
        if (chunk != null) {
            fill.fill(chunk, scratch)
            if (!raw) encodeUniformBlocks()
        } else {
            for (i in 0 until 256) scratch.put(i, unloadedValue.toByte())
        }
        VolumeTextures.uploadR8(textureId, (slot % windowChunks) * 16, (slot / windowChunks) * 16, 16, 16, scratch)
    }

    private fun encodeUniformBlocks() {
        var blockSize = 2
        while (blockSize <= 16) {
            for (bz in 0 until 16 step blockSize) {
                for (bx in 0 until 16 step blockSize) {
                    if (isUniform(bx, bz, blockSize)) markBlock(bx, bz, blockSize)
                }
            }
            blockSize *= 2
        }
    }

    private fun isUniform(bx: Int, bz: Int, blockSize: Int): Boolean {
        val state = scratch.get(bz * 16 + bx).toInt() and FLAG_BIT
        for (z in bz until bz + blockSize) {
            for (x in bx until bx + blockSize) {
                if ((scratch.get(z * 16 + x).toInt() and FLAG_BIT) != state) return false
            }
        }
        return true
    }

    private fun markBlock(bx: Int, bz: Int, blockSize: Int) {
        for (z in bz until bz + blockSize) {
            for (x in bx until bx + blockSize) {
                val i = z * 16 + x
                scratch.put(i, (scratch.get(i).toInt() or blockSize).toByte())
            }
        }
    }

    companion object {
        const val FLAG_BIT = 1
        private const val ALL_UNIFORM = 2 or 4 or 8 or 16
        private const val UNSET = Long.MIN_VALUE
    }
}
