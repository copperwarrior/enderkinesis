package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * Custom item renderer for the Wylland Tome. Replaces vanilla's
 * predicate/override machinery entirely — no more `held` or
 * `page_wave` ItemProperty, no more keyframe override files. The
 * BEWLR receives [ItemDisplayContext] as a parameter and chooses
 * model parts itself; the page-flop animation runs against
 * `gameTime + partialTick` so it's a smooth eased sine wave at
 * the frame rate, not a discrete step animation.
 *
 * **Three model parts.** The open tome is split into three sibling
 * JSONs so the page-flop can rotate page3 and page4 independently
 * while the rest of the book stays fixed:
 *  - `wylland_tome_open_static.json` — spine, both covers, page1,
 *    page2 (5 elements).
 *  - `wylland_tome_open_page3.json` — just the Page3 element, with
 *    its canonical −45° z-rotation around `(8, 1.25, 8)` baked in.
 *  - `wylland_tome_open_page4.json` — just Page4 with canonical
 *    +45° z-rotation around the same pivot.
 *
 * The closed `wylland_tome.json` model is unchanged.
 *
 * **Dispatch.** Vanilla's `ItemRenderer.render` applies the
 * registered model's display transforms and then translates
 * `(-0.5, -0.5, -0.5)` *before* dispatching to a BEWLR. So when
 * [renderByItem] runs, [poseStack] is already in the
 * model-centred render space; we just emit each part's quads
 * (with the page rotations layered on for the two animated
 * parts) and the matrix stack does the rest. The closed and open
 * models share identical `display` blocks in the artist's source —
 * if those ever diverge, the open rendering will visually offset
 * because the closed model's transform got baked into the pose
 * by the time we receive control.
 *
 * **Animation.** Cycle period [PERIOD_TICKS] ticks (~2 s). The
 * angle offset is a continuous sine wave with amplitude
 * [ANGLE_AMPLITUDE_DEG]: page3 swings between −35° and −55°,
 * page4 mirrors between +55° and +35°. Pivot in the baked coord
 * space is `(0.5, 1.25/16, 0.5)` — the model's `(8, 1.25, 8)`
 * pivot divided by 16 because BakedModel quads are in [0, 1]
 * scaled from the editor's [0, 16].
 */
