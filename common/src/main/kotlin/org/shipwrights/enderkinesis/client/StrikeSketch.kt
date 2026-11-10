package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.shipwrights.enderkinesis.item.SketchSpec
import org.shipwrights.enderkinesis.item.SketchSubject
import kotlin.math.ceil

/** A baked sketch that a page draws with its top-left corner at ([x], [y]) in GUI pixels. Call [close] to free it. */
interface BakedSketch {
    fun render(graphics: GuiGraphics, x: Int, y: Int)
    fun close()
}

/**
 * A graphite sketch of [spec], with field notes placed from its seed. The sketch is static, so it bakes once into a
 * texture and each frame only blits it. Call [close] to free the texture.
 */
class StrikeSketch(private val spec: SketchSpec) : BakedSketch {

    /** A sketch of a scene built here, not from [spec]'s subject. */
    internal constructor(spec: SketchSpec, scene: SketchScene) : this(spec) {
        this.scene = scene
    }

    private val size = spec.size
    private val frame = if (spec.notes && size >= NOTES_MIN_SIZE) SketchFrame.WITH_NOTES else SketchFrame.PLAIN

    /** Built on the first bake; a subject that cannot draw here is not tried again each frame. */
    private var scene: SketchScene? = null
    private var unavailable = false

    private var sketch: TextureTarget? = null
    private var sketchScale = 0

    override fun render(graphics: GuiGraphics, x: Int, y: Int) {
        val scale = guiPixelScale()
        if (scale != sketchScale) {
            close()
            sketchScale = scale
        }
        val subject = spec.subject
        if (subject is SketchSubject.Picture) {
            SketchPictures.textureId(subject.key)?.let { blit(graphics, x, y, size, size, it, glRows = false) }
            return
        }
        val texture = sketch ?: bake(scale)?.also { sketch = it } ?: return
        blit(graphics, x, y, size, size, texture.colorTextureId, glRows = true)
    }

    override fun close() {
        sketch?.destroyBuffers()
        sketch = null
    }

    /** The sketch at [scale] pixels per GUI pixel, top row first, or null when the subject cannot draw. */
    fun bakeImage(scale: Int): NativeImage? = bake(scale)?.let(::topFirst)

