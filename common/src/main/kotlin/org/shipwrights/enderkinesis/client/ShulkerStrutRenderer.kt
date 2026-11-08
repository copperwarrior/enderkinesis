package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.blockentity.ShulkerStrutBlockEntity
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.roundToInt
import org.valkyrienskies.core.api.util.GameTickOnly

/**
 * Draws the strut as the base (bottom) shulker shell plus four ancrite chains spiraling
 * up to the lid ship's corresponding face. The lid (top) shulker shell is drawn
 * separately by [ShulkerStrutTopRenderer] at the lid block's own position — VS2's
 * standard ship-block render pipeline carries it through the joint-driven motion.
 *
 * **Chain twist.** The four chains link the corners of the base's FACING face to the
 * corners of the lid's matching face with a two-step rotational offset
 * (`base[i] → lid[(i + 2) mod 4]`) — base 1,2,3,4 → lid 3,4,1,2. As the lid extends along
 * FACING, opposite-corner pairs cross diagonally between the two faces.
 */
@OptIn(GameTickOnly::class)
class ShulkerStrutRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<ShulkerStrutBlockEntity> {

    private val baseModel: ModelPart

    // Render-thread scratch; one renderer instance serves every strut.
    private val baseCorners = Array(4) { Vector3d() }
    private val lidCorners = Array(4) { Vector3d() }
    private val faceCentre = Vector3d()
    private val lidCentreWorld = Vector3d()
    private val lightPos = BlockPos.MutableBlockPos()
    private val chainRotation = Quaternionf()

    init {
        val root = ctx.bakeLayer(ModelLayers.SHULKER)
        baseModel = root.getChild("base")
    }

    // The chains reach past the base block, so culling by the base's bounds would drop them.
    override fun shouldRenderOffScreen(be: ShulkerStrutBlockEntity): Boolean = true

    override fun render(
        be: ShulkerStrutBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        light: Int,
        overlay: Int,
    ) {
        val state = be.blockState
        val facing: Direction = state.getValue(BlockStateProperties.FACING)

        // --- Base shell — at the BE block, vanilla ShulkerBoxRenderer pose chain.
        val shellVc = buffers.getBuffer(shulkerRenderType(be.dyeColor))
        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.scale(0.9995f, 0.9995f, 0.9995f)
        pose.mulPose(facing.rotation)
        pose.scale(1.0f, -1.0f, -1.0f)
        pose.translate(0.0, -1.0, 0.0)
        baseModel.render(pose, shellVc, light, overlay)
        pose.popPose()

        // --- Chains to lid ship. No ship yet → no chains.
        val shipId = be.topShipId
        if (shipId == ShulkerStrutBlockEntity.NO_SHIP) return
        val level = be.level ?: return
        // `loadedShips` (not `allShips`) — only ships whose renderTransform has actually
        // been initialised on this client. Pulling from `allShips` returns ships whose
        // transform is still the default at-spawn pose (shipyard frame is at chunk-claim
        // coords ~30 million blocks up), so the chains would aim at the sky.
        val lidShip = level.shipObjectWorld.loadedShips.getById(shipId) as? ClientShip ?: return
        val lidPos = be.lidShipyardPos ?: return

        // The base BER's pose stack is in the BE's local frame (world for world-mounted,
        // host shipyard for ship-mounted) and that frame is what VS2's render hook leaves
        // us with. To compute the lid block's corners in the same frame, we have to map
        // the lid ship's world transform back through the host ship's worldToShip (or
        // identity if the strut is world-mounted).
        val hostShip = (level as? ClientLevel)
            ?.getLoadedShipManagingPos(be.blockPos) as? ClientShip

        val lidWorld = lidCentreWorld.set(lidPos.x + 0.5, lidPos.y + 0.5, lidPos.z + 0.5)
        lidShip.renderTransform.shipToWorld.transformPosition(lidWorld)
        val lidLight = LevelRenderer.getLightColor(level, lightPos.set(lidWorld.x, lidWorld.y, lidWorld.z))

        faceCorners(facing, baseFaceCentreLocal(facing, faceCentre), baseCorners)
        faceCorners(facing, lidFaceCentreLocal(facing, lidWorld, hostShip, be.blockPos, faceCentre), lidCorners)

        val chainVc = buffers.getBuffer(RenderType.entityCutoutNoCull(BLOCK_ATLAS))
        val sprite = Minecraft.getInstance()
            .getTextureAtlas(BLOCK_ATLAS)
            .apply(CHAIN_TEXTURE)
        for (i in 0..3) {
            drawChainSegment(chainVc, pose, baseCorners[i], lidCorners[(i + 2) % 4], sprite, light, lidLight, overlay)
        }
    }


