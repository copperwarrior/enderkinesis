package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix3f
import org.joml.Matrix4f

/**
 * The boiling water dome over each fountain the BER queued this frame. Drawn after the water and
 * its murk: from the BER, the dome's depth hid the water surface under it and kept the murk off.
 * Render thread only.
 */
object FountainOfBlessingDome {

    private const val MAX_QUEUED = 8
    private val DOME_TYPE: RenderType = RenderType.entityTranslucent(BLOCK_ATLAS)
    private val WATER_STILL = ResourceLocation("minecraft", "block/water_still")

    // Block x, y, z (packed); surface height in the block; time; vigour; packed light.
    private val blocks = LongArray(MAX_QUEUED)
    private val values = FloatArray(MAX_QUEUED * 3)
    private val lights = IntArray(MAX_QUEUED)
    private var queued = 0

    private val heights = FloatArray(GRID_VERTS * GRID_VERTS)
    private val colourPos = BlockPos.MutableBlockPos()

    fun queue(pos: BlockPos, surface: Float, t: Float, vigour: Float, light: Int) {
        if (queued == MAX_QUEUED) return
        blocks[queued] = pos.asLong()
        values[queued * 3] = surface
        values[queued * 3 + 1] = t
        values[queued * 3 + 2] = vigour
        lights[queued] = light
        queued++
    }

    // Called with the other world-space volumes, after WohlonWaterMurk.
    @JvmStatic
    fun render(poseStack: PoseStack, camera: Camera) {
        if (queued == 0) return
        val count = queued
        queued = 0
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val sprite = mc.getTextureAtlas(BLOCK_ATLAS).apply(WATER_STILL)
        val bufferSource = mc.renderBuffers().bufferSource()
        val vc = bufferSource.getBuffer(DOME_TYPE)
        val cam = camera.position
        for (n in 0 until count) {
            colourPos.set(blocks[n])
            FountainOfBlessingParticles.updateWaterColour(level, colourPos)
            poseStack.pushPose()
            poseStack.translate(colourPos.x - cam.x, colourPos.y - cam.y, colourPos.z - cam.z)
            dome(vc, poseStack.last().pose(), poseStack.last().normal(), sprite, lights[n],
                values[n * 3], values[n * 3 + 1], values[n * 3 + 2])
            poseStack.popPose()
        }
        bufferSource.endBatch(DOME_TYPE)
    }

    /** Height over the pool at offset ([dx], [dz]) from the spring axis. */
    private fun domeHeight(dx: Float, dz: Float, t: Float, vigour: Float): Float {
        val r = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
        if (r >= DOME_RADIUS) return 0f
        val edge = 1f - (r / DOME_RADIUS) * (r / DOME_RADIUS)
        // Unrelated frequencies and directions, so the boil never settles into one rhythm or shape.
        val boil = 0.6f + 0.25f * sin(t * 5.3f + dx * 2.1f) + 0.15f * sin(t * 8.9f - dz * 2.7f + r * 4f)
        val chop = 0.09f * sin(1.7f * dx + 2.3f * dz + 3.1f * t) * sin(2.9f * dx - 1.3f * dz - 2.2f * t) +
            0.05f * sin(r * 11f - t * 9f)
        return DOME_HEIGHT * vigour * edge * edge * boil + chop * vigour * edge + SURFACE_LIFT
    }

