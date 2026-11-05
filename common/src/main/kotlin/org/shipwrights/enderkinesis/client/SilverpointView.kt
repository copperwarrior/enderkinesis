package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import dev.architectury.event.EventResult
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.event.events.client.ClientRawInputEvent
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.shipwrights.enderkinesis.item.SilverpointItem
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.item.SilverpointNetwork
import org.shipwrights.enderkinesis.item.SilverpointPicture
import org.shipwrights.enderkinesis.item.SketchSpec
import org.shipwrights.enderkinesis.item.SketchSubject
import org.shipwrights.enderkinesis.item.StrikeReadout
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Silverpoint's viewfinder. While the use is held, a square frame shows on the screen and the scroll wheel zooms
 * it. On release, the next frame's depth inside the square is read back with that frame's camera. The blocks and
 * entities that show in it (a face no deeper than the depth there) are gathered over the next ticks, then rebuilt
 * block for block through the same camera, at the frame's scale, on empty paper, in a bake that also runs over ticks.
 * Blocks that reach past the frame's edge fade out, and so does all that lies past the first gap in depth.
 */
object SilverpointView {

    private const val SCALE = 3
    private const val VIEW_KEY = "view"

    private const val RANGE = 128
    private const val NEAR = 0.05f
    private const val SECTION = 16
    private const val MAX_BLOCKS = 65_536
    private const val SLICE_NANOS = 4_000_000L
    private const val HALF_TURN = 180f

    // A block reaching past the frame's edge fades out between these, as parts of the frame's half-width.
    private const val EDGE_FADE_FROM = 0.95f
    private const val EDGE_FADE_TO = 1.3f
    private const val MIN_FADE = 0.05f

    // The depth gap: skip the nearest part of the blocks, then a gap is GAP_SPAN blocks of depth below GAP_SHARE of
    // the busiest depth so far. Past the cut, content fades out over DEPTH_FADE_SPAN blocks.
    private const val GAP_SKIP_SHARE = 0.2f
    private const val GAP_SHARE = 0.35f
    private const val GAP_SPAN = 2
    private const val DEPTH_FADE_SPAN = 4f

    // A face point counts as seen within this many blocks of the depth drawn at its pixel, plus a part of its distance
    // for depth-buffer precision far away.
    private const val SEEN_SLACK = 0.12f
    private const val SEEN_SLACK_PER_BLOCK = 0.004f
    private const val FACE_INSET = 0.2

    // The frame's side as a part of the screen's shorter side.
    private const val ZOOM_MIN = 0.2f
    private const val ZOOM_MAX = 1f
    private const val ZOOM_STEP = 0.08f
    private const val SHADE = 0x80000000.toInt()
    private const val FRAME_LINE = 0xFFE8E0D0.toInt()

    /** The frame as the level drew it: its depth inside the square, and the camera that drew it. */
    private class Shot(
        val eye: Vec3,
        val rotation: Matrix4f,
        val viewProjection: Matrix4f,
        /** 1 / tan of half the vertical field of view. */
        val focal: Float,
        val near: Float,
        val far: Float,
        val screenWidth: Int,
        val screenHeight: Int,
        val left: Int,
        val bottom: Int,
        val side: Int,
        val depth: FloatArray,
    )

    private class Gathered(val pos: BlockPos, val state: BlockState, val fade: Float, val ground: Boolean, val distance: Float)

    private class Gathering(
        val level: ClientLevel,
        val shot: Shot,
        val camera: SketchScene.SceneCamera,
        val halfTan: Float,
        val frustum: Frustum,
        val sections: LongArray,
    ) {
        var next = 0
        val blocks = ArrayList<Gathered>()
        var bake: SlicedBake? = null
    }

    // Render thread only.
    private var zoom = ZOOM_MAX
    private var pending = false
    private var gathering: Gathering? = null