    /**
     * Bakes the sketch into [atlas] with its bottom-left corner on GL pixel ([glX], [glY]). Returns false when the
     * sketch shader is not loaded yet; a subject that cannot draw leaves its square empty and returns true.
     */
    internal fun bakeInto(atlas: TextureTarget, scale: Int, glX: Int, glY: Int): Boolean {
        if (shader == null) return false
        val out = bake(scale, scratch(size * scale).shaded()) ?: return true
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, out.frameBufferId)
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, atlas.frameBufferId)
        GL30.glBlitFramebuffer(
            0, 0, out.width, out.height, glX, glY, glX + out.width, glY + out.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST,
        )
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0)
        Minecraft.getInstance().mainRenderTarget.bindWrite(true)
        return true
    }

    /** [into] receives the shaded sketch; without it, the sketch goes into a new target that the caller owns. */
    private fun bake(scale: Int, into: TextureTarget? = null): TextureTarget? {
        val shader = shader ?: return null
        if (unavailable) return null
        val scene = scene ?: SketchScene.of(spec.subject)?.also { scene = it }
        if (scene == null) {
            unavailable = true
            return null
        }
        val resolution = size * scale
        val targets = scratch(resolution)
        val camera = scene.camera
        val projection = camera?.projection ?: orthographic(fitView(targets.colour, scene))
        val orient: (PoseStack) -> Unit = if (camera != null) {
            { it.mulPoseMatrix(camera.rotation) }
        } else {
            isometric(scene)
        }
        val colour = renderScene(targets.colour, normals = false, projection, orient, scene)
        val normals = renderScene(targets.normals, normals = true, projection, orient, scene)
        return shade(shader, colour, normals, scale, size, spec.seed, frame, rebuilt = camera != null, wash = spec.colour, into)
    }

    /** The view centre and half-width, in pose units, that holds the whole render. */
    private class View(val centreX: Float, val centreY: Float, val extent: Float)

    /**
     * Every subject fills the frame: widen until nothing touches the edge, then fit to the drawn outline. A fit that
     * zooms in far is measured again, because a few pixels of outline give a poor fit.
     */
    private fun fitView(target: TextureTarget, scene: SketchScene): View {
        val resolution = target.width
        var view = View(0f, 0f, scene.extentHint)
        var widenings = 0
        var refines = 0
        while (true) {
            renderScene(target, normals = false, orthographic(view), isometric(scene), scene)
            val bounds = readBack(target) { StrikeSketchMeasure.bounds(it, scene.fitAlpha) }
            if (bounds == null) return view
            val touches = bounds[0] == 0 || bounds[1] == 0 || bounds[2] == resolution - 1 || bounds[3] == resolution - 1
            if (touches && widenings < FIT_WIDENINGS) {
                view = View(view.centreX, view.centreY, view.extent * FIT_GROWTH)
                widenings++
                continue
            }
            val fit = fitted(view, bounds, resolution)
            if (touches || fit.extent > view.extent / FIT_REFINE_ZOOM || refines == FIT_REFINES) return fit
            view = fit
            refines++
        }
    }

    private fun fitted(view: View, bounds: IntArray, resolution: Int): View {
        fun toPose(pixel: Int, centre: Float) = centre + ((pixel + 0.5f) / resolution * 2f - 1f) * view.extent
        val left = toPose(bounds[0], view.centreX)
        val right = toPose(bounds[2], view.centreX)
        val bottom = toPose(bounds[1], view.centreY)
        val top = toPose(bounds[3], view.centreY)
        val half = (maxOf(right - left, top - bottom) / 2f + view.extent / resolution) * FIT_MARGIN
        return View((left + right) / 2f, (bottom + top) / 2f, maxOf(half, view.extent * FIT_MIN_FRACTION))
    }

    /** Colour with item lighting, or view-space normals for the sketch's inner edges. */
    private fun orthographic(view: View): Matrix4f {
        val e = view.extent
        val depth = maxOf(DEPTH, e * DEPTH_PER_EXTENT)
        return Matrix4f().setOrtho(view.centreX - e, view.centreX + e, view.centreY - e, view.centreY + e, -depth, depth)
    }

    private fun isometric(scene: SketchScene): (PoseStack) -> Unit = {
        it.mulPose(Axis.XP.rotationDegrees(scene.viewPitch))
        it.mulPose(Axis.YP.rotationDegrees(scene.viewYaw))
    }

    private fun renderScene(
        target: TextureTarget, normals: Boolean, projection: Matrix4f, orient: (PoseStack) -> Unit, scene: SketchScene,
    ): TextureTarget {
        target.clear(Minecraft.ON_OSX)
        drawInto(target, normals, projection, orient, scene, 0, scene.parts)
        return target
    }

    /** The pass targets of one resolution. [shaded] is made only for atlas bakes, whose result is copied out at once. */
    private class Scratch(private val resolution: Int) {
        val colour = newTarget(resolution)
        val normals = newTarget(resolution)
        private var shadedTarget: TextureTarget? = null

        fun shaded(): TextureTarget = shadedTarget ?: blankTarget(resolution, depth = false).also { shadedTarget = it }

        fun destroy() {
            colour.destroyBuffers()
            normals.destroyBuffers()
            shadedTarget?.destroyBuffers()
        }
    }

    companion object {
        internal fun newTarget(resolution: Int): TextureTarget = blankTarget(resolution, depth = true)

        private fun blankTarget(resolution: Int, depth: Boolean): TextureTarget = blankTarget(resolution, resolution, depth)

        internal fun blankTarget(width: Int, height: Int, depth: Boolean): TextureTarget {
            val target = TextureTarget(width, height, depth, Minecraft.ON_OSX)
            target.setClearColor(0f, 0f, 0f, 0f)
            target.clear(Minecraft.ON_OSX)
            return target
        }

        // Render thread only. Bakes reuse these targets, so a run of bakes does not make and free targets for each pass.
        private val scratchPool = object : LinkedHashMap<Int, Scratch>(SCRATCH_RESOLUTIONS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Scratch>): Boolean {
                if (size <= SCRATCH_RESOLUTIONS) return false
                eldest.value.destroy()
                return true
            }
        }

        private fun scratch(resolution: Int): Scratch = scratchPool.getOrPut(resolution) { Scratch(resolution) }

        internal fun guiPixelScale(): Int = ceil(Minecraft.getInstance().window.guiScale).toInt().coerceAtLeast(1)

        /** Draws [textureId] over [width] by [height] GUI pixels at ([x], [y]); [glRows] marks a render target's texture. */
        internal fun blit(graphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, textureId: Int, glRows: Boolean) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader)
            RenderSystem.setShaderTexture(0, textureId)
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            val matrix = graphics.pose().last().pose()
            val x0 = x.toFloat()
            val y0 = y.toFloat()
            val x1 = (x + width).toFloat()
            val y1 = (y + height).toFloat()
            // A render target's texture starts at the bottom row.
            val top = if (glRows) 1f else 0f
            val bottom = 1f - top
            val builder = Tesselator.getInstance().builder
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
            builder.vertex(matrix, x0, y0, 0f).uv(0f, top).endVertex()
            builder.vertex(matrix, x0, y1, 0f).uv(0f, bottom).endVertex()
            builder.vertex(matrix, x1, y1, 0f).uv(1f, bottom).endVertex()
            builder.vertex(matrix, x1, y0, 0f).uv(1f, top).endVertex()
            BufferUploader.drawWithShader(builder.end())
            RenderSystem.disableBlend()
        }

        /** Colour with item lighting, or view-space normals for the sketch's inner edges, for parts [from] until [to]. */
        internal fun drawInto(
            target: TextureTarget, normals: Boolean, projection: Matrix4f, orient: (PoseStack) -> Unit,
            scene: SketchScene, from: Int, to: Int,
        ) {
            target.bindWrite(true)
            withIdentityMatrices(projection) {
                Lighting.setupFor3DItems()
                RenderSystem.enableDepthTest()
                val pose = PoseStack()
                orient(pose)
                val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
                val source = if (normals) StrikeSketchRenderTypes.normalsSource(buffers) else buffers
                val rotation = Matrix4f(pose.last().pose())
                val flames = WillfaerFlameVolume.mark()
                scene.drawPart(pose, source, normals, from, to)
                buffers.endBatch()
                if (!normals) WillfaerFlameVolume.renderInto(target, flames, projection, rotation)
                WillfaerFlameVolume.rewind(flames)
            }
            Minecraft.getInstance().mainRenderTarget.bindWrite(true)
        }

        /** Graphite over [colour] and its [normals], both in GL row order; the inputs stay open. [into] defaults to a new target. */
        @Suppress("LongParameterList") // The pass inputs, the sketch settings and the output target.
        internal fun shade(
            shader: ShaderInstance, colour: TextureTarget, normals: TextureTarget,
            scale: Int, size: Int, seed: Int, frame: SketchFrame, rebuilt: Boolean, wash: Boolean, into: TextureTarget? = null,
        ): TextureTarget {
            val resolution = colour.width
            val measure = readBack(colour) { StrikeSketchMeasure.of(it) }
            val notes = if (frame.notes) StrikeNotes.layout(measure, seed, size, frame) else NoteLayout.NONE

            val out = into ?: blankTarget(resolution, depth = false)
            if (into != null) out.clear(Minecraft.ON_OSX)
            out.bindWrite(true)
            RenderSystem.setShader { shader }
            RenderSystem.setShaderTexture(0, colour.colorTextureId)
            RenderSystem.setShaderTexture(1, normals.colorTextureId)
            shader.safeGetUniform("SketchSize").set(resolution.toFloat(), resolution.toFloat())
            shader.safeGetUniform("PixelScale").set(scale.toFloat())
            shader.safeGetUniform("ToneRange").set(measure.toneLow, measure.toneHigh)
            shader.safeGetUniform("Base").set(measure.baseX, measure.baseY, measure.baseHalfWidth, 0f)
            shader.safeGetUniform("NoteA").set(notes.anchors)
            shader.safeGetUniform("NoteB").set(notes.styles)
            shader.safeGetUniform("NoteCount").set(notes.count.toFloat())
            shader.safeGetUniform("Frame").set(frame.zoom, frame.offsetX, frame.offsetY, 0f)
            shader.safeGetUniform("OutlineGround").set(if (rebuilt) 1f else 0f)
            shader.safeGetUniform("SideFade").set(if (rebuilt) VIEW_SIDE_FADE else 0f)
            shader.safeGetUniform("Colour").set(if (wash) 1f else 0f)
            withIdentityMatrices(Matrix4f()) { fullScreenQuad() }
            Minecraft.getInstance().mainRenderTarget.bindWrite(true)
            return out
        }

        /** Reads [target] top row first, then frees it. */
        internal fun topFirst(target: TextureTarget): NativeImage {
            val image = NativeImage(target.width, target.height, false)
            RenderSystem.bindTexture(target.colorTextureId)
            image.downloadTexture(0, false)
            image.flipY()
            target.destroyBuffers()
            return image
        }

        internal fun <T> readBack(target: TextureTarget, read: (NativeImage) -> T): T =
            NativeImage(target.width, target.height, false).use { image ->
                RenderSystem.bindTexture(target.colorTextureId)
                image.downloadTexture(0, false)
                read(image)
            }

        internal fun fullScreenQuad() {
            RenderSystem.disableBlend()
            RenderSystem.disableDepthTest()
            val builder = Tesselator.getInstance().builder
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
            builder.vertex(-1.0, -1.0, 0.0).uv(0f, 0f).endVertex()
            builder.vertex(1.0, -1.0, 0.0).uv(1f, 0f).endVertex()
            builder.vertex(1.0, 1.0, 0.0).uv(1f, 1f).endVertex()
            builder.vertex(-1.0, 1.0, 0.0).uv(0f, 1f).endVertex()
            BufferUploader.drawWithShader(builder.end())
            RenderSystem.enableDepthTest()
        }

        internal inline fun withIdentityMatrices(projection: Matrix4f, draw: () -> Unit) {
            val savedProjection = Matrix4f(RenderSystem.getProjectionMatrix())
            val savedSorting = RenderSystem.getVertexSorting()
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z)
            val modelView = RenderSystem.getModelViewStack()
            modelView.pushPose()
            modelView.setIdentity()
            RenderSystem.applyModelViewMatrix()
            draw()
            modelView.popPose()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting)
        }

        @Volatile
        @JvmField
        var shader: ShaderInstance? = null

        // Smaller sketches have no room for notes.
        internal const val NOTES_MIN_SIZE = 96

        private const val DEPTH = 10f
        private const val DEPTH_PER_EXTENT = 4f
        private const val FIT_WIDENINGS = 2
        private const val FIT_GROWTH = 3f
        private const val FIT_MARGIN = 1.08f
        private const val FIT_REFINES = 2
        private const val FIT_REFINE_ZOOM = 2f
        private const val FIT_MIN_FRACTION = 0.02f
        // Inline pages, framed sketches and the viewfinder each bake at one resolution or a few.
        private const val SCRATCH_RESOLUTIONS = 3
        const val ISO_PITCH = 30f

        // Viewfinder rebuilds fade out over this part of the render at the left and right; chosen in the preview.
        private const val VIEW_SIDE_FADE = 0.03f
        // Two degrees off the diagonal, so the 45° planes of cross models (flowers, saplings) are not seen edge-on.
        const val ISO_YAW = 227f
    }
}

