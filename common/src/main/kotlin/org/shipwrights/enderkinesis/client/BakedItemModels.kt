package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.BakedQuad
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import java.util.WeakHashMap

/** Draws a baked item model's quads at the current pose. `ItemRenderer.renderModelLists`
 *  would do this, but it is private under 1.20.1 Mojang mappings. */
object BakedItemModels {

    // Weak keys: a resource reload replaces every BakedModel.
    private val quadsByModel = WeakHashMap<BakedModel, List<BakedQuad>>()
    private val scratchPos = Vector4f()
    private val scratchNormal = Vector3f()

    /** Manual translucent vertex emitter. Walks the model's
     *  per-direction + null-face quads (same enumeration vanilla's
     *  [ItemRenderer.renderModelLists] uses), transforms each vertex
     *  by the current pose, and writes through
     *  [RenderType.entityTranslucentCull] with the requested alpha
     *  packed onto the per-vertex colour. The render type's
     *  translucent batch is flushed by the level renderer's
     *  end-of-frame buffer flush so the cataloger summon's quads land
     *  alongside everything else translucent. */
    fun emit(
        model: BakedModel,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
        alpha: Float = 1f,
    ) {
        // LOCATION_BLOCKS is deprecated, but the baked item quads still sample the block atlas.
        @Suppress("DEPRECATION")
        val vertexConsumer = bufferSource.getBuffer(
            RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS),
        )
        val pose = poseStack.last()
        val matrix = pose.pose()
        val normalMatrix = pose.normal()

        // Untinted item models only: RGB is white, alpha carries any fade.
        for (quad in quadsOf(model)) {
            emitQuad(
                vertexConsumer, matrix, normalMatrix, quad,
                1f, 1f, 1f, alpha, packedLight, packedOverlay,
            )
        }
    }

    /** The model's per-direction then null-face quads; fixed-seed, so stable per model. */
    fun quadsOf(model: BakedModel): List<BakedQuad> = quadsByModel.getOrPut(model) {
        val random = RandomSource.create()
        val quads = ArrayList<BakedQuad>()
        for (dir in Direction.values()) {
            random.setSeed(QUAD_RANDOM_SEED)
            quads.addAll(model.getQuads(null, dir, random))
        }
        random.setSeed(QUAD_RANDOM_SEED)
        quads.addAll(model.getQuads(null, null, random))
        quads
    }

    /** Emit one [BakedQuad]
     *  through [vc] with the given vertex colour (RGBA). Vertex layout
     *  matches `DefaultVertexFormat.BLOCK`: 8 ints per vertex,
     *  4 vertices per quad. */
    private fun emitQuad(
        vc: VertexConsumer,
        matrix: Matrix4f,
        normalMatrix: Matrix3f,
        quad: BakedQuad,
        r: Float, g: Float, b: Float, a: Float,
        packedLight: Int, packedOverlay: Int,
    ) {
        val vertices = quad.vertices
        val faceNormal = quad.direction.normal
        val n = scratchNormal.set(faceNormal.x.toFloat(), faceNormal.y.toFloat(), faceNormal.z.toFloat())
        n.mul(normalMatrix)
        for (i in 0..3) {
            val o = i * 8
            val x = Float.fromBits(vertices[o])
            val y = Float.fromBits(vertices[o + 1])
            val z = Float.fromBits(vertices[o + 2])
            val u = Float.fromBits(vertices[o + 4])
            val v = Float.fromBits(vertices[o + 5])
            val pos = scratchPos.set(x, y, z, 1f)
            pos.mul(matrix)
            vc.vertex(pos.x().toDouble(), pos.y().toDouble(), pos.z().toDouble())
                .color(r, g, b, a)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(packedLight)
                .normal(n.x(), n.y(), n.z())
                .endVertex()
        }
    }

    /** Same seed [ItemRenderer.renderModelLists] uses — keeps any
     *  non-deterministic per-face variant baked-models picking the
     *  same face. */
    private const val QUAD_RANDOM_SEED: Long = 42L
}
