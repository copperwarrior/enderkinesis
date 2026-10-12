package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.client.model.StatueOwlModel
import java.util.function.Supplier

/**
 * GPU line-of-sight test for owl statues. Each tracked owl owns one bit of an RGBA8 mask:
 *  1. Before particles: draw each owl's geometry, depth-tested against the world, OR-ing its bit.
 *  2. After the hand: clear every bit where the hand wrote depth.
 *  3. After the GUI: clear every bit where the GUI left opaque coverage in main alpha.
 *  4. One occlusion query per owl counts its surviving pixels; results are read frames later.
 */
object OwlSightPasses {

    @Volatile
    @JvmField
    var shader: ShaderInstance? = null

    /** Read by GlStateManagerOwlSightMixin. Render-thread only. */
    @JvmField
    var coverageActive: Boolean = false

    private const val MODE_MASK = 0
    private const val MODE_CLEAR = 1
    private const val MODE_UI = 2
    private const val MODE_QUERY = 3
    private const val UI_OPAQUE_ALPHA = 0.98f
    private const val RING_SIZE = 4
    private const val HAND_QUAD_DEPTH = 0.9999f
    private const val BUFFER_BYTES = 1 shl 16

    private class PendingFrame {
        var frame = 0L
        var count = 0
        val keys = LongArray(OwlSightTracker.MAX_TRACKED)
        val queries = IntArray(OwlSightTracker.MAX_TRACKED)
    }

    private val shaderSupplier = Supplier { shader }
    private val ring = Array(RING_SIZE) { PendingFrame() }
    private var ringHead = 0
    private var ringSize = 0
    private var queriesAllocated = false

    private var awaitingFrame = -1L
    private var fbo = -1
    private var maskTexture = -1
    private var maskWidth = 0
    private var maskHeight = 0
    private var attachedDepth = -1

    private val builder by lazy { BufferBuilder(BUFFER_BYTES) }
    private val owlModel by lazy {
        StatueOwlModel(Minecraft.getInstance().entityModels.bakeLayer(StatueOwlModel.LAYER_LOCATION))
    }
    private val scratchPose = PoseStack()
    private val worldProjection = Matrix4f()
    private val identity = Matrix4f()
    private val zero4 = FloatArray(4)
    private val opaqueAlpha = floatArrayOf(0f, 0f, 0f, 1f)

