package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemDisplayContext
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.block.OrbOfLinkingBlock
import org.shipwrights.enderkinesis.block.OrbRole
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity

/**
 * Renders three layers on top of the Orb of Linking's static
 * Pedestal-only model (see `orb_pedestal_*.json`):
 *
 *  1. **Held item** — filter ItemStack (set via Tome of Filtering)
 *     or book ItemStack (set on a Disintegration SEND orb), tumbling
 *     at the orb's geometric centre. Drawn FIRST so its
 *     solid/cutout passes write depth before the translucent shell
 *     draws on top — without that ordering the shell's depth-write
 *     occludes the item and it becomes invisible from outside the
 *     orb.
 *  2. **Inner orb shell** — the 8 × 8 × 8 cube the Blockbench source
 *     called "Orb". Drawn via [RenderType.entityTranslucentCull]
 *     using the role's texture so the artist's per-side detail and
 *     per-pixel alpha both come through unchanged — the renderer
 *     supplies vertex alpha 1.0 so the texture's own alpha controls
 *     transparency.
 *  3. **Pulsing haze** — the 10 × 10 × 10 cube the Blockbench source
 *     called "haze". Sine-pulsed vertex alpha, visible only when
 *     `ORB_ROLE != UNBOUND`.
 *
 * All three are rotated by [OrbOfLinkingBlock.FACING] using the same
 * Euler angles the blockstate JSON applies to the Pedestal, so the
 * whole stack tracks the orb's mounted orientation.
 */