    /** Anchor-plane centre on the base block, set back from the base's far face by
     *  [ANCHOR_INSET] *into* the cube. The chains start here — the inset keeps them from
     *  poking visibly out the back face of the base shell. */
    private fun baseFaceCentreLocal(facing: Direction, out: Vector3d): Vector3d {
        val d = 0.5 - ANCHOR_INSET
        return out.set(
            0.5 - facing.stepX * d,
            0.5 - facing.stepY * d,
            0.5 - facing.stepZ * d,
        )
    }

    /** Lid-side face centre in the BER's local frame (the BE's block-local frame, axes
     *  rotated with the host ship if the strut is on one). The face we want is the lid
     *  block's FACING side — the *far* end of the lid, opposite the joint anchor.
     *
     *  Coord-frame chain: world → host shipyard (or world) → BE
     *  block-local (subtract `basePos`). Missing the final subtract was the OOM crash that
     *  killed the renderer for ship-mounted struts — the lid corners lived ~30M coords away
     *  from the base corners and `drawChainSegment` looped over 30M tile iterations until
     *  the vertex buffer wrapped past 2 GB. */
    private fun lidFaceCentreLocal(
        facing: Direction,
        lidWorld: Vector3dc,
        hostShip: ClientShip?,
        basePos: BlockPos,
        out: Vector3d,
    ): Vector3d {
        out.set(lidWorld)
        hostShip?.renderTransform?.worldToShip?.transformPosition(out)
        out.sub(basePos.x.toDouble(), basePos.y.toDouble(), basePos.z.toDouble())
        // Step along facing to the lid's anchor plane — `0.5 - inset` blocks from the lid
        // centre, so the anchors sit just inside the lid's far face rather than poking
        // through.
        val d = 0.5 - ANCHOR_INSET
        return out.add(facing.stepX * d, facing.stepY * d, facing.stepZ * d)
    }

    /** Four corners of the FACING-perpendicular anchor face centred at [centre], walking
     *  clockwise when viewed from +FACING. The half-extent on the perpendicular axes is
     *  `0.5 - inset` so the corners sit *inside* the block's perimeter on the in-face
     *  axes too, not just on the FACING axis. */
    private fun faceCorners(facing: Direction, centre: Vector3dc, out: Array<Vector3d>) {
        val (a1, a2) = perpAxes(facing)
        val h = 0.5 - ANCHOR_INSET
        for (i in 0..3) {
            val s1 = if (i < 2) h else -h
            val s2 = if (i == 0 || i == 3) h else -h
            out[i].set(
                centre.x() + s1 * a1.x() + s2 * a2.x(),
                centre.y() + s1 * a1.y() + s2 * a2.y(),
                centre.z() + s1 * a1.z() + s2 * a2.z(),
            )
        }
    }

    /** Two unit axes perpendicular to FACING, picked so `a1 × a2 = FACING.normal`
     *  (right-handed), which keeps the corner walk in a consistent rotational direction
     *  for all FACING values. */
    private fun perpAxes(facing: Direction): Pair<Vector3dc, Vector3dc> = when (facing.axis) {
        Direction.Axis.X -> PERP_X
        Direction.Axis.Z -> PERP_Z
        else -> PERP_Y
    }

