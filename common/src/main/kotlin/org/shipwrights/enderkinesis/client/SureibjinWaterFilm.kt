package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.level.material.FogType
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.dimension.SureibjinChunkGenerator
import org.shipwrights.enderkinesis.physics.GerstnerOcean

/**
 * A dark sheet immediately above the Sureibjin sea, for a viewer above the water. The water texture
 * alpha sets a limit on the opacity of the water, so the sheet adds the remaining darkness. Far from
 * the camera the sheet lifts into waves. The waves go up only, because the vanilla water hides all
 * that is below its surface.
 */
object SureibjinWaterFilm {

    /** The above-water fog. `FogRendererSureibjinMixin` uses these values. Colour #1f272f. */
    const val FOG_R: Float = 0.122f
    const val FOG_G: Float = 0.153f
    const val FOG_B: Float = 0.184f
    /** Fractions of the far plane distance. */
    const val FOG_START_FRAC: Float = 0.0f
    const val FOG_END_FRAC: Float = 0.95f

    private const val WAVE_INTENSITY: Double = 0.5
    private val WAVE_LIFT: Double = GerstnerOcean.swellMaxAmplitude * WAVE_INTENSITY

    // The underwater fog colour, #020202.
    private const val FILM_RGB: Float = 0.008f
    private const val ALPHA_NEAR: Float = 0.75f
    private const val ALPHA_FAR: Float = 0.97f
    private const val ALPHA_FAR_DISTANCE: Float = 48.0f
    // The part of the fog colour that a full crest gets.
    private const val CREST_SHADE: Float = 0.55f

    // The top of a vanilla still-water block is 8/9 of a block. The gap prevents depth conflict with it.
    private const val FILM_Y: Double = SureibjinChunkGenerator.SEA_LEVEL_Y + 8.0 / 9.0 + 0.01

    // The shortest swell is 13 blocks long, so a larger cell cannot show it.
    private const val FINE_CELL: Int = 6
    private const val COARSE_CELL: Int = FINE_CELL * 4
    private const val FINE_HALF_CELLS: Int = 32
    private const val FINE_HALF_EXTENT: Int = FINE_CELL * FINE_HALF_CELLS
    private const val FINE_VERTS: Int = FINE_HALF_CELLS * 2 + 1
    private const val MAX_COARSE_HALF_CELLS: Int = 48

    private const val WAVE_START_DISTANCE: Float = 24.0f
    private const val WAVE_FULL_DISTANCE: Float = 72.0f
    // The waves go to zero at the edge of the fine grid, where the flat coarse grid starts.
    private const val WAVE_EDGE_FADE_START: Float = FINE_HALF_EXTENT * 0.75f
    private const val WAVE_EDGE_FADE_END: Float = (FINE_HALF_EXTENT - FINE_CELL).toFloat()

    private const val MIN_FAR_PLANE: Float = 32.0f
    private const val MASK_REFRESH_TICKS: Long = 100L
    // Depths below the sea level block. Each wet probe adds a quarter of the full wave height.
    private val DEPTH_PROBES = intArrayOf(0, 3, 7, 11)

    // Render-thread scratch, one entry for each fine vertex.
    private val rawMask = FloatArray(FINE_VERTS * FINE_VERTS)
    private val mask = FloatArray(FINE_VERTS * FINE_VERTS)
    private val lift = FloatArray(FINE_VERTS * FINE_VERTS)
    private val shade = FloatArray(FINE_VERTS * FINE_VERTS)
    private val alpha = FloatArray(FINE_VERTS * FINE_VERTS)
    private val probePos = BlockPos.MutableBlockPos()
    private var maskValid = false
    private var maskOriginX = 0
    private var maskOriginZ = 0
    private var maskTime = 0L

    /** Called from `LevelRendererBubblePassMixin` at the TAIL of `LevelRenderer.renderLevel`. */
    @JvmStatic
    fun render(poseStack: PoseStack, camera: Camera, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null || level.dimension() != Sureibjin.LEVEL_KEY || camera.fluidInCamera != FogType.NONE) {
            maskValid = false
            return
        }

        // The sheet has no fog shader. The alpha decreases with distance as the fog does on the water.
        val farPlane = max(mc.gameRenderer.renderDistance, MIN_FAR_PLANE)
        val fogStart = farPlane * FOG_START_FRAC
        val fogEnd = farPlane * FOG_END_FRAC