    /**
     * A block-aligned grid, so each cell takes its UV inside one block and the water tile is never
     * stretched. Alpha is 0 well inside the outermost cells, so their square edges never show.
     */
    private fun dome(
        vc: VertexConsumer, pose: Matrix4f, normal: Matrix3f, sprite: TextureAtlasSprite,
        light: Int, surface: Float, t: Float, vigour: Float,
    ) {
        for (i in 0 until GRID_VERTS) {
            for (j in 0 until GRID_VERTS) {
                heights[i * GRID_VERTS + j] = domeHeight(gridOffset(i), gridOffset(j), t, vigour)
            }
        }
        for (i in 0 until GRID_CELLS) {
            for (j in 0 until GRID_CELLS) {
                if (cellOutside(i, j)) continue
                val blockX = Math.floor((0.5f + gridOffset(i)).toDouble()).toFloat()
                val blockZ = Math.floor((0.5f + gridOffset(j)).toDouble()).toFloat()
                domeVertex(vc, pose, normal, sprite, light, surface, i, j, blockX, blockZ)
                domeVertex(vc, pose, normal, sprite, light, surface, i, j + 1, blockX, blockZ)
                domeVertex(vc, pose, normal, sprite, light, surface, i + 1, j + 1, blockX, blockZ)
                domeVertex(vc, pose, normal, sprite, light, surface, i + 1, j, blockX, blockZ)
            }
        }
    }

    private fun cellOutside(i: Int, j: Int): Boolean {
        val near = Math.min(Math.abs(gridOffset(i)), Math.abs(gridOffset(i + 1)))
        val nearZ = Math.min(Math.abs(gridOffset(j)), Math.abs(gridOffset(j + 1)))
        return near * near + nearZ * nearZ >= DOME_RADIUS * DOME_RADIUS
    }

    private fun gridOffset(i: Int): Float = -GRID_HALF + i * GRID_STEP

    private fun domeVertex(
        vc: VertexConsumer, pose: Matrix4f, normal: Matrix3f, sprite: TextureAtlasSprite, light: Int,
        surface: Float, i: Int, j: Int, blockX: Float, blockZ: Float,
    ) {
        val dx = gridOffset(i)
        val dz = gridOffset(j)
        val x = 0.5f + dx
        val z = 0.5f + dz
        // Slope from the neighbouring grid heights, so the light shows the shape of the boil.
        val gx = (heightAt(i + 1, j) - heightAt(i - 1, j)) / (2f * GRID_STEP)
        val gz = (heightAt(i, j + 1) - heightAt(i, j - 1)) / (2f * GRID_STEP)
        val inv = 1f / Math.sqrt((gx * gx + 1f + gz * gz).toDouble()).toFloat()
        val r = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat()
        val fade = ((RIM_CLEAR - r / DOME_RADIUS) / (RIM_CLEAR - RIM_SOLID)).coerceIn(0f, 1f)
        vc.vertex(pose, x, surface + heightAt(i, j), z)
            .color(FountainOfBlessingParticles.red, FountainOfBlessingParticles.green, FountainOfBlessingParticles.blue,
                WATER_ALPHA * fade * fade * (3f - 2f * fade))
            .uv(sprite.getU(((x - blockX) * 16f).toDouble()), sprite.getV(((z - blockZ) * 16f).toDouble()))
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(normal, -gx * inv, inv, -gz * inv)
            .endVertex()
    }

    private fun heightAt(i: Int, j: Int): Float =
        heights[i.coerceIn(0, GRID_VERTS - 1) * GRID_VERTS + j.coerceIn(0, GRID_VERTS - 1)]

    private fun sin(x: Float): Float = Math.sin(x.toDouble()).toFloat()

    private const val DOME_RADIUS = 1.8f
    private const val DOME_HEIGHT = 0.6f
    /** Half the grid's width; with the grid step it puts grid lines on block edges. */
    private const val GRID_HALF = 2.0f
    private const val GRID_STEP = 0.25f
    private const val GRID_CELLS = 16
    private const val GRID_VERTS = GRID_CELLS + 1
    /** Keeps the dome clear of the vanilla water surface, or the two z-fight. */
    private const val SURFACE_LIFT = 0.01f
    /** Alpha is full inside this share of the radius and 0 past [RIM_CLEAR]. */
    private const val RIM_SOLID = 0.35f
    private const val RIM_CLEAR = 0.85f
    private const val WATER_ALPHA = 0.88f
}