object WyllandTomeBEWLR : BlockEntityWithoutLevelRenderer(
    Minecraft.getInstance().blockEntityRenderDispatcher,
    Minecraft.getInstance().entityModels,
) {

    /** Sub-model resource locations, exposed so the per-platform
     *  client init code can register them with the model loader
     *  and then capture the baked results into the fields below.
     *  - `ICON_MODEL_LOC`: flat 2D `item/generated` icon used for
     *    every NON-hand context (inventory, hotbar, ground entity,
     *    GUI, item frame, fixed).
     *  - `STATIC_MODEL_LOC` + `PAGE3_MODEL_LOC` + `PAGE4_MODEL_LOC`:
     *    composing the 3D open tome rendered in hand contexts.
     *  - `CLOSED_MODEL_LOC`: legacy 3D closed model. No longer
     *    rendered now that the icon replaces it everywhere
     *    non-hand, but kept registered + captured for now in case
     *    we want to fall back. */
    @JvmField val ICON_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_icon")
    @JvmField val CLOSED_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_closed")
    @JvmField val STATIC_MODEL_LOC = ResourceLocation("enderkinesis", "item/wylland_tome_open_static")
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
    @Volatile @JvmField var closedModel: BakedModel? = null
    @Volatile @JvmField var staticModel: BakedModel? = null
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
     *  milliseconds. 250 ms ⇒ ~4 Hz, "rapidly flipping" rather
     *  than gentle wobble. Page4 completes one full 360° rotation
     *  around the spine each period; the visible motion sweeps the
     *  page top from canonical right-side-up over the spine to the
     *  left and back, reading as a right-to-left page turn. */
    private const val ACTIVE_PERIOD_MILLIS: Long = 250L

    /** Pivot in the BakedModel's [0, 1] coord space, lifted from
     *  the artist's `(8, 1.25, 8)` pivot for Page3/Page4 rotations. */
    private const val PIVOT_X: Float = 0.5f
    private const val PIVOT_Y: Float = 1.25f / 16f
    private const val PIVOT_Z: Float = 0.5f

    override fun renderByItem(
        stack: ItemStack,
        displayContext: ItemDisplayContext,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        when {
            // GUI = inventory slots, hotbar slots, recipe outputs.
            // Use the flat texture-only icon — what every vanilla
            // item shows in those contexts. The wylland_tome.json's
            // `gui` display transform is a flat-icon transform
            // (zero rotation, scale 1) so the icon quad renders as
            // a clean square in the slot.
            displayContext == ItemDisplayContext.GUI -> {
                iconModel?.let {
                    renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay)
                }
            }
            // GROUND (dropped item entity), FIXED (item frame), HEAD
            // (worn in the head slot) — the closed 3D book model.
            // These contexts read as a real object in the world, so
            // the silhouette should be a closed book, not a flat
            // sprite. wylland_tome.json keeps its 3D-book-tuned
            // display transforms for these contexts.
            displayContext == ItemDisplayContext.GROUND ||
            displayContext == ItemDisplayContext.FIXED ||
            displayContext == ItemDisplayContext.HEAD -> {
                closedModel?.let {
                    renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay)
                }
            }
            // First/third person hand contexts — open animated tome.
            isHandContext(displayContext) -> {
                staticModel?.let {
                    renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay)
                }
                val grabbing = WyllandTomeClient.isGrabbing()
                val (page3Offset, page4Offset) = currentPageOffsets(grabbing)
                if (!grabbing) {
                    page3Model?.let { model ->
                        poseStack.pushPose()
                        rotateAroundPivot(poseStack, page3Offset)
                        renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay)
                        poseStack.popPose()
                    }
                }
                page4Model?.let { model ->
                    poseStack.pushPose()
                    rotateAroundPivot(poseStack, page4Offset)
                    renderModelPart(model, stack, poseStack, bufferSource, packedLight, packedOverlay)
                    poseStack.popPose()
                }
            }
            // NONE / fallback — flat icon. NONE is the "no specific
            // display context" path used by mods that render items
            // out-of-band; rendering the flat icon there matches
            // vanilla behaviour for `item/generated` items.
            else -> {
                iconModel?.let {
                    renderModelPart(it, stack, poseStack, bufferSource, packedLight, packedOverlay)
                }
            }
        }
    }

    private fun isHandContext(ctx: ItemDisplayContext): Boolean = when (ctx) {
        ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
        ItemDisplayContext.FIRST_PERSON_RIGHT_HAND,
        ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
        ItemDisplayContext.THIRD_PERSON_RIGHT_HAND -> true
        else -> false
    }

    /** Per-page rotation offsets in degrees for the current
     *  frame. Returns `(page3, page4)`. Branches on whether the
     *  local-client grab is active:
     *
     *   - **Active (grabbing)** — Page4 cycles through a full
     *     360° CCW rotation around the spine every
     *     [ACTIVE_PERIOD_MILLIS]. The top of the page sweeps from
     *     its canonical right-side-up position over the spine to
     *     the left and back — reads as rapid right-to-left page
     *     turning, in the direction a reader riffles through a
     *     book backwards. Page3 is NOT rendered in this mode
     *     (caller skips it) — the page3 entry in the returned
     *     tuple is left at 0 as a no-op and only page4's value
     *     matters.
     *
     *   - **Idle** — Symmetric gentle flop: a slow sine wave
     *     (period [IDLE_PERIOD_MILLIS], amplitude
     *     [IDLE_AMPLITUDE_DEG]). Page3 and Page4 mirror each
     *     other so both pages appear to lift and settle in unison
     *     against the spine.
     *
     *  Both modes use [Util.getMillis] for wall-clock-based phase
     *  so the cadence is independent of TPS or FPS. */
    private fun currentPageOffsets(grabbing: Boolean): Pair<Float, Float> {
        val nowMs = Util.getMillis()
        if (grabbing) {
            val phase = ((nowMs % ACTIVE_PERIOD_MILLIS).toDouble() / ACTIVE_PERIOD_MILLIS * 360.0).toFloat()
            return 0.0f to phase
        }
        val phase = (nowMs % IDLE_PERIOD_MILLIS.toLong()).toDouble() /
            IDLE_PERIOD_MILLIS * 2.0 * Math.PI
        val offset = (IDLE_AMPLITUDE_DEG * Math.sin(phase)).toFloat()
        return -offset to offset
    }

    /** Apply a Z-axis rotation around the shared page pivot to
     *  the pose stack. Caller is expected to push/pop around this. */
    private fun rotateAroundPivot(poseStack: PoseStack, angleDegrees: Float) {
        poseStack.translate(PIVOT_X, PIVOT_Y, PIVOT_Z)
        poseStack.mulPose(Axis.ZP.rotationDegrees(angleDegrees))
        poseStack.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z)
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
     *  guards still shows its glint correctly. */
    private fun renderModelPart(
        model: BakedModel,
        stack: ItemStack,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val renderType = ItemBlockRenderTypes.getRenderType(stack, false)
        val vertexConsumer = ItemRenderer.getFoilBuffer(
            bufferSource, renderType, true, stack.hasFoil(),
        )
        Minecraft.getInstance().itemRenderer.renderModelLists(
            model, stack, packedLight, packedOverlay, poseStack, vertexConsumer,
        )
    }
}