    /** Draw one chain from [start] to [end]: two perpendicular crossed quads (chain-link
     *  silhouette) tiled by 1.0 along the chain axis. Same geometry as vanilla
     *  `block/chain` / [PlanarAnchorRenderer] — no fade or scroll, just static tiling. */
    private fun drawChainSegment(
        builder: VertexConsumer,
        pose: PoseStack,
        start: Vector3d,
        end: Vector3d,
        sprite: TextureAtlasSprite,
        startLight: Int,
        endLight: Int,
        overlay: Int,
    ) {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-6) return
        // Defensive cap. Max physical extension is MAX_EXTENSION + a few blocks of corner
        // span — anything beyond [MAX_CHAIN_LEN] means a coord-frame mismatch slipped past
        // the earlier transforms; bail out before the tile loop OOMs the vertex buffer.
        if (len > MAX_CHAIN_LEN) return

        pose.pushPose()
        pose.translate(start.x, start.y, start.z)
        // Vanilla chain is built along +Y; rotate +Y to the chain direction.
        val q = chainRotation.rotationTo(
            0f, 1f, 0f,
            (dx / len).toFloat(), (dy / len).toFloat(), (dz / len).toFloat(),
        )
        pose.mulPose(q)

        val matrix = pose.last().pose()
        val normal = pose.last().normal()
        val w = CHAIN_HALF_WIDTH
        val uSpan = sprite.u1 - sprite.u0
        val uA0 = sprite.u0
        val uA1 = sprite.u0 + uSpan * 3f / 16f
        val uB0 = sprite.u0 + uSpan * 3f / 16f
        val uB1 = sprite.u0 + uSpan * 6f / 16f
        val v0 = sprite.v0
        val v1 = sprite.v1