class OrbOfLinkingHazeRenderer(
    ctx: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<OrbOfLinkingBlockEntity> {

    private val itemRenderer: ItemRenderer = ctx.itemRenderer

    override fun render(
        be: OrbOfLinkingBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val state = be.blockState
        val role = state.getValue(OrbOfLinkingBlock.ORB_ROLE)
        val level = be.level ?: return

        // One texture per role, sampled for *both* the inner orb shell
        // and (when active) the pulsing haze.
        val textureId = textureFor(role)
        val sprite = Minecraft.getInstance()
            .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(textureId)
        val texSpanU = sprite.u1 - sprite.u0
        val texSpanV = sprite.v1 - sprite.v0

        // Apply the blockstate's facing rotation around block centre.
        val facing = state.getValue(OrbOfLinkingBlock.FACING)
        val (xRotDeg, yRotDeg) = facingRotation(facing)
        ps.pushPose()
        ps.translate(0.5, 0.5, 0.5)
        if (xRotDeg != 0) ps.mulPose(Axis.XP.rotationDegrees(xRotDeg.toFloat()))
        if (yRotDeg != 0) ps.mulPose(Axis.YP.rotationDegrees(yRotDeg.toFloat()))
        ps.translate(-0.5, -0.5, -0.5)

        val matrix = ps.last().pose()
        val normal = ps.last().normal()
        val builder = bufferSource.getBuffer(
            RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)
        )

        // -------------------------------------------------------------
        // Held item — render BEFORE the shell so its solid/cutout
        // passes write depth first. The shell's translucent pass
        // then blends over an already-rendered item. Filter wins
        // over book if both are set (the small orb interior can't
        // hold two items without z-fighting).
        // -------------------------------------------------------------
        val held = when {
            !be.filter.isEmpty -> be.filter
            !be.book.isEmpty -> be.book
            else -> null
        }
        if (held != null) {
            renderHeldItem(
                held, level, be.blockPos.asLong(),
                partialTick, ps, bufferSource, packedLight, packedOverlay,
            )
        }

        // -------------------------------------------------------------
        // Inner orb shell — vertex alpha = 1.0 so the texture's own
        // per-pixel alpha controls transparency. The artist already
        // baked the desired translucency into the texture; multiplying
        // it down further was making the shell read more transparent
        // than the texture calls for.
        // -------------------------------------------------------------
        renderInnerOrb(
            builder, matrix, normal,
            sprite.u0, sprite.v0, texSpanU, texSpanV,
            ORB_ALPHA, packedLight, packedOverlay,
        )

        // -------------------------------------------------------------
        // Pulsing haze — only when the orb is actively linked. Smooth
        // sine modulation between [BASE_ALPHA] and [BASE_ALPHA + PULSE_AMP].
        // -------------------------------------------------------------
        if (role != OrbRole.UNBOUND) {
            val t = level.gameTime + partialTick.toDouble()
            val pulse = 0.5 + 0.5 * Math.sin(t * PULSE_FREQ)
            val hazeAlpha = (BASE_ALPHA + PULSE_AMP * pulse).toFloat().coerceIn(0f, 1f)
            renderHaze(
                builder, matrix, normal,
                sprite.u0, sprite.v0, texSpanU, texSpanV,
                hazeAlpha, packedLight, packedOverlay,
            )
        }

        ps.popPose()
    }

    /** Draw the held filter / book ItemStack at the orb's geometric
     *  centre, slowly tumbling. Called BEFORE the shell so its
     *  solid/cutout passes write depth first — the shell's
     *  translucent pass then blends correctly over an
     *  already-rendered item instead of depth-occluding it. Per-orb
     *  phase seed derived from `blockPos.asLong()` so neighbouring
     *  orbs don't tumble in lock-step. */
    private fun renderHeldItem(
        stack: net.minecraft.world.item.ItemStack,
        level: net.minecraft.world.level.Level,
        posKey: Long,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        ps.pushPose()
        ps.translate(0.5, ORB_CENTRE_Y, 0.5)

        // Wall-clock time, not server `gameTime` + partialTick. The
        // gameTime path is sensitive in two ways: it stutters when the
        // server tick lags (gameTime stops advancing while partialTick
        // still rolls 0→1→0), and once gameTime grows into the millions
        // even the Double-promoted version starts losing precision as
        // it's multiplied through. Wall-clock ms (via [Util.getMillis])
        // advances smoothly every frame regardless of TPS, FPS variance
        // or world age — matches the cadence pattern used elsewhere in
        // this project (e.g. WyllandTomeBEWLR's page flop).
        val tSec = Util.getMillis() / 1000.0
        // Deterministic per-orb 0..1 phase from the block-position
        // hash so adjacent orbs don't synchronise.
        val mixed = (posKey xor (posKey ushr 32)) * 0x9E3779B97F4A7C15UL.toLong()
        val phase = ((mixed ushr 48) and 0xFFFFL).toDouble() / 65536.0
        val phaseDeg = phase * 360.0
        val rateScale = 0.7 + phase * 0.6

        val pitch = (tSec * ITEM_PITCH_DEG_PER_SEC * rateScale + phaseDeg * 1.3) % 360.0
        val yaw = (tSec * ITEM_YAW_DEG_PER_SEC * rateScale + phaseDeg * 2.7) % 360.0
        val roll = (tSec * ITEM_ROLL_DEG_PER_SEC * rateScale + phaseDeg * 0.4) % 360.0
        ps.mulPose(Axis.YP.rotationDegrees(yaw.toFloat()))
        ps.mulPose(Axis.XP.rotationDegrees(pitch.toFloat()))
        ps.mulPose(Axis.ZP.rotationDegrees(roll.toFloat()))

        // Render via FIXED — the item-frame display context. We trust
        // vanilla's FIXED transform to position each item at the centre
        // a frame would display it at; our rotation then spins around
        // (orb_centre). Concretely:
        //
        //  - For models whose raw geometry is centred near (0.5, 0.5,
        //    0.5) (every `item/generated`, `handheld`, `block/block`
        //    parent), FIXED carries a (0,0,0) translation and vanilla's
        //    `translate(-0.5, -0.5, -0.5)` step puts the model centre
        //    exactly at origin — no orbit.
        //
        //  - For asymmetric BEWLR-rendered items (trident, beds,
        //    banners) FIXED's translation IS the model-specific centring
        //    correction the designer wrote into the JSON; the model is
        //    not centred at (0.5, 0.5, 0.5) in raw coords, so FIXED's
        //    translation compensates. There is a small residual offset
        //    we can't dynamically remove without quad-walking the BEWLR
        //    geometry (which `getQuads` doesn't expose) — but that
        //    residual is vanilla's intended in-frame positioning. We do
        //    not try to "correct" it: any blanket counter-translation
        //    removes vanilla's correction and makes asymmetric items
        //    orbit further than they would untouched.
        //
        // Size handling is in [computeFitScale]: measured exactly from
        // quads when possible, estimated from `1 / FIXED_scale` (the
        // "item fits ~1 block in a frame" convention) for BEWLR items.
        val model = itemRenderer.getModel(stack, level, null, 0)
        val fixedT = model.transforms.getTransform(ItemDisplayContext.FIXED)
        val fitScale = computeFitScale(model, fixedT)
        ps.scale(fitScale, fitScale, fitScale)

        itemRenderer.renderStatic(
            stack,
            ItemDisplayContext.FIXED,
            packedLight,
            packedOverlay,
            ps,
            bufferSource,
            level,
            0,
        )
        ps.popPose()
    }

    /** Pick a uniform scale that puts the FIXED-rendered held item at
     *  [TARGET_ITEM_EXTENT] block units across — so a flat icon, a
     *  full block, an elongated tool, and a custom-renderer item like
     *  a trident all visually fit the orb the same way.
     *
     *  Steps: measure the model's raw bounding-box extent from quads
     *  when possible; fall back to `1 / FIXED_scale` for BEWLR-only
     *  items, leveraging vanilla's design that the FIXED-rendered
     *  footprint of any item fits ~1 block (item-frame size). Then
     *  divide [TARGET_ITEM_EXTENT] by `rawExtent × FIXED_scale` to get
     *  the scale that lands the FIXED-rendered item at the target. */
    private fun computeFitScale(
        model: BakedModel,
        fixedT: net.minecraft.client.renderer.block.model.ItemTransform,
    ): Float {
        val fixedScale = maxOf(fixedT.scale.x(), fixedT.scale.y(), fixedT.scale.z())
            .coerceAtLeast(MIN_SCALE)
        // Raw model extent: precise via quads when we can read them,
        // estimated from FIXED's "fits ~1 block in an item frame"
        // convention when the model has a custom renderer (BEWLR —
        // `getQuads` empty).
        val rawExtent = (measureRawExtent(model) ?: (1.0f / fixedScale))
            .coerceAtLeast(MIN_SCALE)
        return TARGET_ITEM_EXTENT / (rawExtent * fixedScale)
    }

    /** Max-axis bounding-box extent of [model] in raw model space
     *  (before any display transform). Walks every face direction's
     *  `getQuads`, including the null/unculled face. BakedQuad vertex
     *  data is laid out as 8 ints per vertex in
     *  [com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK] order:
     *  ints 0..2 are position (as `Float.fromBits`). Returns null when
     *  the model has a custom renderer (BEWLR — `getQuads` empty) so
     *  the caller can substitute a different estimate. */
    private fun measureRawExtent(model: BakedModel): Float? {
        if (model.isCustomRenderer) return null
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var any = false
        for (side in QUAD_SIDES) {
            for (quad in model.getQuads(null, side, extentRandom)) {
                any = true
                val v = quad.vertices
                // 4 vertices per quad, 8 ints per vertex.
                for (i in 0 until 4) {
                    val base = i * 8
                    val x = Float.fromBits(v[base])
                    val y = Float.fromBits(v[base + 1])
                    val z = Float.fromBits(v[base + 2])
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                }
            }
        }
        if (!any) return null
        return maxOf(maxX - minX, maxY - minY, maxZ - minZ)
    }

    /** Shared `RandomSource` for `getQuads` calls during extent
     *  measurement. `getQuads` only consumes the random when emitting
     *  multi-variant or animated quads (e.g. grass tinted variants);
     *  for extent purposes we don't care which variant we get, so a
     *  fixed-seed source is fine and avoids per-call allocation. */
    private val extentRandom: RandomSource = RandomSource.create(0L)

    /** Draw the 6 translucent faces of the inner orb element. Each
     *  face samples a different region of the texture — copied
     *  verbatim from the Blockbench source — so the artist's painted
     *  detail per side is preserved. */
    private fun renderInnerOrb(
        builder: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        u0: Float, v0: Float, spanU: Float, spanV: Float,
        alpha: Float, packedLight: Int, packedOverlay: Int,
    ) {
        // Orb element footprint (from Blockbench source, 16-coord
        // pixels → fractional block).
        val x0 = 4f / 16f; val y0 = 3f / 16f; val z0 = 4f / 16f
        val x1 = 12f / 16f; val y1 = 11f / 16f; val z1 = 12f / 16f

        // Down (-Y) — UV [5, 6, 7, 4]
        quadFace(builder, matrix, normal,
            x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,
            u0 + spanU * 5f / 16f, v0 + spanV * 4f / 16f,
            u0 + spanU * 7f / 16f, v0 + spanV * 6f / 16f,
            0f, -1f, 0f, alpha, packedLight, packedOverlay)
        // Up (+Y) — UV [5, 2, 7, 4]
        quadFace(builder, matrix, normal,
            x0, y1, z1,  x1, y1, z1,  x1, y1, z0,  x0, y1, z0,
            u0 + spanU * 5f / 16f, v0 + spanV * 2f / 16f,
            u0 + spanU * 7f / 16f, v0 + spanV * 4f / 16f,
            0f, 1f, 0f, alpha, packedLight, packedOverlay)
        // North (-Z) — UV [3, 4, 5, 6]
        quadFace(builder, matrix, normal,
            x1, y0, z0,  x0, y0, z0,  x0, y1, z0,  x1, y1, z0,
            u0 + spanU * 3f / 16f, v0 + spanV * 4f / 16f,
            u0 + spanU * 5f / 16f, v0 + spanV * 6f / 16f,
            0f, 0f, -1f, alpha, packedLight, packedOverlay)
        // South (+Z) — UV [3, 0, 5, 2]
        quadFace(builder, matrix, normal,
            x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,
            u0 + spanU * 3f / 16f, v0 + spanV * 0f / 16f,
            u0 + spanU * 5f / 16f, v0 + spanV * 2f / 16f,
            0f, 0f, 1f, alpha, packedLight, packedOverlay)
        // East (+X) — UV [5, 0, 7, 2]
        quadFace(builder, matrix, normal,
            x1, y0, z1,  x1, y0, z0,  x1, y1, z0,  x1, y1, z1,
            u0 + spanU * 5f / 16f, v0 + spanV * 0f / 16f,
            u0 + spanU * 7f / 16f, v0 + spanV * 2f / 16f,
            1f, 0f, 0f, alpha, packedLight, packedOverlay)
        // West (-X) — UV [3, 2, 5, 4]
        quadFace(builder, matrix, normal,
            x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0,
            u0 + spanU * 3f / 16f, v0 + spanV * 2f / 16f,
            u0 + spanU * 5f / 16f, v0 + spanV * 4f / 16f,
            -1f, 0f, 0f, alpha, packedLight, packedOverlay)
    }

    /** Draw the 6 translucent faces of the outer pulsing haze cube.
     *  All faces sample the SAME `(u=7..10.5, v=0..3.5)` region — the
     *  artist mapped a single haze patch and pasted it around the
     *  outside. */
    private fun renderHaze(
        builder: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        u0: Float, v0: Float, spanU: Float, spanV: Float,
        alpha: Float, packedLight: Int, packedOverlay: Int,
    ) {
        val x0 = 3f / 16f; val y0 = 2f / 16f; val z0 = 3f / 16f
        val x1 = 13f / 16f; val y1 = 12f / 16f; val z1 = 13f / 16f
        val uMin = u0 + spanU * HAZE_U_MIN / 16f
        val uMax = u0 + spanU * HAZE_U_MAX / 16f
        val vMin = v0 + spanV * HAZE_V_MIN / 16f
        val vMax = v0 + spanV * HAZE_V_MAX / 16f

        // Down (-Y)
        quadFace(builder, matrix, normal,
            x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1,
            uMin, vMin, uMax, vMax,
            0f, -1f, 0f, alpha, packedLight, packedOverlay)
        // Up (+Y)
        quadFace(builder, matrix, normal,
            x0, y1, z1,  x1, y1, z1,  x1, y1, z0,  x0, y1, z0,
            uMin, vMin, uMax, vMax,
            0f, 1f, 0f, alpha, packedLight, packedOverlay)
        // North (-Z)
        quadFace(builder, matrix, normal,
            x1, y0, z0,  x0, y0, z0,  x0, y1, z0,  x1, y1, z0,
            uMin, vMin, uMax, vMax,
            0f, 0f, -1f, alpha, packedLight, packedOverlay)
        // South (+Z)
        quadFace(builder, matrix, normal,
            x0, y0, z1,  x1, y0, z1,  x1, y1, z1,  x0, y1, z1,
            uMin, vMin, uMax, vMax,
            0f, 0f, 1f, alpha, packedLight, packedOverlay)
        // East (+X)
        quadFace(builder, matrix, normal,
            x1, y0, z1,  x1, y0, z0,  x1, y1, z0,  x1, y1, z1,
            uMin, vMin, uMax, vMax,
            1f, 0f, 0f, alpha, packedLight, packedOverlay)
        // West (-X)
        quadFace(builder, matrix, normal,
            x0, y0, z0,  x0, y0, z1,  x0, y1, z1,  x0, y1, z0,
            uMin, vMin, uMax, vMax,
            -1f, 0f, 0f, alpha, packedLight, packedOverlay)
    }

    private fun textureFor(role: OrbRole): ResourceLocation = when (role) {
        OrbRole.UNBOUND -> UNBOUND_TEXTURE
        OrbRole.SEND -> SEND_TEXTURE
        OrbRole.RECEIVE -> RECEIVE_TEXTURE
    }

    /** Emit one quad. Vertex order: BL → BR → TR → TL (CCW in
     *  screen-space — see the windings explanation above). UV
     *  assignment: `(uMin, vMax)`, `(uMax, vMax)`, `(uMax, vMin)`,
     *  `(uMin, vMin)` — texture's bottom edge tracks the face's
     *  bottom edge so the per-side patterns the artist drew on the
     *  orb come out the right way up. */
    private fun quadFace(
        builder: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        uMin: Float, vMin: Float, uMax: Float, vMax: Float,
        nx: Float, ny: Float, nz: Float,
        alpha: Float, packedLight: Int, packedOverlay: Int,
    ) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        v(builder, matrix, normal, x1, y1, z1, uMin, vMax, a, packedLight, packedOverlay, nx, ny, nz)
        v(builder, matrix, normal, x2, y2, z2, uMax, vMax, a, packedLight, packedOverlay, nx, ny, nz)
        v(builder, matrix, normal, x3, y3, z3, uMax, vMin, a, packedLight, packedOverlay, nx, ny, nz)
        v(builder, matrix, normal, x4, y4, z4, uMin, vMin, a, packedLight, packedOverlay, nx, ny, nz)
    }

    private fun v(
        builder: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        x: Float, y: Float, z: Float,
        u: Float, vCoord: Float,
        a: Int, packedLight: Int, packedOverlay: Int,
        nx: Float, ny: Float, nz: Float,
    ) {
        builder.vertex(matrix, x, y, z)
            .color(255, 255, 255, a)
            .uv(u, vCoord)
            .overlayCoords(packedOverlay)
            .uv2(packedLight)
            .normal(normal, nx, ny, nz)
            .endVertex()
    }

    /** Same `x / y` Euler degrees the blockstate JSON applies to the
     *  static model for each facing — so the haze rotates with it. */
    private fun facingRotation(facing: Direction): Pair<Int, Int> = when (facing) {
        Direction.DOWN -> 0 to 0
        Direction.UP -> 180 to 0
        Direction.NORTH -> 270 to 0
        Direction.SOUTH -> 90 to 0
        Direction.EAST -> 90 to 270
        Direction.WEST -> 90 to 90
    }

    companion object {
        private val UNBOUND_TEXTURE = ResourceLocation("enderkinesis", "block/link_orb")
        private val SEND_TEXTURE = ResourceLocation("enderkinesis", "block/orb_transmitter")
        private val RECEIVE_TEXTURE = ResourceLocation("enderkinesis", "block/orb_reciever")

        /** Vertex alpha multiplier for the inner orb shell. 1.0 leaves
         *  the texture's per-pixel alpha unchanged — the artist
         *  controls the translucency on the texture itself, so any
         *  multiplier below 1.0 would make the shell read more
         *  transparent than the artwork calls for. */
        private const val ORB_ALPHA = 1.0f

        /** Block-local centre of the artist's Orb element along Y
         *  (from `(4, 3, 4) → (12, 11, 12)` Blockbench pixels →
         *  `y_centre = 7/16`). */
        private const val ORB_CENTRE_Y = 7.0 / 16.0
        /** Target rendered extent of the held item, in block units —
         *  what we scale each model's measured extent to fit. 0.3
         *  leaves the item visibly suspended inside the 8-pixel ≈
         *  0.5-block orb interior with room around every corner of the
         *  tumble (a rotated cube's diagonal is √3 × side ≈ 1.73 ×, so
         *  a 0.3-side cube sweeps a 0.52-block envelope at worst —
         *  matched to the shell, no overhang). Applied by
         *  [computeFitScale] after measuring the model. */
        private const val TARGET_ITEM_EXTENT: Float = 0.3f

        /** Floor used to clamp measured extents and display scales
         *  before dividing — guards against a divide-by-zero if a
         *  pathological model reports zero in either. */
        private const val MIN_SCALE: Float = 1e-4f

        /** Face directions to query when measuring extents. The `null`
         *  entry is the "unculled" face — quads that aren't tagged with
         *  any specific direction; nearly every item model emits its
         *  quads here, so it's the most important one to include. */
        private val QUAD_SIDES: Array<Direction?> = arrayOf(
            null, Direction.DOWN, Direction.UP,
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
        )
        /** Tumble rates around each axis (deg/sec, on top of the
         *  per-orb seed phase). Coprime-ish so no two axes ever line
         *  up — the item never looks frozen. */
        private const val ITEM_PITCH_DEG_PER_SEC = 17.0
        private const val ITEM_YAW_DEG_PER_SEC = 29.0
        private const val ITEM_ROLL_DEG_PER_SEC = 11.0

        /** Haze region of every orb texture, in 16-coord UV space. */
        private const val HAZE_U_MIN = 7f
        private const val HAZE_U_MAX = 10.5f
        private const val HAZE_V_MIN = 0f
        private const val HAZE_V_MAX = 3.5f

        /** Pulse trough alpha — the floor of the pulse. The haze
         *  never fades to zero while active so it stays continuously
         *  visible, and the trough is bright enough that the haze
         *  still reads clearly when the pulse dips. */
        private const val BASE_ALPHA = 0.55
        /** Peak-to-trough amplitude on top of [BASE_ALPHA]. Sized to
         *  put the peak exactly at full opacity (`0.55 + 0.45 = 1.0`)
         *  so each pulse swings all the way from half-translucent up
         *  to fully solid before easing back. */
        private const val PULSE_AMP = 0.45
        /** Pulse angular frequency (radians per tick). 0.12 → ~52
         *  ticks per full cycle ≈ 2.6 s, a slow breath. */
        private const val PULSE_FREQ = 0.12
    }
}
