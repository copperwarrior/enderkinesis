package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector4f
import org.shipwrights.enderkinesis.client.model.SselithMothModel
import org.shipwrights.enderkinesis.entity.SselithMothEntity
import kotlin.math.floor

/**
 * Camouflage layer for landed moths: repaints the moth with the textures
 * of the blocks under its wings, pixel-locked to each block's texel grid.
 *
 * The projection is BAKED, not live: pass 1 renders the model in the
 * frozen landing pose (idle animation at t=0) through a capturing
 * consumer, in world-aligned moth-relative coordinates, giving each vertex a rest-pose
 * face-plane coordinate. Pass 2 renders the LIVE animated model, with a
 * wrapping consumer that writes each vertex's baked coordinate into the
 * free overlay channel (fixed-point shorts — overlay is unused since the
 * hurt flash is disabled for this type). Result: the pattern is aligned
 * to the world grid AND stays glued to the wings as they animate — a
 * live per-fragment projection can only give one or the other.
 *
 * The wing footprint spans blocks with different textures; the layer
 * builds a face-grid cell → sprite map and draws up to eight sprites in
 * one pass, with a per-cell sprite slot the shader looks up.
 */
class SselithMothCamoLayer(
    parent: RenderLayerParent<SselithMothEntity, SselithMothModel>,
) : RenderLayer<SselithMothEntity, SselithMothModel>(parent) {

    /** Rest-pose world positions for one landing; rebaked only when an input of the bake changes. */
    private class RestPose {
        var positions = FloatArray(INITIAL_REST_FLOATS)
        var size = 0
        private var x = Double.NaN
        private var y = 0.0
        private var z = 0.0
        private var yaw = 0f
        private var nx = 0.0
        private var ny = 0.0
        private var nz = 0.0
        private var frozenBlend = 0f

        fun matches(entity: SselithMothEntity, normal: Vec3, blend: Float): Boolean =
            x == entity.x && y == entity.y && z == entity.z && yaw == entity.yBodyRot &&
                nx == normal.x && ny == normal.y && nz == normal.z && frozenBlend == blend

        fun markBaked(entity: SselithMothEntity, normal: Vec3, blend: Float) {
            x = entity.x
            y = entity.y
            z = entity.z
            yaw = entity.yBodyRot
            nx = normal.x
            ny = normal.y
            nz = normal.z
            frozenBlend = blend
        }

        fun add(value: Float) {
            if (size == positions.size) positions = positions.copyOf(size * 2)
            positions[size++] = value
        }
    }

    /** entityId → rest pose for its current landing. */
    private val restCache = HashMap<Int, RestPose>()

    private class CachedCamo(
        val perch: BlockPos,
        val face: Direction,
        val groups: List<CamoGroup>,
    )

    /** Up to [GROUP_SPRITES] sprites drawn in one pass: each column of [rectsA]/[rectsB] is a
     *  sprite's atlas rect, and [slots] packs a 4-bit slot per face-grid cell (15 = not this pass). */
    private class CamoGroup(val rectsA: Matrix4f, val rectsB: Matrix4f, val slots: IntArray)

    /** entityId → cell map for its current landing. */
    private val cellCache = HashMap<Int, CachedCamo>()

    override fun render(
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
        entity: SselithMothEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        partialTick: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        // perchRender* accessors survive the takeoff fade — the camo fades
        // out with the blend (CamoAlpha below) instead of vanishing the
        // moment the synced face clears.
        val face = entity.perchRenderFace() ?: return
        val normal = entity.perchRenderNormal() ?: return
        val blend = entity.landedBlend(partialTick)
        if (blend < 0.05f) return
        val shader = MothCamoRenderType.shader ?: return

        val perchBlock = entity.perchRenderBlock() ?: return
        val level = entity.level()
        if (level.getBlockState(perchBlock).isAir) return

        // Face tangents matching vanilla's per-face UV conventions, so the
        // projected texels line up with the block's own rendered face
        // (walls run mirrored u; side/down v runs "world-down").
        val u: Vec3
        val v: Vec3
        when (face) {
            Direction.UP -> { u = Vec3(1.0, 0.0, 0.0); v = Vec3(0.0, 0.0, 1.0) }
            Direction.DOWN -> { u = Vec3(1.0, 0.0, 0.0); v = Vec3(0.0, 0.0, -1.0) }
            Direction.NORTH -> { u = Vec3(-1.0, 0.0, 0.0); v = Vec3(0.0, -1.0, 0.0) }
            Direction.SOUTH -> { u = Vec3(1.0, 0.0, 0.0); v = Vec3(0.0, -1.0, 0.0) }
            Direction.WEST -> { u = Vec3(0.0, 0.0, 1.0); v = Vec3(0.0, -1.0, 0.0) }
            Direction.EAST -> { u = Vec3(0.0, 0.0, -1.0); v = Vec3(0.0, -1.0, 0.0) }
        }

        // ---- Pass 1: rest-pose world positions, baked per landing. ------
        // The bake reads the setupAnim blend at FROZEN_AGE, so that value is part of the cache key.
        val frozenBlend = entity.landedBlend(FROZEN_AGE - entity.tickCount)
        if (restCache.size > CACHE_LIMIT && entity.id !in restCache) restCache.clear()
        val rest = restCache.getOrPut(entity.id) { RestPose() }
        if (!rest.matches(entity, normal, frozenBlend)) {
            bakeRestPose(entity, normal, packedLight, rest)
            rest.markBaked(entity, normal, frozenBlend)
            // Restore the live pose for the camo draws (and any later layers).
            parentModel.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch)
        }

        // ---- Face-grid cell → sprite map (cached per landing). ---------
        // Terrain and perch are static while landed, so the probe sweep
        // runs once per touchdown instead of every frame.
        val cached = cellCache[entity.id]
        val groups = if (cached != null && cached.perch == perchBlock && cached.face == face) {
            cached.groups
        } else {
            val built = buildCellMap(entity, perchBlock, face, u, v)
            if (cellCache.size > CACHE_LIMIT) cellCache.clear()
            cellCache[entity.id] = CachedCamo(perchBlock, face, built)
            built
        }
        if (groups.isEmpty()) return
        val origin = Vec3(perchBlock.x.toDouble(), perchBlock.y.toDouble(), perchBlock.z.toDouble())

        shader.getUniform("CamoAlpha")?.set(blend)

        // Undistorted projection frame: rotate the face tangents by the
        // perch tilt (faceNormal → SDF normal) so the projection plane IS
        // the tilted wing plane — projecting along the face normal onto a
        // tilted body foreshortens the pattern into a skew. The anchor
        // offset keeps the texel phase continuous with the block grid at
        // the contact point; at zero tilt this reduces exactly to the
        // plain face projection.
        val faceN = Vec3(face.stepX.toDouble(), face.stepY.toDouble(), face.stepZ.toDouble())
        val tilt = org.joml.Quaternionf().rotationTo(
            faceN.x.toFloat(), faceN.y.toFloat(), faceN.z.toFloat(),
            normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat(),
        )
        val uTilted = rotated(tilt, u)
        val vTilted = rotated(tilt, v)
        val entityPos = Vec3(entity.x, entity.y, entity.z)
        val anchorRel = entityPos.subtract(origin)
        val anchorDU = anchorRel.x * u.x + anchorRel.y * u.y + anchorRel.z * u.z
        val anchorDV = anchorRel.x * v.x + anchorRel.y * v.y + anchorRel.z * v.z

        // ---- Pass 2: live model, baked projection, one draw per sprite.
        for (group in groups) {
            shader.getUniform("SpriteRectsA")?.set(group.rectsA)
            shader.getUniform("SpriteRectsB")?.set(group.rectsB)
            for (i in 0 until SLOT_VECTORS) {
                val o = i * 4
                shader.getUniform("CellSlots$i")
                    ?.setSafe(group.slots[o], group.slots[o + 1], group.slots[o + 2], group.slots[o + 3])
            }
            val consumer = ProjectingConsumer(
                buffer.getBuffer(MothCamoRenderType.TYPE),
                rest, uTilted, vTilted, anchorDU, anchorDV,
            )
            parentModel.renderToBuffer(
                poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY,
                1.0f, 1.0f, 1.0f, 1.0f,
            )
            // Uniforms are per draw, so flush before the next group or moth sets its own.
            // A glowing moth gets an outline wrapper, which sends this type to the main buffer source.
            (buffer as? MultiBufferSource.BufferSource ?: Minecraft.getInstance().renderBuffers().bufferSource())
                .endBatch(MothCamoRenderType.TYPE)
        }
    }

    /** Freezes the pose (idle at t=0) and rebuilds the entity's world transform from scratch — the
     *  same chain LivingEntityRenderer + setupRotations apply, but with the moth's position as the
     *  origin, so the captured positions are world-aligned offsets from the moth. */
    private fun bakeRestPose(entity: SselithMothEntity, normal: Vec3, packedLight: Int, rest: RestPose) {
        val yaw = entity.yBodyRot
        parentModel.setupAnim(entity, 0f, 0f, FROZEN_AGE, 0f, 0f)
        val worldPose = PoseStack()
        // Entity-relative, not absolute: float vertices lose sub-block precision far from the origin.
        worldPose.mulPose(Axis.YP.rotationDegrees(180f - yaw))
        SselithMothRenderer.applyPerchAlignment(worldPose, normal, yaw, 1f)
        worldPose.scale(-1f, -1f, 1f)
        worldPose.translate(0.0, -1.501, 0.0)
        rest.size = 0
        parentModel.renderToBuffer(
            worldPose, CapturingConsumer(rest), packedLight,
            OverlayTexture.NO_OVERLAY, 1.0f, 1.0f, 1.0f, 1.0f,
        )
    }

    /** Probes every face-grid cell for its representative surface block.
     *  Cells whose probes find nothing solid within range — wings hanging
     *  far over a ledge — fall back to the PERCH block's sprite, so the
     *  camo always covers the whole wing. */
    private fun buildCellMap(
        entity: SselithMothEntity,
        perchBlock: BlockPos,
        face: Direction,
        u: Vec3,
        v: Vec3,
    ): List<CamoGroup> {
        val level = entity.level()
        val shaper = Minecraft.getInstance().blockRenderer.blockModelShaper
        val perchSprite = shaper.getParticleIcon(level.getBlockState(perchBlock))
        val origin = Vec3(perchBlock.x.toDouble(), perchBlock.y.toDouble(), perchBlock.z.toDouble())
        val sprites = ArrayList<TextureAtlasSprite>()
        val cellSprite = IntArray(GRID_W * GRID_H)
        val scratch = BlockPos.MutableBlockPos()
        for (cu in -GRID_OFF_U until GRID_W - GRID_OFF_U) {
            for (cv in -GRID_OFF_V until GRID_H - GRID_OFF_V) {
                val centre = origin
                    .add(0.5, 0.5, 0.5)
                    .add(u.scale(cu.toDouble()))
                    .add(v.scale(cv.toDouble()))
                var sprite: TextureAtlasSprite? = null
                for (step in PROBE_STEPS) {
                    scratch.set(
                        floor(centre.x + face.stepX * step).toInt(),
                        floor(centre.y + face.stepY * step).toInt(),
                        floor(centre.z + face.stepZ * step).toInt(),
                    )
                    val state = level.getBlockState(scratch)
                    // Skip alpha-textured blocks (glass, leaves, plants —
                    // all non-occluding): their transparent texels project
                    // as garbage. Keep probing downward to the opaque
                    // ground beneath them.
                    if (!state.isAir && state.canOcclude()) {
                        sprite = shaper.getParticleIcon(state)
                        break
                    }
                }
                val chosen = sprite ?: perchSprite
                val idx = (cu + GRID_OFF_U) * GRID_H + (cv + GRID_OFF_V)
                var spriteIndex = sprites.indexOf(chosen)
                if (spriteIndex < 0) {
                    spriteIndex = sprites.size
                    sprites.add(chosen)
                }
                cellSprite[idx] = spriteIndex
            }
        }
        return sprites.indices.chunked(GROUP_SPRITES).map { chunk -> buildGroup(sprites, chunk, cellSprite) }
    }

    private fun buildGroup(sprites: List<TextureAtlasSprite>, chunk: List<Int>, cellSprite: IntArray): CamoGroup {
        val rectsA = Matrix4f()
        val rectsB = Matrix4f()
        for ((slot, spriteIndex) in chunk.withIndex()) {
            val sprite = sprites[spriteIndex]
            val target = if (slot < 4) rectsA else rectsB
            target.setColumn(slot % 4, Vector4f(sprite.u0, sprite.v0, sprite.u1, sprite.v1))
        }
        val slots = IntArray(SLOT_VECTORS * 4) { -1 }
        for (idx in cellSprite.indices) {
            val slot = cellSprite[idx] - chunk.first()
            if (slot !in chunk.indices) continue
            val word = idx shr 3
            val shift = (idx and 7) * 4
            slots[word] = (slots[word] and (0xF shl shift).inv()) or (slot shl shift)
        }
        return CamoGroup(rectsA, rectsB, slots)
    }

    /** Records transformed vertex positions; ignores everything else. */
    private class CapturingConsumer(private val out: RestPose) : VertexConsumer {
        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
            out.add(x.toFloat()); out.add(y.toFloat()); out.add(z.toFloat())
            return this
        }
        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer = this
        override fun uv(u: Float, v: Float): VertexConsumer = this
        override fun overlayCoords(u: Int, v: Int): VertexConsumer = this
        override fun uv2(u: Int, v: Int): VertexConsumer = this
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer = this
        override fun endVertex() {}
        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) {}
        override fun unsetDefaultColor() {}
    }

    /** Passes vertices through, replacing the (unused) overlay channel
     *  with the vertex's REST-POSE wing-plane projection in fixed point
     *  (1/2048 block per unit — ±16 blocks of range in a short). The
     *  projection axes lie IN the tilted wing plane; [offsetU]/[offsetV]
     *  re-phase the coordinates onto the block grid at the perch point.
     *  Pairs vertices with the capture pass by traversal order, which is
     *  deterministic for the same model. */
    private class ProjectingConsumer(
        private val delegate: VertexConsumer,
        private val rest: RestPose,
        private val faceU: Vec3,
        private val faceV: Vec3,
        private val offsetU: Double,
        private val offsetV: Double,
    ) : VertexConsumer {
        private var index = 0

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
            delegate.vertex(x, y, z); return this
        }
        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer {
            delegate.color(r, g, b, a); return this
        }
        override fun uv(u: Float, v: Float): VertexConsumer {
            delegate.uv(u, v); return this
        }
        override fun overlayCoords(u: Int, v: Int): VertexConsumer {
            val base = index * 3
            if (base + 2 < rest.size) {
                val rx = rest.positions[base].toDouble()
                val ry = rest.positions[base + 1].toDouble()
                val rz = rest.positions[base + 2].toDouble()
                val du = rx * faceU.x + ry * faceU.y + rz * faceU.z + offsetU
                val dv = rx * faceV.x + ry * faceV.y + rz * faceV.z + offsetV
                delegate.overlayCoords(
                    Math.round(du * PROJ_SCALE).toInt().coerceIn(-32767, 32767),
                    Math.round(dv * PROJ_SCALE).toInt().coerceIn(-32767, 32767),
                )
            } else {
                delegate.overlayCoords(u, v)
            }
            return this
        }
        override fun uv2(u: Int, v: Int): VertexConsumer {
            delegate.uv2(u, v); return this
        }
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
            delegate.normal(x, y, z); return this
        }
        override fun endVertex() {
            delegate.endVertex(); index++
        }
        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) {}
        override fun unsetDefaultColor() {}
    }

    private companion object {
        private fun rotated(q: org.joml.Quaternionf, vec: Vec3): Vec3 {
            val out = q.transform(org.joml.Vector3f(vec.x.toFloat(), vec.y.toFloat(), vec.z.toFloat()))
            return Vec3(out.x.toDouble(), out.y.toDouble(), out.z.toDouble())
        }

        /** Face-grid extents — must match the constants in moth_camo.fsh.
         *  13×13 covers the wings (trailing tips reach ~5.4 blocks) at any
         *  yaw. */
        private const val GRID_W = 13
        private const val GRID_H = 13
        private const val GRID_OFF_U = 6
        private const val GRID_OFF_V = 6

        /** Normal-axis probes per cell, nearest-the-moth first, continuing
         *  DOWN along the perch normal past air until solid ground — deep
         *  enough that wings hanging over high ledges still find the
         *  terrain beneath (the cell map is cached per landing, so depth
         *  costs nothing per frame). Bottomless cells fall back to the
         *  perch sprite in [buildCellMap]. */
        private val PROBE_STEPS = DoubleArray(14) { if (it == 0) 1.0 else (1 - it).toDouble() }

        /** Sprites per draw: two mat4 uniforms of four rects each — must match moth_camo.fsh. */
        private const val GROUP_SPRITES = 8

        /** ivec4 uniforms holding 4-bit slots for all 169 cells — must match moth_camo.fsh. */
        private const val SLOT_VECTORS = 6

        /** Cell-map and rest-pose cache bound (opportunistically cleared). */
        private const val CACHE_LIMIT = 64

        private const val INITIAL_REST_FLOATS = 2048

        /** Fixed-point scale for the baked projection (units per block) —
         *  must match the divisor in moth_camo.vsh. */
        private const val PROJ_SCALE = 2048.0

        /** Frozen animation time for the bake pose. */
        private const val FROZEN_AGE = 0f
    }
}
