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

    /** Built on the first bake; a subject that cannot draw here is not tried again each frame. */
    private var scene: SketchScene? = null
    private var unavailable = false

    private var sketch: TextureTarget? = null
    private var sketchScale = 0

    /** Set by [advance]: the sketch then bakes only in steps, and [render] shows nothing until it is done. */
    private var incremental = false
    private var staged: SlicedBake? = null

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
        // A scene that waits for data keeps its finished batches in the staged bake, not redrawn each frame.
        if (!incremental) stepBake(scale, Long.MAX_VALUE)
        val texture = sketch ?: return
        blit(graphics, x, y, size, size, texture.colorTextureId, glRows = true)
    }

    /**
     * Bakes a part of the sketch until [deadline] (System.nanoTime), in targets that stay between calls. Returns true
     * when the sketch is ready to [render]. Once called, [render] no longer bakes the whole sketch in one frame.
     */
    fun advance(deadline: Long): Boolean {
        incremental = true
        val scale = guiPixelScale()
        if (scale != sketchScale) {
            close()
            sketchScale = scale
        }
        return stepBake(scale, deadline)
    }

    private fun stepBake(scale: Int, deadline: Long): Boolean {
        if (sketch != null) return true
        if (shader == null || unavailable) return false
        val scene = scene() ?: return false
        val bake = staged ?: SlicedBake(spec, scene, scale).also { staged = it }
        bake.step(deadline)
        if (!bake.done) return false
        sketch = bake.shade()
        bake.close()
        staged = null
        return sketch != null
    }

    /** True when the last [advance] stopped to wait for scene data, so it spent no bake time. */
    val waiting: Boolean get() = staged?.waiting == true

    /** True when the baked sketch is here for the current GUI scale. */
    val ready: Boolean get() = sketch != null && sketchScale == guiPixelScale()

    override fun close() {
        sketch?.destroyBuffers()
        sketch = null
        staged?.close()
        staged = null
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

    private fun scene(): SketchScene? {
        if (unavailable) return null
        val scene = scene ?: SketchScene.of(spec.subject)?.also { scene = it }
        if (scene == null) unavailable = true
        return scene
    }

    /** [into] receives the shaded sketch; without it, the sketch goes into a new target that the caller owns. */
    private fun bake(scale: Int, into: TextureTarget? = null): TextureTarget? {
        if (shader == null) return null
        val scene = scene() ?: return null
        val targets = scratch(size * scale)
        val bake = SlicedBake(spec, scene, scale, targets.colour, targets.normals)
        bake.step(Long.MAX_VALUE)
        // A scene that waits for data does not bake in one call; it needs advance().
        return if (bake.done) bake.shade(into) else null
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

        // Render thread only. Free colour and normals pairs of staged bakes, which each hold a pair over several frames.
        private val stagedPool = ArrayList<StagedTargets>()

        internal fun takeStaged(resolution: Int): StagedTargets {
            val i = stagedPool.indexOfFirst { it.colour.width == resolution }
            return if (i >= 0) stagedPool.removeAt(i) else StagedTargets(newTarget(resolution), newTarget(resolution))
        }

        internal fun giveBackStaged(targets: StagedTargets) {
            stagedPool.add(targets)
            if (stagedPool.size > STAGED_POOL) stagedPool.removeAt(0).destroy()
        }

        /** Colour with item lighting, or view-space normals for the sketch's inner edges. */
        internal fun orthographic(view: SketchView): Matrix4f {
            val e = view.extent
            val depth = maxOf(DEPTH, e * DEPTH_PER_EXTENT)
            return Matrix4f().setOrtho(view.centreX - e, view.centreX + e, view.centreY - e, view.centreY + e, -depth, depth)
        }

        internal fun isometric(scene: SketchScene): (PoseStack) -> Unit = {
            it.mulPose(Axis.XP.rotationDegrees(scene.viewPitch))
            it.mulPose(Axis.YP.rotationDegrees(scene.viewYaw))
        }

        internal fun frameOf(spec: SketchSpec): SketchFrame =
            if (spec.notes && spec.size >= NOTES_MIN_SIZE) SketchFrame.WITH_NOTES else SketchFrame.PLAIN

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
            try {
                draw()
            } finally {
                modelView.popPose()
                RenderSystem.applyModelViewMatrix()
                RenderSystem.setProjectionMatrix(savedProjection, savedSorting)
            }
        }

        @Volatile
        @JvmField
        var shader: ShaderInstance? = null

        // Smaller sketches have no room for notes.
        internal const val NOTES_MIN_SIZE = 96

        private const val DEPTH = 10f
        private const val DEPTH_PER_EXTENT = 4f
        // Inline pages, framed sketches and the viewfinder each bake at one resolution or a few.
        private const val SCRATCH_RESOLUTIONS = 3
        // A sketchbook spread has one bake running and a few that wait for plan sections.
        private const val STAGED_POOL = 4
        const val ISO_PITCH = 30f

        // Viewfinder rebuilds fade out over this part of the render at the left and right; chosen in the preview.
        private const val VIEW_SIDE_FADE = 0.03f
        // Two degrees off the diagonal, so the 45° planes of cross models (flowers, saplings) are not seen edge-on.
        const val ISO_YAW = 227f
    }
}