        val cam = camera.position
        val originX = Mth.floor(cam.x / COARSE_CELL) * COARSE_CELL
        val originZ = Mth.floor(cam.z / COARSE_CELL) * COARSE_CELL
        refreshMask(level, originX, originZ)
        val offX = (originX - cam.x).toFloat()
        val offY = (FILM_Y - cam.y).toFloat()
        val offZ = (originZ - cam.z).toFloat()
        fillFine(originX, originZ, offX, offY, offZ, level.gameTime + partialTick.toDouble(), fogStart, fogEnd)

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }

        val pose = poseStack.last().pose()
        val buf = Tesselator.getInstance().builder
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        drawFine(buf, pose, offX, offY, offZ)
        drawCoarse(buf, pose, offX, offY, offZ, fogStart, fogEnd)
        BufferUploader.drawWithShader(buf.end())

        RenderSystem.depthMask(true)
        RenderSystem.enableCull()
        RenderSystem.disableBlend()
    }

    /** The wave height factor for each fine vertex: zero on land and near land, one in deep water. */
    private fun refreshMask(level: ClientLevel, originX: Int, originZ: Int) {
        val time = level.gameTime
        if (maskValid && originX == maskOriginX && originZ == maskOriginZ &&
            abs(time - maskTime) < MASK_REFRESH_TICKS
        ) return
        maskValid = true
        maskOriginX = originX
        maskOriginZ = originZ
        maskTime = time

        for (j in 0 until FINE_VERTS) {
            for (i in 0 until FINE_VERTS) {
                val wx = originX + (i - FINE_HALF_CELLS) * FINE_CELL
                val wz = originZ + (j - FINE_HALF_CELLS) * FINE_CELL
                var wet = 0
                for (depth in DEPTH_PROBES) {
                    probePos.set(wx, SureibjinChunkGenerator.SEA_LEVEL_Y - depth, wz)
                    if (!level.getFluidState(probePos).`is`(FluidTags.WATER)) break
                    wet++
                }
                rawMask[j * FINE_VERTS + i] = wet.toFloat() / DEPTH_PROBES.size
            }
        }
        // A vertex next to a dry vertex stays flat, so a wave cannot go through the land between them.
        for (j in 0 until FINE_VERTS) {
            for (i in 0 until FINE_VERTS) {
                var least = 1.0f
                for (nj in max(j - 1, 0)..min(j + 1, FINE_VERTS - 1)) {
                    for (ni in max(i - 1, 0)..min(i + 1, FINE_VERTS - 1)) {
                        least = min(least, rawMask[nj * FINE_VERTS + ni])
                    }
                }
                mask[j * FINE_VERTS + i] = least
            }
        }
    }

    @Suppress("LongParameterList") // The grid origin, the camera offset, the wave time and the fog range.
    private fun fillFine(
        originX: Int, originZ: Int, offX: Float, offY: Float, offZ: Float,
        time: Double, fogStart: Float, fogEnd: Float,
    ) {
        for (j in 0 until FINE_VERTS) {
            for (i in 0 until FINE_VERTS) {
                val index = j * FINE_VERTS + i
                val gx = (i - FINE_HALF_CELLS) * FINE_CELL
                val gz = (j - FINE_HALF_CELLS) * FINE_CELL
                val dx = gx + offX
                val dz = gz + offZ
                val flat = sqrt(dx * dx + dz * dz)
                alpha[index] = alphaAt(sqrt(flat * flat + offY * offY), fogStart, fogEnd)

                val edge = max(abs(gx), abs(gz)).toFloat()
                val amount = mask[index] *
                    smooth(WAVE_START_DISTANCE, WAVE_FULL_DISTANCE, flat) *
                    (1.0f - smooth(WAVE_EDGE_FADE_START, WAVE_EDGE_FADE_END, edge))
                if (amount <= 0.0f) {
                    lift[index] = 0.0f
                    shade[index] = 0.0f
                    continue
                }
                val height = GerstnerOcean.swellHeightAt(
                    (originX + gx).toDouble(), (originZ + gz).toDouble(), time, WAVE_INTENSITY,
                ) + WAVE_LIFT
                val crest = (height / (2.0 * WAVE_LIFT)).toFloat().coerceIn(0.0f, 1.0f)
                lift[index] = amount * height.toFloat().coerceAtLeast(0.0f)
                shade[index] = amount * crest * crest * crest * CREST_SHADE
            }
        }
    }

    private fun drawFine(buf: BufferBuilder, pose: Matrix4f, offX: Float, offY: Float, offZ: Float) {
        for (j in 0 until FINE_VERTS - 1) {
            for (i in 0 until FINE_VERTS - 1) {
                val a = j * FINE_VERTS + i
                val b = a + 1
                val c = a + FINE_VERTS + 1
                val d = a + FINE_VERTS
                if (alpha[a] <= 0.0f && alpha[b] <= 0.0f && alpha[c] <= 0.0f && alpha[d] <= 0.0f) continue
                val x0 = (i - FINE_HALF_CELLS) * FINE_CELL + offX
                val z0 = (j - FINE_HALF_CELLS) * FINE_CELL + offZ
                fineVertex(buf, pose, x0, offY, z0, a)
                fineVertex(buf, pose, x0 + FINE_CELL, offY, z0, b)
                fineVertex(buf, pose, x0 + FINE_CELL, offY, z0 + FINE_CELL, c)
                fineVertex(buf, pose, x0, offY, z0 + FINE_CELL, d)
            }
        }
    }

    @Suppress("LongParameterList") // A wrapper type for one vertex adds an allocation in the render path.
    private fun fineVertex(buf: BufferBuilder, pose: Matrix4f, x: Float, y: Float, z: Float, index: Int) {
        val s = shade[index]
        val r = Mth.lerp(s, FILM_RGB, FOG_R)
        val g = Mth.lerp(s, FILM_RGB, FOG_G)
        val b = Mth.lerp(s, FILM_RGB, FOG_B)
        buf.vertex(pose, x, y + lift[index], z).color(r, g, b, alpha[index]).endVertex()
    }

    /** The flat part of the sheet, from the fine grid out to the end of the fog. */
    @Suppress("LongParameterList") // The camera offset and the fog range.
    private fun drawCoarse(
        buf: BufferBuilder, pose: Matrix4f, offX: Float, offY: Float, offZ: Float,
        fogStart: Float, fogEnd: Float,
    ) {
        val fineHalf = FINE_HALF_EXTENT / COARSE_CELL
        val half = min(Mth.ceil(fogEnd / COARSE_CELL) + 1, MAX_COARSE_HALF_CELLS)
        for (j in -half until half) {
            for (i in -half until half) {
                if (i >= -fineHalf && i < fineHalf && j >= -fineHalf && j < fineHalf) continue
                val x0 = i * COARSE_CELL + offX
                val z0 = j * COARSE_CELL + offZ
                val x1 = x0 + COARSE_CELL
                val z1 = z0 + COARSE_CELL
                val a = alphaAt(length(x0, offY, z0), fogStart, fogEnd)
                val b = alphaAt(length(x1, offY, z0), fogStart, fogEnd)
                val c = alphaAt(length(x1, offY, z1), fogStart, fogEnd)
                val d = alphaAt(length(x0, offY, z1), fogStart, fogEnd)
                if (a <= 0.0f && b <= 0.0f && c <= 0.0f && d <= 0.0f) continue
                buf.vertex(pose, x0, offY, z0).color(FILM_RGB, FILM_RGB, FILM_RGB, a).endVertex()
                buf.vertex(pose, x1, offY, z0).color(FILM_RGB, FILM_RGB, FILM_RGB, b).endVertex()
                buf.vertex(pose, x1, offY, z1).color(FILM_RGB, FILM_RGB, FILM_RGB, c).endVertex()
                buf.vertex(pose, x0, offY, z1).color(FILM_RGB, FILM_RGB, FILM_RGB, d).endVertex()
            }
        }
    }

    private fun alphaAt(dist: Float, fogStart: Float, fogEnd: Float): Float {
        val fog = ((dist - fogStart) / (fogEnd - fogStart)).coerceIn(0.0f, 1.0f)
        return Mth.lerp(smooth(0.0f, ALPHA_FAR_DISTANCE, dist), ALPHA_NEAR, ALPHA_FAR) * (1.0f - fog)
    }

    private fun length(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }
}