    fun init() {
        ClientRawInputEvent.MOUSE_SCROLLED.register { _, amount ->
            if (!framing()) return@register EventResult.pass()
            zoom = (zoom - amount.toFloat() * ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
            EventResult.interruptTrue()
        }
        ClientGuiEvent.RENDER_HUD.register { graphics, _ -> if (framing()) drawFrame(graphics) }
        ClientTickEvent.CLIENT_POST.register {
            sketchSounds()
            step()
        }
    }

    private fun sketchSounds() {
        val level = Minecraft.getInstance().level ?: return
        for (player in level.players()) {
            if (!player.isUsingItem) continue
            val item = player.useItem.item as? SilverpointItem ?: continue
            IncantationSounds.hold(player, item, EKSounds.SILVERPOINT_SKETCH.get(), SoundSource.PLAYERS, 1f)
        }
    }

    private fun framing(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return player.isUsingItem && player.useItem.item is SilverpointItem
    }

    private fun drawFrame(graphics: GuiGraphics) {
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        val side = (minOf(width, height) * zoom).toInt()
        val left = (width - side) / 2
        val top = (height - side) / 2
        val right = left + side
        val bottom = top + side
        graphics.fill(0, 0, width, top, SHADE)
        graphics.fill(0, bottom, width, height, SHADE)
        graphics.fill(0, top, left, bottom, SHADE)
        graphics.fill(right, top, width, bottom, SHADE)
        graphics.renderOutline(left, top, side, side, FRAME_LINE)
    }

    /** The use was released: the next frame's level depth is read back for the sketch. */
    fun capture() {
        gathering?.bake?.close()
        gathering = null
        pending = true
    }

    /** Called after the level draws and before the hand clears the depth. */
    @JvmStatic
    fun afterLevel() {
        if (!pending) return
        pending = false
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val shot = readShot(minecraft) ?: return
        // The square's field of view; the sketch is drawn through it, so the paper holds exactly the frame.
        val halfTan = shot.side / shot.screenHeight.toFloat() / shot.focal
        val projection = Matrix4f().setPerspective(2f * atan(halfTan), 1f, NEAR, RANGE * 1.5f)
        val frustum = Frustum(shot.rotation, projection).also { it.prepare(shot.eye.x, shot.eye.y, shot.eye.z) }
        gathering = Gathering(
            level, shot, SketchScene.SceneCamera(shot.rotation, projection), halfTan, frustum,
            sectionsInView(level, shot.eye, frustum),
        )
    }

    private fun readShot(minecraft: Minecraft): Shot? {
        val main = minecraft.mainRenderTarget
        val camera = minecraft.gameRenderer.mainCamera
        val width = main.width
        val height = main.height
        val side = (minOf(width, height) * zoom).toInt()
        if (side <= 0) return null
        val left = (width - side) / 2
        val bottom = (height - side) / 2
        val buffer = MemoryUtil.memAllocFloat(side * side)
        val depth = try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId)
            GL11.glReadPixels(left, bottom, side, side, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer)
            FloatArray(side * side).also { buffer.get(it) }
        } finally {
            MemoryUtil.memFree(buffer)
            main.bindWrite(true)
        }
        val projection = Matrix4f(RenderSystem.getProjectionMatrix())
        val rotation = Matrix4f()
            .rotate(Axis.XP.rotationDegrees(camera.xRot))
            .rotate(Axis.YP.rotationDegrees(camera.yRot + HALF_TURN))
        // A GL perspective holds its planes in m22 and m32.
        val near = projection.m32() / (projection.m22() - 1f)
        val far = projection.m32() / (projection.m22() + 1f)
        return Shot(
            camera.position, rotation, Matrix4f(projection).mul(rotation), projection.m11(), near, far,
            width, height, left, bottom, side, depth,
        )
    }

    private fun step() {
        val gathering = gathering ?: return
        if (Minecraft.getInstance().level !== gathering.level) {
            gathering.bake?.close()
            this.gathering = null
            return
        }
        val deadline = System.nanoTime() + SLICE_NANOS
        val bake = gathering.bake
        if (bake != null) {
            bake.step(deadline)
            if (bake.done) {
                this.gathering = null
                send(bake)
            }
            return
        }
        while (gathering.next < gathering.sections.size && gathering.blocks.size < MAX_BLOCKS) {
            gathering.sections[gathering.next++].let { gatherSection(gathering, it) }
            if (System.nanoTime() > deadline) return
        }
        gathering.bake = startBake(gathering)
        if (gathering.bake == null) this.gathering = null
    }

    /** Section positions within [RANGE] that the frame's frustum sees, nearest first. */
    private fun sectionsInView(level: ClientLevel, eye: Vec3, frustum: Frustum): LongArray {
        fun sections(centre: Double, low: Int = Int.MIN_VALUE, high: Int = Int.MAX_VALUE): IntRange =
            Math.floorDiv(maxOf(Mth.floor(centre) - RANGE, low), SECTION)..Math.floorDiv(minOf(Mth.floor(centre) + RANGE, high), SECTION)
        val found = ArrayList<Pair<Long, Double>>()
        val sectionsY = sections(eye.y, level.minBuildHeight, level.maxBuildHeight - 1)
        for (sx in sections(eye.x)) for (sy in sectionsY) for (sz in sections(eye.z)) {
            val x0 = sx * SECTION.toDouble()
            val y0 = sy * SECTION.toDouble()
            val z0 = sz * SECTION.toDouble()
            if (!frustum.isVisible(AABB(x0, y0, z0, x0 + SECTION, y0 + SECTION, z0 + SECTION))) continue
            val middle = Vec3(x0 + SECTION / 2.0, y0 + SECTION / 2.0, z0 + SECTION / 2.0)
            found.add(SectionPos.asLong(sx, sy, sz) to middle.distanceToSqr(eye))
        }
        return found.sortedBy { it.second }.map { it.first }.toLongArray()
    }

    /** The section's drawn blocks within [RANGE] that show in the frame. */
    private fun gatherSection(gathering: Gathering, section: Long) {
        val level = gathering.level
        val eye = gathering.shot.eye
        val pos = BlockPos.MutableBlockPos()
        val scratch = Vector4f()
        val x0 = SectionPos.sectionToBlockCoord(SectionPos.x(section))
        val y0 = SectionPos.sectionToBlockCoord(SectionPos.y(section))
        val z0 = SectionPos.sectionToBlockCoord(SectionPos.z(section))
        for (x in x0 until x0 + SECTION) for (y in y0 until y0 + SECTION) for (z in z0 until z0 + SECTION) {
            val dx = x + 0.5 - eye.x
            val dy = y + 0.5 - eye.y
            val dz = z + 0.5 - eye.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
            if (distance > RANGE) continue
            pos.set(x, y, z)
            val state = level.getBlockState(pos)
            if (state.isAir || state.block is LiquidBlock || hidden(level, pos, state)) continue
            if (!gathering.frustum.isVisible(AABB(pos))) continue
            val fade = 1f - smoothstep(EDGE_FADE_FROM, EDGE_FADE_TO, reach(gathering, x, y, z))
            if (fade < MIN_FADE || !seen(gathering.shot, x, y, z, scratch)) continue
            gathering.blocks.add(Gathered(pos.immutable(), state, fade, ObjectFinder.isGround(state), distance))
        }
    }

    /**
     * True when a point on a face of the block toward the eye was drawn in the frame: no deeper than the depth there.
     * Each face is tried at its middle and four points near its corners, so a block that peeks round an edge counts.
     */
    private fun seen(shot: Shot, x: Int, y: Int, z: Int, scratch: Vector4f): Boolean {
        val eye = shot.eye
        for (axis in 0..2) {
            val low = when (axis) { 0 -> x; 1 -> y; else -> z }
            val eyeAxis = when (axis) { 0 -> eye.x; 1 -> eye.y; else -> eye.z }
            val plane = when {
                eyeAxis < low -> low.toDouble()
                eyeAxis > low + 1 -> low + 1.0
                else -> continue
            }
            for (k in 0 until FACE_SAMPLES.size / 2) {
                val a = FACE_SAMPLES[2 * k]
                val b = FACE_SAMPLES[2 * k + 1]
                val (px, py, pz) = when (axis) {
                    0 -> Triple(plane, y + a, z + b)
                    1 -> Triple(x + a, plane, z + b)
                    else -> Triple(x + a, y + b, plane)
                }
                if (drawnAt(shot, px, py, pz, 0f, scratch)) return true
            }
        }
        return false
    }

    /** True when the world point is inside the frame and no deeper than the level's depth there, within [slack]. */
    private fun drawnAt(shot: Shot, px: Double, py: Double, pz: Double, slack: Float, scratch: Vector4f): Boolean {
        val eye = shot.eye
        shot.viewProjection.transform(scratch.set((px - eye.x).toFloat(), (py - eye.y).toFloat(), (pz - eye.z).toFloat(), 1f))
        if (scratch.w <= 0f) return false
        val sx = ((scratch.x / scratch.w * 0.5f + 0.5f) * shot.screenWidth).toInt() - shot.left
        val sy = ((scratch.y / scratch.w * 0.5f + 0.5f) * shot.screenHeight).toInt() - shot.bottom
        if (sx !in 0 until shot.side || sy !in 0 until shot.side) return false
        val drawn = linear(shot, shot.depth[sy * shot.side + sx])
        val point = linear(shot, scratch.z / scratch.w * 0.5f + 0.5f)
        return point <= drawn + SEEN_SLACK + SEEN_SLACK_PER_BLOCK * point + slack
    }

    /** Distance along the view for a depth-buffer value. */
    private fun linear(shot: Shot, depth: Float): Float {
        val ndc = depth * 2f - 1f
        return 2f * shot.near * shot.far / (shot.far + shot.near - ndc * (shot.far - shot.near))
    }

    /** How far the block's outline reaches from the frame's centre, in frame half-widths; past 1 it crosses the edge. */
    private fun reach(gathering: Gathering, x: Int, y: Int, z: Int): Float {
        val rotation = gathering.shot.rotation
        val eye = gathering.shot.eye
        val corner = Vector3f()
        var reach = 0f
        for (dx in 0..1) for (dy in 0..1) for (dz in 0..1) {
            rotation.transformPosition(
                (x + dx - eye.x).toFloat(), (y + dy - eye.y).toFloat(), (z + dz - eye.z).toFloat(), corner,
            )
            val depth = -corner.z
            if (depth < NEAR) return Float.MAX_VALUE
            reach = maxOf(reach, maxOf(abs(corner.x), abs(corner.y)) / depth / gathering.halfTan)
        }
        return reach
    }

    /** Not drawn, or a full block with a full block on every side, which nothing can show. */
    private fun hidden(level: ClientLevel, pos: BlockPos, state: BlockState): Boolean {
        if (state.renderShape == RenderShape.INVISIBLE) return true
        if (!state.isSolidRender(level, pos)) return false
        val near = BlockPos.MutableBlockPos()
        return Direction.entries.all {
            near.setWithOffset(pos, it)
            level.getBlockState(near).isSolidRender(level, near)
        }
    }

    private fun startBake(gathering: Gathering): SlicedBake? {
        val minecraft = Minecraft.getInstance()
        val level = gathering.level
        val shot = gathering.shot
        val eye = shot.eye
        val partialTick = minecraft.frameTime
        val hiddenViewer = if (minecraft.options.cameraType.isFirstPerson) minecraft.cameraEntity else null
        val cut = depthGap(gathering.blocks.map { it.distance })
        val blocks = gathering.blocks.mapNotNull {
            val fade = it.fade * depthFade(it.distance, cut)
            if (fade < MIN_FADE) return@mapNotNull null
            SketchScene.ViewBlock(
                Vec3(it.pos.x - eye.x, it.pos.y - eye.y, it.pos.z - eye.z), it.state,
                level.getBlockEntity(it.pos)?.saveWithoutMetadata(), fade, it.ground,
            )
        }
        val scratch = Vector4f()
        val entities = level.entitiesForRendering()
            .filter { it !== hiddenViewer && it.position().distanceToSqr(eye) <= RANGE * RANGE.toDouble() }
            .filter { gathering.frustum.isVisible(it.boundingBoxForCulling) }
            .filter {
                // The middle lies inside the entity, so it may sit up to half the entity behind the drawn surface.
                val middle = it.boundingBox.center
                val half = maxOf(it.bbWidth, it.bbHeight) / 2f
                drawnAt(shot, middle.x, middle.y, middle.z, half, scratch)
            }
            .mapNotNull { entity ->
                val feet = entity.getPosition(partialTick)
                val fade = depthFade(feet.distanceTo(eye).toFloat(), cut)
                if (fade < MIN_FADE) null else SketchScene.ViewEntity(feet.subtract(eye), entity, fade)
            }
        if (blocks.isEmpty() && entities.isEmpty()) return null
        val scene = SketchScene.ofCamera(blocks, entities, gathering.camera)
        val spec = SketchSpec(SketchSubject.Picture(VIEW_KEY), Random.nextInt(), StrikeReadout.SKETCH_SIZE, notes = true)
        return SlicedBake(spec, scene, SCALE)
    }

    private fun send(bake: SlicedBake) {
        val resolution = StrikeReadout.SKETCH_SIZE * SCALE
        val alpha = bake.image()?.use { image ->
            ByteArray(resolution * resolution) { i -> (image.getPixelRGBA(i % resolution, i / resolution) ushr 24).toByte() }
        } ?: return
        SilverpointNetwork.send(SilverpointPicture.pack(resolution, alpha))
    }

    /**
     * Where the content ends: the first clear gap in the depths of the visible blocks after the nearest part of them,
     * where few blocks show for a while before the backdrop starts. Without a gap, the whole range.
     */
    private fun depthGap(depths: List<Float>): Float {
        val counts = IntArray(RANGE + 1)
        for (d in depths) counts[d.toInt().coerceIn(0, RANGE)]++
        val smooth = FloatArray(RANGE + 1) { i ->
            (counts[maxOf(0, i - 1)] + counts[i] + counts[minOf(RANGE, i + 1)]) / 3f
        }
        val skip = depths.size * GAP_SKIP_SHARE
        var seen = 0
        var peak = 0f
        var low = 0
        for (i in 0..RANGE) {
            seen += counts[i]
            peak = maxOf(peak, smooth[i])
            if (seen < skip) continue
            low = if (smooth[i] < peak * GAP_SHARE) low + 1 else 0
            if (low == GAP_SPAN) return (i - GAP_SPAN + 1).toFloat()
        }
        return RANGE - DEPTH_FADE_SPAN
    }

    /** 1 before the cut, falling to 0 over [DEPTH_FADE_SPAN] past it, so the back of the content has no hard edge. */
    private fun depthFade(distance: Float, cut: Float): Float = 1f - smoothstep(cut, cut + DEPTH_FADE_SPAN, distance)

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // Face sample points (two face-plane coordinates each): the middle, then four points in from the corners.
    private val FACE_SAMPLES = doubleArrayOf(
        0.5, 0.5,
        FACE_INSET, FACE_INSET, 1 - FACE_INSET, FACE_INSET, FACE_INSET, 1 - FACE_INSET, 1 - FACE_INSET, 1 - FACE_INSET,
    )
}