/**
 * Bakes a scene that has its own camera over several ticks: the colour pass, then the normals pass, a batch of parts at a
 * time, into targets that stay between ticks. Call [close] if it is dropped before [image].
 */
internal class SlicedBake(private val spec: SketchSpec, private val scene: SketchScene, private val scale: Int) {
    private val camera = checkNotNull(scene.camera) { "a sliced bake needs a scene camera" }
    private val orient: (PoseStack) -> Unit = { it.mulPoseMatrix(camera.rotation) }
    private val colour = StrikeSketch.newTarget(spec.size * scale)
    private val normals = StrikeSketch.newTarget(spec.size * scale)
    private var pass = 0
    private var next = 0

    val done: Boolean get() = pass == PASSES

    fun step(deadline: Long) {
        while (pass < PASSES && System.nanoTime() < deadline) {
            val to = minOf(scene.parts, next + BATCH)
            StrikeSketch.drawInto(if (pass == 0) colour else normals, pass == 1, camera.projection, orient, scene, next, to)
            next = to
            if (next == scene.parts) {
                pass++
                next = 0
            }
        }
    }

    /** The finished sketch, top row first, or null when the sketch shader is not loaded. Frees the pass targets. */
    fun image(): NativeImage? {
        check(done) { "the bake has passes left" }
        val shader = StrikeSketch.shader
        val frame = if (spec.notes && spec.size >= StrikeSketch.NOTES_MIN_SIZE) SketchFrame.WITH_NOTES else SketchFrame.PLAIN
        val out = shader?.let { StrikeSketch.shade(it, colour, normals, scale, spec.size, spec.seed, frame, rebuilt = true, wash = spec.colour) }
        close()
        return out?.let(StrikeSketch::topFirst)
    }

    fun close() {
        colour.destroyBuffers()
        normals.destroyBuffers()
    }

    private companion object {
        const val PASSES = 2
        const val BATCH = 256
    }
}
