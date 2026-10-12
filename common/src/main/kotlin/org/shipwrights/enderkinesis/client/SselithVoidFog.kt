package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.shaders.BlendMode
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.nio.ByteBuffer
import java.util.function.IntSupplier
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.EffectInstance
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.dimension.SselithRepertory

/** Volumetric fog in Sselith columns that have no blocks from world bottom to world top.
 *  A half-resolution pass ray-marches a toroidal column mask around the camera; a depth-aware
 *  upsample blends the result over main. All state is on the client render thread. */
object SselithVoidFog {

    private val LOG = LogUtils.getLogger()

    // Power of two: the shader wraps mask coords with `& (size - 1)`.
    private const val WINDOW_CHUNKS = 32
    private const val WINDOW_RADIUS = WINDOW_CHUNKS / 2 - 1
    private const val MASK_SIZE = WINDOW_CHUNKS * 16

    // Every chunk in the window lies at least this far from the camera on X and Z.
    private const val MAX_MARCH = WINDOW_RADIUS * 16f

    // Blocks past the world top and bottom over which the fog fades to 0.
    private const val EDGE_FADE = 32f

    // Column texel bits: EMPTY_BIT for a void column, plus bit `size` (2, 4, 8, 16) when the aligned
    // size×size block around the column is all one state. The shader steps over the largest such block.
    private const val EMPTY_BIT = 1
    private const val ALL_UNIFORM = 2 or 4 or 8 or 16
    private const val UNSET = Long.MIN_VALUE

    private val OPAQUE_BLEND = BlendMode()

    private val slotKeys = LongArray(WINDOW_CHUNKS * WINDOW_CHUNKS) { UNSET }
    private val dirtyChunks = LongOpenHashSet()
    private val columnScratch: ByteBuffer = BufferUtils.createByteBuffer(16 * 16)
    private var boundLevel: ClientLevel? = null
    private var windowChunkX = Int.MIN_VALUE
    private var windowChunkZ = Int.MIN_VALUE
    private var columnTex = -1
    private var marchEffect: EffectInstance? = null
    private var upsampleEffect: EffectInstance? = null

    // A program that does not compile fails again on each attempt, so try one time only.
    private var loadFailed = false
    private var halfFbo = -1
    private var halfTex = -1
    private var halfWidth = 0
    private var halfHeight = 0
    private var colorFbo = -1
    private var fboColorTex = -1
    private var fboWidth = 0
    private var fboHeight = 0
    private val gpuTimer = GpuTimer()

    private val invViewProj = Matrix4f()
    private var camBlockX = 0
    private var camBlockZ = 0
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var fogDim = 0f
    private var minY = 0f
    private var maxY = 0f
    private var depthA = 0f
    private var marchDist = 0f
    private var depthB = 0f

    // Kinks in the density curve, sorted; the shader integrates exactly between them.
    private val densityBreaks = FloatArray(8)

    private val pushMarchUniforms: (EffectInstance) -> Unit = ::pushMarchUniforms
    private val pushUpsampleUniforms: (EffectInstance) -> Unit = ::pushUpsampleUniforms

    /** Chunk load and unload hook. */
    @JvmStatic
    fun markChunkDirty(chunkX: Int, chunkZ: Int) {
        if (boundLevel != null) dirtyChunks.add(ChunkPos.asLong(chunkX, chunkZ))
    }

    @JvmStatic
    fun onBlockChanged(pos: BlockPos, oldState: BlockState, newState: BlockState) {
        if (oldState.isAir != newState.isAir) markChunkDirty(pos.x shr 4, pos.z shr 4)
    }

    @JvmStatic
    fun render(poseStack: PoseStack, projection: Matrix4f, camera: Camera, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null || level.dimension() != SselithRepertory.LEVEL_KEY || IrisCompat.isShaderPackInUse()) {
            unbind()
            return
        }
        if (loadFailed) return
        val march = marchEffect ?: loadEffect(mc, "sselith_void_fog")?.also { marchEffect = it }
        val upsample = upsampleEffect ?: loadEffect(mc, "sselith_void_fog_upsample")?.also { upsampleEffect = it }
        if (march == null || upsample == null) {
            loadFailed = true
            return
        }
        ensureTextures()
        if (level !== boundLevel) bind(level)