    fun onBeforeParticles() {
        val frame = OwlSightTracker.startFrame()
        awaitingFrame = -1L
        val count = OwlSightTracker.candidateCount
        if (count == 0) {
            pushFrame(frame, 0)
            return
        }
        val shader = shader
        if (shader == null) {
            OwlSightTracker.holdCandidates(frame)
            return
        }
        worldProjection.set(RenderSystem.getProjectionMatrix())
        val main = Minecraft.getInstance().mainRenderTarget
        val previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        bindMask(main)
        RenderSystem.colorMask(true, true, true, true)
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, zero4)
        // Never leave the mask on a sampler unit while it is the bound colour attachment.
        RenderSystem.setShaderTexture(0, main.colorTextureId)

        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL11.GL_LEQUAL)
        RenderSystem.depthMask(false)
        RenderSystem.enableCull()
        // The owl already wrote this exact surface into depth; pull the mask in front of it.
        // Units only: a slope factor pulls near-edge-on faces through walls, so owls behind blocks read as seen.
        RenderSystem.polygonOffset(0f, -10f)
        RenderSystem.enablePolygonOffset()
        RenderSystem.enableColorLogicOp()
        RenderSystem.logicOp(GlStateManager.LogicOp.OR)
        withModelViewIdentity {
            for (i in 0 until count) drawOwl(shader, i, MODE_MASK)
        }
        RenderSystem.disableColorLogicOp()
        RenderSystem.disablePolygonOffset()
        RenderSystem.depthMask(true)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo)
        awaitingFrame = frame
    }

    fun onAfterHand() {
        val shader = shader ?: return
        if (awaitingFrame < 0) return
        val main = Minecraft.getInstance().mainRenderTarget
        val previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        bindMask(main)
        // The hand pass cleared depth first, so any depth below 1.0 is hand or held item.
        RenderSystem.enableDepthTest()
        RenderSystem.depthFunc(GL11.GL_GREATER)
        RenderSystem.depthMask(false)
        drawFullscreen(shader, MODE_CLEAR, HAND_QUAD_DEPTH, main.colorTextureId)
        RenderSystem.depthFunc(GL11.GL_LEQUAL)
        RenderSystem.depthMask(true)
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo)
    }

    /** Main target is bound and its depth was just cleared for the GUI. */
    fun onGuiBegin() {
        if (awaitingFrame < 0) return
        RenderSystem.colorMask(false, false, false, true)
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, zero4)
        RenderSystem.colorMask(true, true, true, true)
        coverageActive = true
        // Re-issue the live blend func so the alpha remap applies to draws that rely on it.
        GlStateManager._blendFuncSeparate(
            GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
            GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
            GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
            GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
        )
    }

    fun onGuiEnd() {
        val alphaCleared = coverageActive
        if (coverageActive) {
            coverageActive = false
            // Resync GlStateManager's cache: _blendFunc compares only the RGB factors.
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        }
        val shader = shader
        if (awaitingFrame >= 0 && shader != null) {
            val main = Minecraft.getInstance().mainRenderTarget
            bindMask(main)
            RenderSystem.disableDepthTest()
            drawFullscreen(shader, MODE_UI, 0f, main.colorTextureId)
            queryOwls(shader, main)
            RenderSystem.enableDepthTest()
            main.bindWrite(false)
            pushFrame(awaitingFrame, OwlSightTracker.candidateCount)
        }
        // Main alpha reaches the screen on some window systems; zero alpha shows as black.
        if (alphaCleared) {
            Minecraft.getInstance().mainRenderTarget.bindWrite(false)
            RenderSystem.colorMask(false, false, false, true)
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, opaqueAlpha)
            RenderSystem.colorMask(true, true, true, true)
        }
        awaitingFrame = -1L
        OwlSightTracker.clearCandidates()
        pollResults()
    }

    private fun queryOwls(shader: ShaderInstance, main: RenderTarget) {
        allocateQueries()
        val slot = ring[(ringHead + ringSize) % RING_SIZE]
        // Sample the mask while a different framebuffer is bound, so there is no feedback loop.
        main.bindWrite(false)
        RenderSystem.colorMask(false, false, false, false)
        RenderSystem.enableCull()
        RenderSystem.setShaderTexture(0, maskTexture)
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(worldProjection, VertexSorting.DISTANCE_TO_ORIGIN)
        withModelViewIdentity {
            for (i in 0 until OwlSightTracker.candidateCount) {
                GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, slot.queries[i])
                drawOwl(shader, i, MODE_QUERY)
                GL15.glEndQuery(GL15.GL_SAMPLES_PASSED)
            }
        }
        RenderSystem.restoreProjectionMatrix()
        RenderSystem.colorMask(true, true, true, true)
    }

    private fun pushFrame(frame: Long, count: Int) {
        if (ringSize == RING_SIZE) {
            ringHead = (ringHead + 1) % RING_SIZE
            ringSize--
        }
        val slot = ring[(ringHead + ringSize) % RING_SIZE]
        slot.frame = frame
        slot.count = count
        System.arraycopy(OwlSightTracker.candidateKeys, 0, slot.keys, 0, count)
        ringSize++
    }

    private fun pollResults() {
        while (ringSize > 0) {
            val slot = ring[ringHead]
            if (slot.count > 0) {
                val last = slot.queries[slot.count - 1]
                // Queries complete in order, so the last one gates the whole frame.
                if (GL15.glGetQueryObjecti(last, GL15.GL_QUERY_RESULT_AVAILABLE) == GL11.GL_FALSE) return
                for (i in 0 until slot.count) {
                    if (GL15.glGetQueryObjecti(slot.queries[i], GL15.GL_QUERY_RESULT) > 0) {
                        OwlSightTracker.markSeen(slot.keys[i], slot.frame)
                    }
                }
            }
            OwlSightTracker.markResolved(slot.frame)
            ringHead = (ringHead + 1) % RING_SIZE
            ringSize--
        }
    }

    private fun drawOwl(shader: ShaderInstance, index: Int, mode: Int) {
        shader.safeGetUniform("Mode").set(mode)
        shader.safeGetUniform("Bit").set(index)
        scratchPose.last().pose().set(OwlSightTracker.candidateMatrices[index])
        owlModel.setBone2Rotation(OwlSightTracker.candidateXRot[index], OwlSightTracker.candidateYRot[index])
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
        owlModel.renderToBuffer(scratchPose, builder, 0, 0, 1f, 1f, 1f, 1f)
        RenderSystem.setShader(shaderSupplier)
        BufferUploader.drawWithShader(builder.end())
    }

    private fun drawFullscreen(shader: ShaderInstance, mode: Int, depth: Float, texture: Int) {
        shader.safeGetUniform("Mode").set(mode)
        shader.safeGetUniform("AlphaThreshold").set(UI_OPAQUE_ALPHA)
        RenderSystem.setShaderTexture(0, texture)
        RenderSystem.disableCull()
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(identity, VertexSorting.ORTHOGRAPHIC_Z)
        withModelViewIdentity {
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION)
            builder.vertex(-1.0, -1.0, depth.toDouble()).endVertex()
            builder.vertex(1.0, -1.0, depth.toDouble()).endVertex()
            builder.vertex(1.0, 1.0, depth.toDouble()).endVertex()
            builder.vertex(-1.0, 1.0, depth.toDouble()).endVertex()
            RenderSystem.setShader(shaderSupplier)
            BufferUploader.drawWithShader(builder.end())
        }
        RenderSystem.restoreProjectionMatrix()
        RenderSystem.enableCull()
    }

    private inline fun withModelViewIdentity(block: () -> Unit) {
        val stack = RenderSystem.getModelViewStack()
        stack.pushPose()
        stack.setIdentity()
        RenderSystem.applyModelViewMatrix()
        block()
        stack.popPose()
        RenderSystem.applyModelViewMatrix()
    }

    /** Binds an FBO with the mask as colour and the main target's depth texture as depth. */
    private fun bindMask(main: RenderTarget) {
        if (fbo < 0) {
            fbo = GlStateManager.glGenFramebuffers()
            maskTexture = TextureUtil.generateTextureId()
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        if (main.width != maskWidth || main.height != maskHeight) {
            maskWidth = main.width
            maskHeight = main.height
            GlStateManager._bindTexture(maskTexture)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
            GlStateManager._texImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, maskWidth, maskHeight, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null,
            )
            GlStateManager._glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, maskTexture, 0,
            )
        }
        if (main.depthTextureId != attachedDepth) {
            attachedDepth = main.depthTextureId
            GlStateManager._glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, attachedDepth, 0,
            )
        }
    }

    private fun allocateQueries() {
        if (queriesAllocated) return
        queriesAllocated = true
        for (slot in ring) GL15.glGenQueries(slot.queries)
    }
}
