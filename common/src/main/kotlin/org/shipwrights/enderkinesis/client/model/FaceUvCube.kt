package org.shipwrights.enderkinesis.client.model

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import java.util.EnumSet
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.core.Direction
import org.joml.Vector3f
import org.joml.Vector4f
import org.shipwrights.enderkinesis.mixin.ModelPartCubesAccessor

/**
 * A cube with Blockbench per-face UVs, which CubeListBuilder cannot express.
 * [faceUvs] holds 24 floats: Blockbench `north, east, south, west, up, down`, each `u1, v1, u2, v2`.
 */
class FaceUvCube(
    x: Float, y: Float, z: Float,
    width: Float, height: Float, depth: Float,
    grow: Float, faceUvs: FloatArray, textureWidth: Float, textureHeight: Float,
) : ModelPart.Cube(0, 0, x, y, z, width, height, depth, grow, grow, grow, false, textureWidth, textureHeight, EnumSet.noneOf(Direction::class.java)) {

    private val faces: Array<ModelPart.Polygon>
    private val normalScratch = Vector3f()
    private val posScratch = Vector4f()

    init {
        require(faceUvs.size == FACE_COUNT * UV_PER_FACE) { "need 24 face UV floats, got ${faceUvs.size}" }
        val x0 = x - grow
        val y0 = y - grow
        val z0 = z - grow
        val x1 = x + width + grow
        val y1 = y + height + grow
        val z1 = z + depth + grow
        val v1 = ModelPart.Vertex(x0, y0, z0, 0f, 0f)
        val v2 = ModelPart.Vertex(x1, y0, z0, 0f, 0f)
        val v3 = ModelPart.Vertex(x1, y1, z0, 0f, 0f)
        val v4 = ModelPart.Vertex(x0, y1, z0, 0f, 0f)
        val v5 = ModelPart.Vertex(x0, y0, z1, 0f, 0f)
        val v6 = ModelPart.Vertex(x1, y0, z1, 0f, 0f)
        val v7 = ModelPart.Vertex(x1, y1, z1, 0f, 0f)
        val v8 = ModelPart.Vertex(x0, y1, z1, 0f, 0f)

        fun side(face: Int, corners: Array<ModelPart.Vertex>, direction: Direction): ModelPart.Polygon {
            val i = face * UV_PER_FACE
            return ModelPart.Polygon(
                corners, faceUvs[i], faceUvs[i + 1], faceUvs[i + 2], faceUvs[i + 3],
                textureWidth, textureHeight, false, direction,
            )
        }

        // Blockbench stores up/down UVs with both corners swapped against vanilla's polygon order.
        fun cap(face: Int, corners: Array<ModelPart.Vertex>, direction: Direction): ModelPart.Polygon {
            val i = face * UV_PER_FACE
            return ModelPart.Polygon(
                corners, faceUvs[i + 2], faceUvs[i + 3], faceUvs[i], faceUvs[i + 1],
                textureWidth, textureHeight, false, direction,
            )
        }

        // Model space is mirrored on X and Y against Blockbench: BB east is vanilla WEST, BB up is vanilla DOWN.
        faces = arrayOf(
            cap(BB_UP, arrayOf(v6, v5, v1, v2), Direction.DOWN),
            cap(BB_DOWN, arrayOf(v3, v4, v8, v7), Direction.UP),
            side(BB_EAST, arrayOf(v1, v5, v8, v4), Direction.WEST),
            side(BB_NORTH, arrayOf(v2, v1, v4, v3), Direction.NORTH),
            side(BB_WEST, arrayOf(v6, v2, v3, v7), Direction.EAST),
            side(BB_SOUTH, arrayOf(v5, v6, v7, v8), Direction.SOUTH),
        )
    }

    override fun compile(
        pose: PoseStack.Pose, consumer: VertexConsumer, packedLight: Int, packedOverlay: Int,
        red: Float, green: Float, blue: Float, alpha: Float,
    ) {
        val matrix = pose.pose()
        val normalMatrix = pose.normal()
        for (face in faces) {
            val normal = normalMatrix.transform(normalScratch.set(face.normal))
            for (vertex in face.vertices) {
                val p = matrix.transform(
                    posScratch.set(vertex.pos.x() / UNITS, vertex.pos.y() / UNITS, vertex.pos.z() / UNITS, 1f),
                )
                consumer.vertex(
                    p.x(), p.y(), p.z(), red, green, blue, alpha, vertex.u, vertex.v,
                    packedOverlay, packedLight, normal.x(), normal.y(), normal.z(),
                )
            }
        }
    }

    companion object {
        private const val FACE_COUNT = 6
        private const val UV_PER_FACE = 4
        private const val UNITS = 16f
        private const val BB_NORTH = 0
        private const val BB_EAST = 1
        private const val BB_SOUTH = 2
        private const val BB_WEST = 3
        private const val BB_UP = 4
        private const val BB_DOWN = 5

        fun attach(part: ModelPart, vararg cubes: ModelPart.Cube) {
            @Suppress("CAST_NEVER_SUCCEEDS") // ModelPartCubesAccessor is mixed into ModelPart.
            val accessor = part as ModelPartCubesAccessor
            accessor.`enderkinesis$setCubes`(accessor.`enderkinesis$getCubes`() + cubes)
        }
    }
}
