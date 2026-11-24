package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import org.shipwrights.enderkinesis.client.volume.DepthTextureCopy
import org.shipwrights.enderkinesis.client.volume.ScreenBounds
import org.shipwrights.enderkinesis.client.volume.VolumeTextures
import org.shipwrights.enderkinesis.client.volume.boundDrawFramebuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * The plan ghost as a Silverpoint dye-wash sketch. Ghost blocks draw into screen-size colour and normals targets that
 * test against a copy of main depth; then the real strike_sketch shader runs once over the screen box of the ghost.
 */
object PlanGhostSketch {

    @Volatile
    @JvmField
    var sourceShader: ShaderInstance? = null

    // Render thread only.
    private val depth = DepthTextureCopy()
    private val bounds = ScreenBounds()
    private val tone = GhostToneMeter()
    private val identity: Matrix4f = Matrix4f()
    private val clearValue = FloatArray(CHANNELS)
    private val quad by lazy(LazyThreadSafetyMode.NONE) { screenQuad() }
    private var colourFbo = -1
    private var normalsFbo = -1
    private var colourTex = -1
    private var normalsTex = -1
    private var positionFbo = -1
    private var positionTex = -1
    private var width = 0
    private var height = 0
    private var attachment = 0
    private var prevFbo = 0
    private var begun = false
    private var warbleTime = 0f

    /** The default opaque mode, to hand the shared BlendMode cache back in a known state. */
    internal val opaqueBlend = BlendMode()

    fun beginFrame() {
        begun = false
        bounds.reset()
        warbleTime = (Util.getMillis().toDouble() * WARBLE_CELLS_PER_SECOND / MILLIS_PER_SECOND % WARBLE_CYCLE).toFloat()
    }

    /** A camera-relative box of drawn ghost blocks, through the ghost's own [viewProj]. */
    @Suppress("LongParameterList") // A matrix and a box.
    fun addBounds(viewProj: Matrix4fc, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) =
        bounds.addProjectedBox(viewProj, x0, y0, z0, x1, y1, z1)

    /** The source shader; the first call of a frame copies main depth and clears the targets. Null until both shaders load. */
    fun beginSource(): ShaderInstance? {
        val shader = sourceShader ?: return null
        if (StrikeSketch.shader == null) return null
        if (!begun) {
            val main = Minecraft.getInstance().mainRenderTarget
            prevFbo = boundDrawFramebuffer(main)
            depth.copyFrom(main, prevFbo)
            ensure(main)
            RenderSystem.colorMask(true, true, true, true)
            clearColour(colourFbo)
            clearColour(normalsFbo)
            clearColour(positionFbo)
            begun = true
        }
        return shader
    }