        var y = 0.0
        while (y < len) {
            val yEnd = (y + CHAIN_TILE_LENGTH).coerceAtMost(len)
            // Texture v fraction = the *world-length* covered by this tile, scaled so a
            // full [CHAIN_TILE_LENGTH] tile maps to the full texture height. Without the
            // divide, a shorter tile would only show the bottom fraction of the link
            // silhouette, and the chain would look like a stack of repeated-stubs.
            val frac = ((yEnd - y) / CHAIN_TILE_LENGTH).toFloat()
            val vAtY0 = v1
            val vAtY1 = v1 - (v1 - v0) * frac
            val y0f = y.toFloat()
            val y1f = yEnd.toFloat()
            val light0 = lerpLight(startLight, endLight, y / len)
            val light1 = lerpLight(startLight, endLight, yEnd / len)

            doubleSidedQuad(
                builder, matrix, normal,
                -w, y0f, -w,  w, y0f,  w,
                 w, y1f,  w, -w, y1f, -w,
                uA0, uA1, vAtY0, vAtY1, light0, light1, overlay,
            )
            doubleSidedQuad(
                builder, matrix, normal,
                -w, y0f,  w,  w, y0f, -w,
                 w, y1f, -w, -w, y1f,  w,
                uB0, uB1, vAtY0, vAtY1, light0, light1, overlay,
            )
            y = yEnd
        }
        pose.popPose()
    }

    private fun doubleSidedQuad(
        builder: VertexConsumer,
        matrix: Matrix4f,
        normal: Matrix3f,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        uLeft: Float, uRight: Float,
        vBottom: Float, vTop: Float,
        lightBottom: Int, lightTop: Int, packedOverlay: Int,
    ) {
        v(builder, matrix, normal, x0, y0, z0, uLeft, vBottom, lightBottom, packedOverlay)
        v(builder, matrix, normal, x1, y1, z1, uRight, vBottom, lightBottom, packedOverlay)
        v(builder, matrix, normal, x2, y2, z2, uRight, vTop, lightTop, packedOverlay)
        v(builder, matrix, normal, x3, y3, z3, uLeft, vTop, lightTop, packedOverlay)
        v(builder, matrix, normal, x3, y3, z3, uLeft, vTop, lightTop, packedOverlay)
        v(builder, matrix, normal, x2, y2, z2, uRight, vTop, lightTop, packedOverlay)
        v(builder, matrix, normal, x1, y1, z1, uRight, vBottom, lightBottom, packedOverlay)
        v(builder, matrix, normal, x0, y0, z0, uLeft, vBottom, lightBottom, packedOverlay)
    }

    /** Same per-channel block/sky blend vanilla uses along a leash. */
    private fun lerpLight(from: Int, to: Int, t: Double): Int {
        val block = LightTexture.block(from) + (LightTexture.block(to) - LightTexture.block(from)) * t
        val sky = LightTexture.sky(from) + (LightTexture.sky(to) - LightTexture.sky(from)) * t
        return LightTexture.pack(block.roundToInt(), sky.roundToInt())
    }

    private fun v(
        builder: VertexConsumer,
        matrix: Matrix4f,
        normal: Matrix3f,
        x: Float, y: Float, z: Float,
        u: Float, vCoord: Float,
        packedLight: Int, packedOverlay: Int,
    ) {
        builder.vertex(matrix, x, y, z).color(255, 255, 255, 255)
            .uv(u, vCoord).overlayCoords(packedOverlay).uv2(packedLight)
            .normal(normal, 0f, 0f, 1f).endVertex()
    }

    private companion object {
        /** `null` colour → vanilla shulker default (purple, no `_<colour>` suffix). */
        private val SHULKER_TEXTURE = ResourceLocation("textures/entity/shulker/shulker.png")
        private val SHULKER_RENDER_TYPE: RenderType = RenderType.entityCutoutNoCull(SHULKER_TEXTURE)

        private val COLORED_TYPES: Map<DyeColor, RenderType> = DyeColor.values().associateWith { c ->
            RenderType.entityCutoutNoCull(
                ResourceLocation("textures/entity/shulker/shulker_${c.getName()}.png")
            )
        }

        fun shulkerRenderType(color: DyeColor?): RenderType =
            color?.let { COLORED_TYPES[it] } ?: SHULKER_RENDER_TYPE

        private val CHAIN_TEXTURE = ResourceLocation("enderkinesis", "block/ancrite_chain")

        private val PERP_Y: Pair<Vector3dc, Vector3dc> = Vector3d(1.0, 0.0, 0.0) to Vector3d(0.0, 0.0, 1.0)
        private val PERP_X: Pair<Vector3dc, Vector3dc> = Vector3d(0.0, 1.0, 0.0) to Vector3d(0.0, 0.0, 1.0)
        private val PERP_Z: Pair<Vector3dc, Vector3dc> = Vector3d(1.0, 0.0, 0.0) to Vector3d(0.0, 1.0, 0.0)

        /** Both axes at exactly 1/3 vanilla scale — vanilla is half-width
         *  `1.5 / (16·√2) ≈ 0.0663` with a 1.0-block tile. Scaling both uniformly keeps
         *  texture pixels square (`per-pixel size = 1/3 · (1/16) ≈ 0.0208` blocks on both
         *  axes), so the chain links read naturally instead of stretched/squished. */
        private const val CHAIN_HALF_WIDTH = 0.0221f
        private const val CHAIN_TILE_LENGTH = 1.0 / 3.0

        /** Distance from each face edge that the chain anchor sits inside the block, on
         *  *every* axis (the FACING axis and the two perpendicular in-face axes). 1.6 voxel
         *  pixels — enough that the chain ends never poke visibly out of the shulker shell
         *  faces. */
        private const val ANCHOR_INSET = 0.1

        /** Defensive ceiling for a single chain segment. Real chains can never exceed
         *  `sqrt(MAX_EXTENSION² + 1² + 1²) ≈ 5.2`; we cap at 32 so any coord-frame mismatch
         *  surfaces as missing chains, not as a 30-million-iteration tile loop. */
        private const val MAX_CHAIN_LEN = 32.0
    }
}
