package org.shipwrights.enderkinesis.client.volume

import com.mojang.blaze3d.platform.GlStateManager
import java.nio.ByteBuffer
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30

object VolumeTextures {
    /** (Re)allocates [tex] with no mips. */
    @Suppress("LongParameterList") // Mirrors glTexImage2D.
    fun allocate(
        tex: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int,
        filter: Int, clampToEdge: Boolean = false,
    ) {
        GlStateManager._bindTexture(tex)
        // The default mipmapped min filter leaves a texture with no mips incomplete; texelFetch then reads 0.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter)
        if (clampToEdge) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        }
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, null as ByteBuffer?)
    }

    /**
     * Uploads the [width] x [height] rectangle at ([x], [y]) of [data], a full image [rowLength] texels wide,
     * to the same place in [tex]. Leaves the unpack state at the GL defaults.
     */
    @Suppress("LongParameterList") // Mirrors glTexSubImage2D.
    fun uploadR8Region(tex: Int, x: Int, y: Int, width: Int, height: Int, rowLength: Int, data: ByteBuffer) {
        GlStateManager._bindTexture(tex)
        resetUnpack()
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, rowLength)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, x)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, y)
        data.rewind()
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data)
        restoreUnpackDefaults()
    }

    /** Allocates an R8 3D texture with repeat wrap and no mips. [bind3D] it by hand: EffectInstance binds samplers as 2D. */
    fun allocateR8x3D(tex: Int, width: Int, height: Int, depth: Int, filter: Int) =
        allocate3D(tex, width, height, depth, filter, GL30.GL_R8, GL11.GL_RED)

    /** Like [allocateR8x3D], with four 8-bit channels. */
    fun allocateRgba8x3D(tex: Int, width: Int, height: Int, depth: Int, filter: Int) =
        allocate3D(tex, width, height, depth, filter, GL11.GL_RGBA8, GL11.GL_RGBA)

    private fun allocate3D(tex: Int, width: Int, height: Int, depth: Int, filter: Int, internalFormat: Int, format: Int) {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, filter)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, filter)
        GL12.glTexImage3D(
            GL12.GL_TEXTURE_3D, 0, internalFormat, width, height, depth, 0, format, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?,
        )
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
    }

    /** Binds [tex] for a run of [uploadR8x3D] calls. Call [endUpload3D] after the run. */
    fun beginUpload3D(tex: Int) {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex)
        resetUnpack()
    }

    @Suppress("LongParameterList") // Mirrors glTexSubImage3D.
    fun uploadR8x3D(x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, data: ByteBuffer) {
        data.rewind()
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, width, height, depth, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data)
    }

    /** Like [uploadR8x3D], with four bytes for each texel. */
    fun uploadRgba8x3D(x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, data: ByteBuffer) {
        data.rewind()
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, width, height, depth, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data)
    }

    /** Unbinds the 3D texture and leaves the unpack state at the GL defaults. */
    fun endUpload3D() {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        restoreUnpackDefaults()
    }

    /** Allocates a 2D unsigned-integer texture, read by texelFetch only (integer textures cannot filter). */
    fun allocateUint2D(tex: Int, width: Int, height: Int, internalFormat: Int, format: Int) {
        GlStateManager._bindTexture(tex)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, GL11.GL_UNSIGNED_INT, null as java.nio.IntBuffer?)
    }

    /** Uploads the first [rows] rows of [data] (tightly packed unsigned ints, [channels] to a texel) to a texture of [width]. */
    @Suppress("LongParameterList") // Mirrors glTexSubImage2D.
    fun uploadUint2D(tex: Int, width: Int, rows: Int, format: Int, channels: Int, data: java.nio.IntBuffer) {
        GlStateManager._bindTexture(tex)
        resetUnpack()
        data.position(0).limit(width * rows * channels)
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, rows, format, GL11.GL_UNSIGNED_INT, data)
        data.limit(data.capacity())
        restoreUnpackDefaults()
    }

    /** Allocates a 3D unsigned-integer texture; [type] is the component type to upload with. */
    @Suppress("LongParameterList") // Mirrors glTexImage3D.
    fun allocateUint3D(tex: Int, width: Int, height: Int, depth: Int, internalFormat: Int, format: Int, type: Int) {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE)
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, internalFormat, width, height, depth, 0, format, type, null as java.nio.ByteBuffer?)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
    }

    /** Uploads a whole block of 32-bit unsigned ints at ([x], [y], [z]) of a 3D texture. */
    @Suppress("LongParameterList") // Mirrors glTexSubImage3D.
    fun uploadUint3D(tex: Int, x: Int, y: Int, z: Int, width: Int, height: Int, depth: Int, format: Int, data: java.nio.IntBuffer) {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex)
        resetUnpack()
        data.position(0).limit(width * height * depth)
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, width, height, depth, format, GL11.GL_UNSIGNED_INT, data)
        data.limit(data.capacity())
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        restoreUnpackDefaults()
    }

    /** Like [uploadUint3D], for 16-bit unsigned ints. */
    @Suppress("LongParameterList") // Mirrors glTexSubImage3D.
    fun uploadUint3D16(tex: Int, width: Int, height: Int, depth: Int, format: Int, data: java.nio.ShortBuffer) {
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex)
        resetUnpack()
        data.position(0).limit(width * height * depth)
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, width, height, depth, format, GL11.GL_UNSIGNED_SHORT, data)
        data.limit(data.capacity())
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0)
        restoreUnpackDefaults()
    }

    /** Binds a 2D texture to [unit] for a shader that reads it by an int uniform. Pass 0 to unbind. */
    fun bind2D(unit: Int, tex: Int) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        GlStateManager._activeTexture(GL13.GL_TEXTURE0)
    }

    /** Pass 0 for [tex] to unbind. GlStateManager tracks only 2D bindings, so this leaves its cache valid. */
    fun bind3D(unit: Int, tex: Int) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, tex)
        GlStateManager._activeTexture(GL13.GL_TEXTURE0)
    }

    private const val DEFAULT_UNPACK_ALIGNMENT = 4

    // NativeImage uploads leave unpack state set; a stale row length would shear an upload.
    private fun resetUnpack() {
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GlStateManager._pixelStore(GL12.GL_UNPACK_IMAGE_HEIGHT, 0)
        GlStateManager._pixelStore(GL12.GL_UNPACK_SKIP_IMAGES, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
    }

    // Sodium uploads its own textures and assumes the GL defaults.
    private fun restoreUnpackDefaults() {
        resetUnpack()
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, DEFAULT_UNPACK_ALIGNMENT)
    }
}
