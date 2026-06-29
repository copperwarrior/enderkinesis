package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * Replaces vanilla predicate/override machinery — BEWLR receives [ItemDisplayContext]
 * directly and picks model parts in code, with the page-flop running off
 * `gameTime + partialTick` for frame-rate smoothness (not a discrete step animation).
 *
 * Five sibling JSONs (`wylland_tome_open_{spine,cover1,cover2,page3,page4}.json`) each
 * baked at their canonical open angle around their own hinge, so closing/opening is a
 * delta rotation per part rather than a per-quad reshuffle.
 *
 * Dispatch caveat: vanilla's `ItemRenderer.render` applies the registered model's display
 * transforms and then translates `(-0.5, -0.5, -0.5)` *before* calling [renderByItem], so
 * by the time we run, [poseStack] is already in model-centred render space. The closed and
 * open models share identical `display` blocks — if they diverge, the open render will
 * offset because the closed transform got baked into the pose.
 */
object WyllandTomeBEWLR : BlockEntityWithoutLevelRenderer(
    Minecraft.getInstance().blockEntityRenderDispatcher,
    Minecraft.getInstance().entityModels,
) {

    /** Sub-model resource locations, exposed so the per-platform
     *  client init code can register them with the model loader
     *  and then capture the baked results into the fields below.
     *  - `ICON_MODEL_LOC`: flat 2D `item/generated` icon drawn in
     *    every non-hand context (inventory, ground entity, item
     *    frame, head slot, NONE). Matches what every vanilla item
     *    shows in those slots.
     *  - `SPINE`/`COVER1`/`COVER2`/`PAGE3`/`PAGE4`: the five 3D
     *    parts of the open tome rendered in hand contexts and by
     *    the Cataloger tome-summon flourish. */
    @JvmField val ICON_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_icon")
    @JvmField val SPINE_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_open_spine")
    @JvmField val COVER1_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_open_cover1")
    @JvmField val COVER2_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_open_cover2")
    @JvmField val PAGE3_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_open_page3")
    @JvmField val PAGE4_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_open_page4")

    /** Captured baked models, populated by platform-specific
     *  bake-completion hooks (Fabric: `ModelLoadingPlugin`'s
     *  `modifyModelAfterBake`; Forge: `ModelEvent.ModifyBakingResult`).
     *
     *  Direct lookup through `ModelManager.getModel` is impractical
     *  for our case in 1.20.1: that API only accepts
     *  `ModelResourceLocation` and won't find entries stored under
     *  plain `ResourceLocation` (which is how `addModels`-style
     *  extra models are keyed). Capturing the bake result is
     *  simpler and avoids an accessor mixin into a private field. */
    @Volatile @JvmField var iconModel: BakedModel? = null
    @Volatile @JvmField var spineModel: BakedModel? = null
    @Volatile @JvmField var cover1Model: BakedModel? = null
    @Volatile @JvmField var cover2Model: BakedModel? = null
    @Volatile @JvmField var page3Model: BakedModel? = null
    @Volatile @JvmField var page4Model: BakedModel? = null

    /** Idle (not-grabbing) page-flop period in **real wall-clock
     *  milliseconds**. 2000 ms = 2 s — driven by [Util.getMillis]
     *  so the animation is decoupled from the game loop entirely
     *  (TPS spikes, server stalls, FPS variance all leave the
     *  cadence unchanged). */
    private const val IDLE_PERIOD_MILLIS: Double = 2000.0

    /** Peak swing from canonical, in degrees, for the idle gentle
     *  flop. Page3 (canonical −45°) sweeps to −55°/−35°;
     *  Page4 (canonical +45°) mirrors to +55°/+35°. */
    private const val IDLE_AMPLITUDE_DEG: Float = 10.0f

    /** Active (grabbing) page-riffle period in wall-clock
     *  milliseconds. 250 ms ⇒ ~4 Hz. [RIFFLE_PAGE_COUNT] staggered
     *  copies of page4 share the cycle, each visible only inside
     *  the [COVER_OPEN_ANGLE_DEG]-defined safe arc — see
     *  [renderActiveRiffle]. */
    private const val ACTIVE_PERIOD_MILLIS: Long = 250L

    /** Canonical baked Z rotation of page4 in degrees. Used by the
     *  safe-arc visibility test to convert applied rotation into
     *  visual angle around the spine. */
    private const val PAGE4_CANONICAL_DEG: Float = 45f

    /** Cover open tilt in degrees: each cover sits at ±22.5° around
     *  its hinge, so the inside surface defines the lowest visual
     *  angle a page can reach without clipping into that cover. The
     *  safe page arc is therefore `[COVER_OPEN_ANGLE_DEG, 180° -
     *  COVER_OPEN_ANGLE_DEG]` = `[22.5°, 157.5°]` (135° wide). */
    private const val COVER_OPEN_ANGLE_DEG: Float = 22.5f

    /** Number of staggered page4 copies driven during the active
     *  riffle. Three copies spaced 120° in phase give 135° of
     *  visible arc each minus 120° of stagger = 15° of overlap at
     *  every handoff: always ≥1 copy visible, never zero, no
     *  cover-clip. Two copies (180° stagger) would leave 45° gaps;
     *  four would over-cover and read as a busy crowd of pages. */
    private const val RIFFLE_PAGE_COUNT: Int = 3

    /** Page hinge in BakedModel `[0, 1]` space — `(8, 1.25, 8)/16`. */
    private const val PAGE_PIVOT_X: Float = 0.5f
    private const val PAGE_PIVOT_Y: Float = 1.25f / 16f
    private const val PAGE_PIVOT_Z: Float = 0.5f

    /** Cover1 hinge in BakedModel `[0, 1]` space — `(7.5, 1, 8)/16`. */
    private const val COVER1_PIVOT_X: Float = 7.5f / 16f
    private const val COVER1_PIVOT_Y: Float = 1f / 16f
    private const val COVER1_PIVOT_Z: Float = 0.5f

    /** Cover2 hinge — `(8.5, 1, 8)/16`. */
    private const val COVER2_PIVOT_X: Float = 8.5f / 16f
    private const val COVER2_PIVOT_Y: Float = 1f / 16f
    private const val COVER2_PIVOT_Z: Float = 0.5f

    /** Cover swing from canonical open to fully closed, in degrees.
     *  Covers are baked at ±22.5° (their open angle). Closing rotates
     *  each cover an additional ∓67.5° to reach ±90° — vertical above
     *  the spine, faces touching. */
    private const val COVER_CLOSE_SWING_DEG: Float = 67.5f

    /** Page swing from canonical open to fully closed, in degrees.
     *  Pages baked at ±45°. Closing brings each page to ±90° — flush
     *  inside the closed covers. The page swing is independent of the
     *  cover swing so we don't have to compose two rotations per page. */
    private const val PAGE_CLOSE_SWING_DEG: Float = 45f

    override fun renderByItem(
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        // Custom 3D rendering only in first/third person hand —
        // everything else (GUI, GROUND, FIXED, HEAD, NONE) draws
        // the flat 2D icon, matching the inventory/item-frame look
        // of any vanilla `item/generated` item.
        //
        // The BEWLR is still *dispatched* in every context (Fabric
        // BIRR is keyed on the item, not the context; Forge's
        // `IClientItemExtensions.getCustomRenderer` fires whenever
        // the registered model is `builtin/entity`). We can't
        // sidestep that without per-loader mixin glue, but having
        // the BEWLR draw the same flat icon vanilla would gives
        // an indistinguishable result.
        //
        // wylland_tome.json must also declare `"gui_light": "front"` —
        // vanilla's GUI lighting setup is keyed on the *registered*
        // model's `usesBlockLight()` and runs before this method,
        // so we can't fix the lighting from inside the BEWLR. With
        // `builtin/entity`'s default of `side`, the flat icon gets
        // lit from above by a 3D rig and reads visibly dark.
        if (isHandContext(displayContext)) {
            // Held tome is always fully open — covers at canonical
            // angle, pages doing the idle/grabbing dance.
            renderStaticBody(stack, poseStack, bufferSource, packedLight, packedOverlay)
            if (WyllandTomeClient.isGrabbing()) {
                renderActiveRiffle(stack, poseStack, bufferSource, packedLight, packedOverlay)
            } else {
                val (page3Offset, page4Offset) = currentIdlePageOffsets()
                page3Model?.let { model ->
                    poseStack.pushPose()
                    rotateAroundPagePivot(poseStack, page3Offset)
                    renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay)
                    poseStack.popPose()
                }
                page4Model?.let { model ->
                    poseStack.pushPose()
                    rotateAroundPagePivot(poseStack, page4Offset)
                    renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay)
                    poseStack.popPose()
                }
            }
        } else {
            iconModel?.let {
                renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay)
            }
        }
    }

    /** Spine + both covers at their canonical open angles (covers
     *  not rotated — `[renderOpenWithOpenness]` is the only entry
     *  point that closes them). Used by the held-tome path. */
    private fun renderStaticBody(
        stack: ItemStack, poseStack: PoseStack, bufferSource: MultiBufferSource,
        packedLight: Int, packedOverlay: Int,
    ) {
        spineModel?.let { renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay) }
        cover1Model?.let { renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay) }
        cover2Model?.let { renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay) }
    }

    private fun isHandContext(ctx: ItemDisplayContext): Boolean = when (ctx) {
        ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
        ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
        ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND -> true
        else -> false
    }

    /** Symmetric gentle flop for the idle (not-grabbing) state: a
     *  slow sine wave on the [IDLE_PERIOD_MILLIS] period with
     *  amplitude [IDLE_AMPLITUDE_DEG]. Page3 and Page4 mirror each
     *  other so both pages appear to lift and settle in unison
     *  against the spine. Wall-clock phase keeps cadence
     *  independent of TPS / FPS. Returns `(page3, page4)`. */
    private fun currentIdlePageOffsets(): Pair<Float, Float> {
        val nowMs = Util.getMillis()
        val phase = (nowMs % IDLE_PERIOD_MILLIS.toLong()).toDouble() /
            IDLE_PERIOD_MILLIS * 2.0 * Math.PI
        val offset = (IDLE_AMPLITUDE_DEG * Math.sin(phase)).toFloat()
        return -offset to offset
    }

    /** Rapid right-to-left riffle. [RIFFLE_PAGE_COUNT] copies of
     *  page4 cycle the full 360° around the spine on the
     *  [ACTIVE_PERIOD_MILLIS] period, evenly staggered in phase.
     *  Each copy is rendered only while its visual angle (canonical
     *  baked rotation + applied rotation, mod 360) sits inside the
     *  cover-defined safe arc `[COVER_OPEN_ANGLE_DEG, 180° -
     *  COVER_OPEN_ANGLE_DEG]`. Rotations outside that arc would
     *  push the page either through the back of the book (visual
     *  angle in `(180°, 360°)`) or through one of the tilted-up
     *  covers (the first or last 22.5° of the upper hemisphere).
     *
     *  Coverage is continuous: at every cycle phase at least one
     *  copy sits inside the safe arc. At each handoff a copy
     *  settles against one cover while the next copy lifts off the
     *  opposite cover — reads as continuous page-turning. */
    private fun renderActiveRiffle(
        stack: ItemStack, poseStack: PoseStack, bufferSource: MultiBufferSource,
        packedLight: Int, packedOverlay: Int,
    ) {
        val model = page4Model ?: return
        val nowMs = Util.getMillis()
        val basePhaseDeg = ((nowMs % ACTIVE_PERIOD_MILLIS).toDouble() / ACTIVE_PERIOD_MILLIS * 360.0).toFloat()
        val staggerDeg = 360f / RIFFLE_PAGE_COUNT
        for (i in 0 until RIFFLE_PAGE_COUNT) {
            val rotation = (basePhaseDeg + i * staggerDeg) % 360f
            renderRifflePageIfInSafeArc(model, rotation, stack, poseStack, bufferSource, packedLight, packedOverlay)
        }
    }

    private fun renderRifflePageIfInSafeArc(
        model: BakedModel, rotation: Float,
        stack: ItemStack, poseStack: PoseStack, bufferSource: MultiBufferSource,
        packedLight: Int, packedOverlay: Int,
    ) {
        val visualAngle = ((PAGE4_CANONICAL_DEG + rotation) % 360f + 360f) % 360f
        if (visualAngle < COVER_OPEN_ANGLE_DEG) return
        if (visualAngle > 180f - COVER_OPEN_ANGLE_DEG) return
        poseStack.pushPose()
        rotateAroundPagePivot(poseStack, rotation)
        renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay)
        poseStack.popPose()
    }

    /**
     * Draw the open tome at the held-tome's idle pose — covers at
     * canonical angle, pages at canonical with a gentle sine flop.
     * Convenience wrapper used by the Cataloger summon mid-dwell.
     */
    fun renderOpenIdle(
        stack: ItemStack,
        poseStack: PoseStack, bufferSource: MultiBufferSource,
        packedLight: Int, packedOverlay: Int,
    ) {
        renderOpenWithOpenness(stack, 1f, poseStack, bufferSource, packedLight, packedOverlay)
    }

    /**
     * Draw the tome with a variable [openness] ∈ [0, 1]:
     *  - **`openness = 1`** — covers spread at their canonical
     *    ±22.5° and pages at canonical ±45° (the same look as the
     *    held tome, with the idle page flop).
     *  - **`openness = 0`** — covers swing all the way up to ±90°
     *    against the spine; pages swing inward to ±90°. The book is
     *    closed. Used by the Cataloger summon's outbound/inbound
     *    flight phases (closed book in transit).
     *  - In between: linear interpolation on both covers and pages.
     *    The idle flop is multiplied by `openness` so closed pages
     *    don't shiver.
     *
     * Pages and covers swing independently around their own hinges —
     * no transform compounding, no nested matrix headache. The
     * relative angles are tuned so the two reach the spine at the
     * same `openness` value.
     */
    fun renderOpenWithOpenness(
        stack: ItemStack, openness: Float,
        poseStack: PoseStack, bufferSource: MultiBufferSource,
        packedLight: Int, packedOverlay: Int,
        alpha: Float = 1f,
    ) {
        if (alpha <= 0.001f) return
        val o = openness.coerceIn(0f, 1f)
        val closingFactor = 1f - o
        val cover1Rotation = closingFactor * -COVER_CLOSE_SWING_DEG
        val cover2Rotation = closingFactor * COVER_CLOSE_SWING_DEG
        val idleOffset = currentIdleOffsetDegrees() * o
        val pageClose = closingFactor * PAGE_CLOSE_SWING_DEG
        val page3Rotation = -pageClose - idleOffset
        val page4Rotation = pageClose + idleOffset

        spineModel?.let {
            renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay, alpha)
        }
        cover1Model?.let { model ->
            poseStack.pushPose()
            rotateAroundCover1Pivot(poseStack, cover1Rotation)
            renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay, alpha)
            poseStack.popPose()
        }
        cover2Model?.let { model ->
            poseStack.pushPose()
            rotateAroundCover2Pivot(poseStack, cover2Rotation)
            renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay, alpha)
            poseStack.popPose()
        }
        page3Model?.let { model ->
            poseStack.pushPose()
            rotateAroundPagePivot(poseStack, page3Rotation)
            renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay, alpha)
            poseStack.popPose()
        }
        page4Model?.let { model ->
            poseStack.pushPose()
            rotateAroundPagePivot(poseStack, page4Rotation)
            renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay, alpha)
            poseStack.popPose()
        }
    }

    /** Current idle page-flop offset in degrees, sine-eased on the
     *  [IDLE_PERIOD_MILLIS] period. Page3 takes `-offset`, Page4 takes
     *  `+offset` so they mirror around the spine. */
    private fun currentIdleOffsetDegrees(): Float {
        val nowMs = Util.getMillis()
        val phase = (nowMs % IDLE_PERIOD_MILLIS.toLong()).toDouble() /
            IDLE_PERIOD_MILLIS * 2.0 * Math.PI
        return (IDLE_AMPLITUDE_DEG * Math.sin(phase)).toFloat()
    }

    /** Z rotation around the shared page hinge `(8, 1.25, 8)/16`. */
    private fun rotateAroundPagePivot(poseStack: PoseStack, angleDegrees: Float) =
        rotateAroundZ(poseStack, PAGE_PIVOT_X, PAGE_PIVOT_Y, PAGE_PIVOT_Z, angleDegrees)

    /** Z rotation around the left-cover hinge `(7.5, 1, 8)/16`. */
    private fun rotateAroundCover1Pivot(poseStack: PoseStack, angleDegrees: Float) =
        rotateAroundZ(poseStack, COVER1_PIVOT_X, COVER1_PIVOT_Y, COVER1_PIVOT_Z, angleDegrees)

    /** Z rotation around the right-cover hinge `(8.5, 1, 8)/16`. */
    private fun rotateAroundCover2Pivot(poseStack: PoseStack, angleDegrees: Float) =
        rotateAroundZ(poseStack, COVER2_PIVOT_X, COVER2_PIVOT_Y, COVER2_PIVOT_Z, angleDegrees)

    /** Translate-rotate-untranslate sandwich around `(px, py, pz)`,
     *  Z axis. Caller is expected to push/pop the pose. */
    private fun rotateAroundZ(
        poseStack: PoseStack, px: Float, py: Float, pz: Float, angleDegrees: Float,
    ) {
        poseStack.translate(px, py, pz)
        poseStack.mulPose(Axis.ZP.rotationDegrees(angleDegrees))
        poseStack.translate(-px, -py, -pz)
    }

    /** Emit a baked model's quads through the item-rendering path
     *  without recursing through `ItemRenderer.render` (which
     *  would dispatch right back here because the Wylland Tome's
     *  registered model is flagged as custom-renderer by
     *  Fabric/Forge).
     *
     *  In 1.20.1 BakedModel doesn't expose `getRenderTypes`
     *  (added in 1.20.2). The render type for items rendered
     *  out of the block atlas is resolved via
     *  [ItemBlockRenderTypes.getRenderType], the same path
     *  vanilla `ItemRenderer.render` uses for non-custom
     *  models. We pass `withGlint = stack.hasFoil()` so a tome
     *  that gets enchanted in spite of our anti-enchant
     *  guards still shows its glint correctly.
     *
     *  When [alpha] < 1, the fast vanilla path doesn't work
     *  ([ItemRenderer.renderModelLists] hard-codes alpha 255 inside
     *  `putBulkData`), so we fall through to a manual quad emitter
     *  ([emitModelTranslucent]) targeting [RenderType.entityTranslucentCull]. */
    private fun renderModelPart(
        model: BakedModel,
        stack: ItemStack,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
        alpha: Float = 1f,
    ) {
        // Always route through the manual quad emitter — the obvious alpha==1 fast-path
        // through `ItemRenderer.renderModelLists` doesn't compile against 1.20.1 Mojang
        // mappings (the method is private; Gradle's incremental compile hides the error
        // until a clean build). The emitter is slower but at once-per-frame BER usage the
        // difference is invisible; translucent-at-alpha-1 reads
        // identically to a solid render.
        if (alpha <= 0.001f) return
        emitModelTranslucent(
            model, stack, poseStack, bufferSource, packedLight, packedOverlay, alpha,
        )
    }

    /** Manual translucent vertex emitter. Walks the model's
     *  per-direction + null-face quads (same enumeration vanilla's
     *  [ItemRenderer.renderModelLists] uses), transforms each vertex
     *  by the current pose, and writes through
     *  [RenderType.entityTranslucentCull] with the requested alpha
     *  packed onto the per-vertex colour. The render type's
     *  translucent batch is flushed by the level renderer's
     *  end-of-frame buffer flush so the cataloger summon's quads land
     *  alongside everything else translucent. */
    private fun emitModelTranslucent(
        model: BakedModel,
        stack: ItemStack,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
        alpha: Float,
    ) {
        val random = RandomSource.create()
        val vertexConsumer = bufferSource.getBuffer(
            RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS),
        )
        val pose = poseStack.last()
        val matrix = pose.pose()
        val normalMatrix = pose.normal()

        // The Wylland Tome's baked quads are not tinted — every quad
        // uses the atlas texture's own colour, so we hard-wire RGB
        // to white and only the alpha varies for the fade.
        fun emitQuads(quads: List<net.minecraft.client.renderer.block.model.BakedQuad>) {
            for (quad in quads) {
                emitQuad(
                    vertexConsumer, matrix, normalMatrix, quad,
                    1f, 1f, 1f, alpha, packedLight, packedOverlay,
                )
            }
        }

        for (dir in net.minecraft.core.Direction.values()) {
            random.setSeed(QUAD_RANDOM_SEED)
            emitQuads(model.getQuads(null, dir, random))
        }
        random.setSeed(QUAD_RANDOM_SEED)
        emitQuads(model.getQuads(null, null, random))
    }

    /** Emit one [net.minecraft.client.renderer.block.model.BakedQuad]
     *  through [vc] with the given vertex colour (RGBA). Vertex layout
     *  matches `DefaultVertexFormat.BLOCK`: 8 ints per vertex,
     *  4 vertices per quad. */
    private fun emitQuad(
        vc: VertexConsumer,
        matrix: Matrix4f,
        normalMatrix: Matrix3f,
        quad: net.minecraft.client.renderer.block.model.BakedQuad,
        r: Float, g: Float, b: Float, a: Float,
        packedLight: Int, packedOverlay: Int,
    ) {
        val vertices = quad.vertices
        val faceNormal = quad.direction.normal
        val nx = faceNormal.x.toFloat()
        val ny = faceNormal.y.toFloat()
        val nz = faceNormal.z.toFloat()
        val n = Vector3f(nx, ny, nz)
        n.mul(normalMatrix)
        for (i in 0..3) {
            val o = i * 8
            val x = Float.fromBits(vertices[o])
            val y = Float.fromBits(vertices[o + 1])
            val z = Float.fromBits(vertices[o + 2])
            val u = Float.fromBits(vertices[o + 4])
            val v = Float.fromBits(vertices[o + 5])
            val pos = Vector4f(x, y, z, 1f)
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