    /** Binds the target of [pass] (colour, normals or paper position). The passes share one depth; only colour writes it. */
    fun bindPass(shader: ShaderInstance, pass: Int) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, when (pass) {
            PASS_NORMALS -> normalsFbo
            PASS_POSITION -> positionFbo
            else -> colourFbo
        })
        GlStateManager._viewport(0, 0, width, height)
        opaqueBlend.apply()
        RenderSystem.disableBlend()
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL11.GL_LEQUAL)
        RenderSystem.depthMask(pass == PASS_COLOUR)
        RenderSystem.enableCull()
        shader.safeGetUniform("Mode").set(pass.toFloat())
        shader.safeGetUniform("WarbleTime").set(warbleTime)
    }

    /** The world (or ship) block of the section's minimum corner, which the warble hashes. */
    fun warbleOrigin(shader: ShaderInstance, x: Int, y: Int, z: Int) {
        shader.safeGetUniform("WarbleOrigin").set(
            Math.floorMod(x, WARBLE_WRAP).toFloat(), Math.floorMod(y, WARBLE_WRAP).toFloat(), Math.floorMod(z, WARBLE_WRAP).toFloat(),
        )
    }

    /** The section's box minimum relative to the anchor, which the paper position of its vertices adds to their own. */
    fun frameOrigin(shader: ShaderInstance, x: Int, y: Int, z: Int) =
        shader.safeGetUniform("FrameOrigin").set(x.toFloat(), y.toFloat(), z.toFloat())

    fun endSource() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo)
        RenderSystem.depthMask(true)
    }

    /** The sketch over the frame, when a ghost drew this frame. */
    fun composite() {
        if (!begun || bounds.isEmpty) return
        val shader = StrikeSketch.shader ?: return
        tone.measure(colourFbo, width, height)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo)
        GlStateManager._viewport(0, 0, width, height)
        val scale = StrikeSketch.guiPixelScale()
        shader.safeGetUniform("SketchSize").set(width.toFloat(), height.toFloat())
        shader.safeGetUniform("PixelScale").set(scale.toFloat())
        shader.safeGetUniform("ToneRange").set(tone.low, tone.high)
        shader.safeGetUniform("Base").set(OFF_SCREEN, OFF_SCREEN, BASE_HALF_WIDTH, 0f)
        shader.safeGetUniform("NoteCount").set(0f)
        shader.safeGetUniform("Frame").set(1f, 0f, 0f, 0f)
        // Rebuild mode with no ground flags: lines follow each section's distance fade, and no ground marks show.
        shader.safeGetUniform("OutlineGround").set(1f)
        shader.safeGetUniform("SideFade").set(0f)
        shader.safeGetUniform("Colour").set(1f)
        RenderSystem.setShaderTexture(0, colourTex)
        RenderSystem.setShaderTexture(1, normalsTex)
        RenderSystem.setShaderTexture(PAPER_UNIT, positionTex)
        shader.safeGetUniform("WorldNoise").set(1f)
        // The sketch shader's own mode is opaque: with the cache already opaque, its apply leaves this blend on.
        opaqueBlend.apply()
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        bounds.applyScissor(width, height, ceil(WOBBLE_REACH * maxOf(width, height)).toInt() + LINE_REACH * scale)
        quad.bind()
        quad.drawWithShader(identity, identity, shader)
        VertexBuffer.unbind()
        // The item sketches share this shader and never set the uniform.
        shader.safeGetUniform("WorldNoise").set(0f)
        RenderSystem.disableScissor()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
    }

    private fun clearColour(fbo: Int) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, clearValue)
    }

    private fun ensure(main: RenderTarget) {
        val resized = main.width != width || main.height != height
        if (colourFbo != -1 && !resized && depth.attachment == attachment) return
        if (colourFbo == -1) {
            colourTex = GlStateManager._genTexture()
            normalsTex = GlStateManager._genTexture()
            colourFbo = GlStateManager.glGenFramebuffers()
            normalsFbo = GlStateManager.glGenFramebuffers()
            positionTex = GlStateManager._genTexture()
            positionFbo = GlStateManager.glGenFramebuffers()
        }
        if (resized) {
            allocate(colourTex, main)
            allocate(normalsTex, main)
            // 16-bit unorm: the paper position, in 1/512 blocks over a 512-block span around the anchor.
            VolumeTextures.allocate(
                positionTex, GL11.GL_RGBA16, main.width, main.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, GL11.GL_NEAREST, true,
            )
        }
        attach(colourFbo, colourTex)
        attach(normalsFbo, normalsTex)
        attach(positionFbo, positionTex)
        width = main.width
        height = main.height
        attachment = depth.attachment
    }

    private fun allocate(tex: Int, main: RenderTarget) =
        VolumeTextures.allocate(tex, GL11.GL_RGBA8, main.width, main.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, GL11.GL_NEAREST, true)

    private fun attach(fbo: Int, tex: Int) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0)
        // A switch between depth and depth-stencil would leave the old point attached.
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, depth.attachment, GL11.GL_TEXTURE_2D, depth.textureId, 0)
    }

    private fun screenQuad(): VertexBuffer {
        val builder = BufferBuilder(QUAD_BYTES)
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
        builder.vertex(-1.0, -1.0, 0.0).uv(0f, 0f).endVertex()
        builder.vertex(1.0, -1.0, 0.0).uv(1f, 0f).endVertex()
        builder.vertex(1.0, 1.0, 0.0).uv(1f, 1f).endVertex()
        builder.vertex(-1.0, 1.0, 0.0).uv(0f, 1f).endVertex()
        val buffer = VertexBuffer(VertexBuffer.Usage.STATIC)
        buffer.bind()
        buffer.upload(builder.end())
        VertexBuffer.unbind()
        return buffer
    }

    const val PASS_COLOUR = 0
    const val PASS_NORMALS = 1
    const val PASS_POSITION = 2
    private const val PAPER_UNIT = 2
    private const val CHANNELS = 4
    private const val QUAD_BYTES = 256

    // About 0.03 blocks in plan_ghost_source.vsh; smooth noise, this many noise cells a second. The cycle matches the shader's wrap.
    private const val WARBLE_CELLS_PER_SECOND = 1.2
    private const val MILLIS_PER_SECOND = 1000.0
    private const val WARBLE_CYCLE = 1024.0
    private const val WARBLE_WRAP = 1024

    // Base far off the render keeps the sketch's overshoot ticks off screen.
    private const val OFF_SCREEN = -4f
    private const val BASE_HALF_WIDTH = 0.01f

    // The sketch reads up to 0.016 render UV of wobble and about 6 GUI px of line and wash past a form.
    private const val WOBBLE_REACH = 0.02f
    private const val LINE_REACH = 8
}