        val cam = camera.position
        camX = cam.x
        camY = cam.y
        camZ = cam.z
        camBlockX = Mth.floor(cam.x)
        camBlockZ = Mth.floor(cam.z)
        refresh(level, camBlockX shr 4, camBlockZ shr 4)

        // The pose holds only the camera rotation, so the inverse maps clip space to camera-relative world space.
        invViewProj.set(projection).mul(poseStack.last().pose()).invert()
        // Linear depth = depthB / (ndcZ + depthA); both passes compare depths on this scale.
        depthA = projection.m22()
        depthB = projection.m32()
        fogDim = SselithFog.dim(level, partialTick, level.maxBuildHeight.toDouble())
        minY = level.minBuildHeight.toFloat()
        maxY = level.maxBuildHeight.toFloat()
        fillDensityBreaks()
        // Unloaded chunks read as solid, so steps past the render distance add no fog.
        marchDist = minOf(MAX_MARCH, mc.options.effectiveRenderDistance * 16f)
        drawFog(mc, march, upsample)
    }

    /** F3 line in Sselith with the pass's GPU time or why it has none. */
    @JvmStatic
    fun debugLine(): String? {
        val level = Minecraft.getInstance().level ?: return null
        if (level.dimension() != SselithRepertory.LEVEL_KEY) return null
        val state = if (boundLevel == null) "off (shader pack)" else gpuTimer.status
        return "Sselith void fog: $state"
    }

    private fun drawFog(mc: Minecraft, march: EffectInstance, upsample: EffectInstance) {
        val main = mc.mainRenderTarget
        ensureHalfTarget(main.width, main.height)
        RenderSystem.disableDepthTest()
        RenderSystem.disableCull()
        RenderSystem.depthMask(false)
        val timed = mc.options.renderDebug && gpuTimer.begin()

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, halfFbo)
        GlStateManager._viewport(0, 0, halfWidth, halfHeight)
        RenderSystem.disableBlend()
        RefractionPass.drawQuad(march, halfWidth, halfHeight, pushMarchUniforms)

        bindColorOnlyTarget(main)
        GlStateManager._viewport(0, 0, main.width, main.height)
        // BlendMode.apply skips all state, enableBlend too, when this effect's mode was the last one applied.
        RenderSystem.enableBlend()
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD)
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE,
        )
        RefractionPass.drawQuad(upsample, main.width, main.height, pushUpsampleUniforms)

        if (timed) gpuTimer.end()
        RenderSystem.depthMask(true)
        RenderSystem.enableDepthTest()
        RenderSystem.enableCull()
        // Core shaders apply an opaque BlendMode, which turns blending off when the last mode was not
        // opaque; leaving the upsample's mode last made the GUI vignette draw opaque.
        OPAQUE_BLEND.apply()
        RenderSystem.defaultBlendFunc()
        main.bindWrite(false)
    }

    /** R = fog alpha, G = linear depth of the scene sample the march used. */
    private fun ensureHalfTarget(width: Int, height: Int) {
        val w = (width + 1) / 2
        val h = (height + 1) / 2
        if (halfFbo != -1 && w == halfWidth && h == halfHeight) return
        if (halfTex == -1) halfTex = GlStateManager._genTexture()
        if (halfFbo == -1) halfFbo = GlStateManager.glGenFramebuffers()
        GlStateManager._bindTexture(halfTex)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG16F, w, h, 0, GL30.GL_RG, GL11.GL_FLOAT, null as ByteBuffer?)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, halfFbo)
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, halfTex, 0)
        halfWidth = w
        halfHeight = h
    }

    // Main's colour without its depth, so the shader can sample main depth without a GL feedback loop.
    private fun bindColorOnlyTarget(main: RenderTarget) {
        if (colorFbo == -1) colorFbo = GlStateManager.glGenFramebuffers()
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, colorFbo)
        // A resize can hand the new colour texture the deleted one's id, so the size is checked too.
        if (fboColorTex != main.colorTextureId || fboWidth != main.width || fboHeight != main.height) {
            GlStateManager._glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.colorTextureId, 0,
            )
            fboColorTex = main.colorTextureId
            fboWidth = main.width
            fboHeight = main.height
        }
    }

    private fun pushMarchUniforms(eff: EffectInstance) {
        eff.safeGetUniform("InvViewProj").set(invViewProj)
        eff.safeGetUniform("CamBlockWrap").set(camBlockX and (MASK_SIZE - 1), camBlockZ and (MASK_SIZE - 1))
        eff.safeGetUniform("CamFrac").set((camX - camBlockX).toFloat(), (camZ - camBlockZ).toFloat())
        eff.safeGetUniform("CamY").set(camY.toFloat())
        eff.safeGetUniform("DepthParams").set(depthA, depthB)
        val start = SselithFog.start()
        // smoothstep is undefined when start >= end; `/sselithfogrange` can set that.
        eff.safeGetUniform("FogRange").set(start, maxOf(SselithFog.end(), start + 1e-3f))
        eff.safeGetUniform("SlabY").set(minY, maxY)
        eff.safeGetUniform("EdgeFade").set(EDGE_FADE)
        eff.safeGetUniform("BreaksLo").set(densityBreaks[0], densityBreaks[1], densityBreaks[2], densityBreaks[3])
        eff.safeGetUniform("BreaksHi").set(densityBreaks[4], densityBreaks[5], densityBreaks[6], densityBreaks[7])
        eff.safeGetUniform("MaxDist").set(marchDist)
        eff.safeGetUniform("Density").set(SselithRepertory.FOG_FALLOFF_Y.toFloat(), SselithRepertory.FOG_MIN_DENSITY)
    }

    private fun pushUpsampleUniforms(eff: EffectInstance) {
        eff.safeGetUniform("FogColor").set(SselithFog.red() * fogDim, SselithFog.green() * fogDim, SselithFog.blue() * fogDim)
        eff.safeGetUniform("DepthParams").set(depthA, depthB)
        eff.safeGetUniform("HalfSize").set(halfWidth.toFloat(), halfHeight.toFloat())
    }

    private fun fillDensityBreaks() {
        val falloff = SselithRepertory.FOG_FALLOFF_Y.toFloat()
        densityBreaks[0] = minY - EDGE_FADE
        densityBreaks[1] = minY
        densityBreaks[2] = -falloff
        densityBreaks[3] = 0f
        densityBreaks[4] = falloff
        densityBreaks[5] = maxY
        densityBreaks[6] = maxY + EDGE_FADE
        densityBreaks[7] = maxY + EDGE_FADE
        densityBreaks.sort()
    }

    private fun refresh(level: ClientLevel, camChunkX: Int, camChunkZ: Int) {
        if (camChunkX != windowChunkX || camChunkZ != windowChunkZ) {
            windowChunkX = camChunkX
            windowChunkZ = camChunkZ
            for (cz in camChunkZ - WINDOW_RADIUS until camChunkZ - WINDOW_RADIUS + WINDOW_CHUNKS) {
                for (cx in camChunkX - WINDOW_RADIUS until camChunkX - WINDOW_RADIUS + WINDOW_CHUNKS) {
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
            if (cx - windowChunkX + WINDOW_RADIUS !in 0 until WINDOW_CHUNKS) continue
            if (cz - windowChunkZ + WINDOW_RADIUS !in 0 until WINDOW_CHUNKS) continue
            rebuildSlot(level, cx, cz)
        }
        dirtyChunks.clear()
    }

    private fun slotOf(cx: Int, cz: Int): Int =
        (cx and (WINDOW_CHUNKS - 1)) + (cz and (WINDOW_CHUNKS - 1)) * WINDOW_CHUNKS

    private fun rebuildSlot(level: ClientLevel, cx: Int, cz: Int) {
        val slot = slotOf(cx, cz)
        slotKeys[slot] = ChunkPos.asLong(cx, cz)
        val chunk = level.chunkSource.getChunkNow(cx, cz)
        if (chunk != null) {
            scanColumns(chunk)
            openAxialBand(chunk)
            encodeUniformBlocks()
        } else {
            for (i in 0 until 256) columnScratch.put(i, ALL_UNIFORM.toByte())
        }
        upload(columnTex, (slot % WINDOW_CHUNKS) * 16, (slot / WINDOW_CHUNKS) * 16, 16, columnScratch)
    }

    /** Fills [columnScratch] (index `z * 16 + x`) with [EMPTY_BIT] for each void column. */
    private fun scanColumns(chunk: LevelChunk) {
        for (i in 0 until 256) columnScratch.put(i, EMPTY_BIT.toByte())
        for (section in chunk.sections) {
            if (section.hasOnlyAir()) continue
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val i = z * 16 + x
                    if (columnScratch.get(i).toInt() == 0) continue
                    for (y in 0 until 16) {
                        if (!section.getBlockState(x, y, z).isAir) {
                            columnScratch.put(i, 0)
                            break
                        }
                    }
                }
            }
        }
    }

    // The path floor, arches and gap pillars put blocks in the axial band. Scene depth still clips the fog there.
    private fun openAxialBand(chunk: LevelChunk) {
        val minX = chunk.pos.minBlockX
        val minZ = chunk.pos.minBlockZ
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                if (SselithRepertory.isAxialBand(minX + x, minZ + z)) columnScratch.put(z * 16 + x, EMPTY_BIT.toByte())
            }
        }
    }

    private fun encodeUniformBlocks() {
        var size = 2
        while (size <= 16) {
            for (bz in 0 until 16 step size) {
                for (bx in 0 until 16 step size) {
                    if (isUniform(bx, bz, size)) markBlock(bx, bz, size)
                }
            }
            size *= 2
        }
    }

    private fun isUniform(bx: Int, bz: Int, size: Int): Boolean {
        val state = columnScratch.get(bz * 16 + bx).toInt() and EMPTY_BIT
        for (z in bz until bz + size) {
            for (x in bx until bx + size) {
                if ((columnScratch.get(z * 16 + x).toInt() and EMPTY_BIT) != state) return false
            }
        }
        return true
    }

    private fun markBlock(bx: Int, bz: Int, size: Int) {
        for (z in bz until bz + size) {
            for (x in bx until bx + size) {
                val i = z * 16 + x
                columnScratch.put(i, (columnScratch.get(i).toInt() or size).toByte())
            }
        }
    }

    private fun upload(tex: Int, x: Int, y: Int, size: Int, data: ByteBuffer) {
        GlStateManager._bindTexture(tex)
        // NativeImage uploads leave unpack state set; a stale row length would shear this upload.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1)
        data.rewind()
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, size, size, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data)
    }

    private fun bind(level: ClientLevel) {
        boundLevel = level
        slotKeys.fill(UNSET)
        windowChunkX = Int.MIN_VALUE
        windowChunkZ = Int.MIN_VALUE
        dirtyChunks.clear()
    }

    fun onDisconnect() = unbind()

    private fun unbind() {
        if (boundLevel == null) return
        boundLevel = null
        dirtyChunks.clear()
    }

    private fun ensureTextures() {
        if (columnTex == -1) columnTex = createMask(MASK_SIZE)
    }

    private fun createMask(size: Int): Int {
        val tex = GlStateManager._genTexture()
        GlStateManager._bindTexture(tex)
        // The default mipmapped min filter leaves a texture with no mips incomplete; texelFetch then reads 0.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, size, size, 0,
            GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?,
        )
        return tex
    }

    // Samplers a program does not declare are ignored, so both passes get the same set.
    private fun loadEffect(mc: Minecraft, name: String): EffectInstance? = try {
        EffectInstance(mc.resourceManager, name).also {
            it.setSampler("DepthSampler", IntSupplier { Minecraft.getInstance().mainRenderTarget.depthTextureId })
            it.setSampler("ColumnMask", IntSupplier { columnTex })
            it.setSampler("HalfSampler", IntSupplier { halfTex })
        }
    } catch (t: Throwable) {
        LOG.warn("Failed to load Sselith void fog shader program {}", name, t)
        null
    }
}