/** The pass targets of one staged bake; a bake clears them before its first draw. */
internal class StagedTargets(val colour: TextureTarget, val normals: TextureTarget) {
    fun destroy() {
        colour.destroyBuffers()
        normals.destroyBuffers()
    }
}

/**
 * Bakes a scene in steps: the fit renders (a scene without a camera), then the colour and normals passes a batch of
 * parts at a time, then the shade. A step waits while the scene's next parts are not ready. With its own targets the
 * bake can run over several frames; call [close] if it is dropped before [image].
 */
internal class SlicedBake(
    private val spec: SketchSpec,
    private val scene: SketchScene,
    private val scale: Int,
    private val colour: TextureTarget,
    private val normals: TextureTarget,
    private val pooled: StagedTargets? = null,
) {
    constructor(spec: SketchSpec, scene: SketchScene, scale: Int) :
        this(spec, scene, scale, StrikeSketch.takeStaged(spec.size * scale))

    private constructor(spec: SketchSpec, scene: SketchScene, scale: Int, targets: StagedTargets) :
        this(spec, scene, scale, targets.colour, targets.normals, targets)

    private var closed = false

    private val camera = scene.camera
    private val fit = if (camera == null) SketchFit(scene.firstView ?: SketchView(0f, 0f, scene.extentHint), colour.width) else null
    private val orient: (PoseStack) -> Unit =
        if (camera != null) {
            { pose -> pose.mulPoseMatrix(camera.rotation) }
        } else {
            StrikeSketch.isometric(scene)
        }
    private var projection: Matrix4f = camera?.projection ?: StrikeSketch.orthographic(checkNotNull(fit).view)
    private var fitting = fit != null
    private var cursor = PassCursor(scene.parts, scene.partBatch)

    val done: Boolean get() = !fitting && cursor.done

    /** True when the last [step] stopped because the scene's next parts were not ready. */
    var waiting: Boolean = false
        private set

    /** Draws batches until [deadline] (System.nanoTime) passes, at least one when the scene is ready. */
    fun step(deadline: Long) {
        waiting = false
        while (!done) {
            if (!scene.partsReady(cursor.from, cursor.to)) {
                waiting = true
                return
            }
            if (cursor.first) {
                colour.clear(Minecraft.ON_OSX)
                if (!fitting) normals.clear(Minecraft.ON_OSX)
            }
            StrikeSketch.drawInto(colour, false, projection, orient, scene, cursor.from, cursor.to)
            if (!fitting) StrikeSketch.drawInto(normals, true, projection, orient, scene, cursor.from, cursor.to)
            cursor.advance()
            if (fitting && cursor.done) measureFit()
            if (System.nanoTime() >= deadline) return
        }
    }

    private fun measureFit() {
        val fit = checkNotNull(fit)
        fit.measured(StrikeSketch.readBack(colour) { StrikeSketchMeasure.bounds(it, scene.fitAlpha) })
        fitting = !fit.done
        projection = StrikeSketch.orthographic(fit.view)
        cursor = PassCursor(scene.parts, scene.partBatch)
    }

    /** The shaded sketch in [into] or a new target, or null when the sketch shader is not loaded. The passes stay open. */
    fun shade(into: TextureTarget? = null): TextureTarget? {
        check(done) { "the bake has passes left" }
        val shader = StrikeSketch.shader ?: return null
        return StrikeSketch.shade(
            shader, colour, normals, scale, spec.size, spec.seed, StrikeSketch.frameOf(spec), rebuilt = camera != null,
            wash = spec.colour, into,
        )
    }

    /** The finished sketch, top row first, or null when the sketch shader is not loaded. Frees the pass targets. */
    fun image(): NativeImage? {
        val out = shade()
        close()
        return out?.let(StrikeSketch::topFirst)
    }

    /** Gives pooled targets back once; targets the caller passed stay with the caller. */
    fun close() {
        if (closed) return
        closed = true
        pooled?.let(StrikeSketch::giveBackStaged)
    }
}