/**
 * The sketches' tone rule (StrikeSketchMeasure percentiles) on a small nearest-sampled copy of the colour target. The
 * read goes through a pixel buffer and a fence, so it never stalls a frame; the range lags a few frames.
 */
private class GhostToneMeter {
    var low = 0f
        private set
    var high = 1f
        private set
    private var measured = false
    private var fbo = -1
    private var tex = -1
    private var pbo = -1
    private var fence = 0L
    private var wait = 0
    private val counts = IntArray(StrikeSketchMeasure.LUMINANCE_LEVELS)
    private val pixels: ByteBuffer by lazy(LazyThreadSafetyMode.NONE) {
        ByteBuffer.allocateDirect(SAMPLE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    }

    /** Reads a finished sample, or starts a new one every [MEASURE_FRAMES] frames. Leaves an unknown framebuffer bound. */
    fun measure(sourceFbo: Int, width: Int, height: Int) {
        if (fence != 0L) {
            collect()
            return
        }
        if (--wait > 0) return
        wait = MEASURE_FRAMES
        ensure()
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFbo)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo)
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, SAMPLE_W, SAMPLE_H, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST)
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo)
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, PACK_ALIGNMENT)
        GL11.glReadPixels(0, 0, SAMPLE_W, SAMPLE_H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
    }

    private fun collect() {
        val status = GL32.glClientWaitSync(fence, 0, 0L)
        if (status == GL32.GL_TIMEOUT_EXPIRED) return
        GL32.glDeleteSync(fence)
        fence = 0L
        if (status == GL32.GL_WAIT_FAILED) return
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo)
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, pixels)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
        counts.fill(0)
        var covered = 0
        // RGBA bytes read little-endian are NativeImage's ABGR ints.
        for (i in 0 until SAMPLE_W * SAMPLE_H) if (StrikeSketchMeasure.countTone(counts, pixels.getInt(i * BYTES_PER_PIXEL))) covered++
        if (covered == 0) return
        val newLow = StrikeSketchMeasure.toneLow(counts, covered)
        val newHigh = StrikeSketchMeasure.toneHigh(counts, covered)
        // Eased after the first read, so sections that stream in do not pop the whole ghost's tone.
        val ease = if (measured) EASE else 1f
        low += (newLow - low) * ease
        high += (newHigh - high) * ease
        measured = true
    }

    private fun ensure() {
        if (fbo != -1) return
        tex = GlStateManager._genTexture()
        VolumeTextures.allocate(tex, GL11.GL_RGBA8, SAMPLE_W, SAMPLE_H, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, GL11.GL_NEAREST, true)
        fbo = GlStateManager.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0)
        pbo = GL15.glGenBuffers()
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo)
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, SAMPLE_BYTES.toLong(), GL15.GL_STREAM_READ)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
    }

    private companion object {
        const val SAMPLE_W = 160
        const val SAMPLE_H = 90
        const val BYTES_PER_PIXEL = 4
        const val SAMPLE_BYTES = SAMPLE_W * SAMPLE_H * BYTES_PER_PIXEL
        const val PACK_ALIGNMENT = 4
        const val MEASURE_FRAMES = 6
        const val EASE = 0.5f
    }
}
