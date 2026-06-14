package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import java.util.UUID
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.entity.CatalogerTomePath
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Renders the Cataloger's tome-summon flourish: a Wylland tome floats
 * out of a Sselith bookshelf, hangs open in front of the cataloger
 * while pages riffle, then floats back.
 *
 * **Three phases**, derived from `elapsed = clientTickCount + partialTick − startTick`:
 *  - **Outbound** (0 .. [Cataloger.TOME_OUTBOUND_TICKS]): closed tome,
 *    position lerps bookshelf → cataloger hold-point along a gentle
 *    parabolic arc, tumbles end-over-end and yaws on a different rate
 *    so the rotation never repeats.
 *  - **Dwell** ([Cataloger.TOME_OUTBOUND_TICKS] .. +[Cataloger.TOME_DWELL_TICKS]):
 *    open tome at the cataloger's hold-point, pages riffle at the same
 *    rapid right-to-left cadence the held tome uses while grabbing
 *    (see [WyllandTomeBEWLR.currentPageRifflePhase]). A small Y bob
 *    keeps it from looking nailed in place.
 *  - **Inbound** (... .. end): mirror of outbound, lerping back.
 *
 * Coordinate handling — the call site is [CatalogerRenderer.render]
 * AFTER `super.render`. At that point the pose stack is at the
 * cataloger's interpolated world position in world-aligned axes
 * (LivingEntityRenderer's local pose is push/popped inside super, so
 * its yaw/scale/translate do NOT leak here). We translate by the
 * tome's world position minus the cataloger's interpolated world
 * position, apply the tumble/dwell rotation, and emit the closed or
 * open baked model through [WyllandTomeBEWLR].
 *
 * Lighting is looked up at the tome's current world block via
 * [LevelRenderer.getLightColor]; a tome floating into a dim shelf
 * dims with it.
 */
object CatalogerTomeRender {

    /** Total yaw and pitch (degrees) the closed tome accumulates over a
     *  single flight phase, applied as an eased cumulative angle —
     *  angular velocity ramps up from 0 at flight start, peaks mid-
     *  flight, and decays back to 0 at flight end. Picked so the two
     *  axes never share a full-rotation alignment: 540° = 1.5 yaw
     *  turns vs 720° = 2 pitch turns. */
    private const val TUMBLE_YAW_TOTAL_DEG = 540f
    private const val TUMBLE_PITCH_TOTAL_DEG = 720f

    /** Uniform scale applied to the floating tome. Wylland Tome's raw
     *  baked size is the BakedModel's 1.0-unit box (the editor's 16-
     *  voxel cube scaled to 1) — full-size reads as a giant book hovering
     *  in mid-air. 0.55 brings it down to a believable hand-held size
     *  while staying legible from a few blocks away. */
    private const val TOME_SCALE = 0.55f

    /** Geometric centre of the Wylland Tome's baked model in [0, 1]
     *  space. The editor model spans roughly x∈[-5, 21] (open covers
     *  fanned), y∈[0, 1.5], z∈[0, 16] (in editor units / 16). Closed
     *  shape sits near (0.5, 0.09, 0.5). Rotating tumbles and
     *  centering both pivot here so the model spins around its own
     *  centre rather than its origin corner. */
    private const val MODEL_CENTER_X = 0.5f
    private const val MODEL_CENTER_Y = 0.09f
    private const val MODEL_CENTER_Z = 0.5f

    /** Period (ticks) of the tiny Z sway during open dwell. Longer
     *  than the bob — sub-Hz so it reads as drift, not flutter. */
    private const val DWELL_SWAY_PERIOD = 110.0
    private const val DWELL_SWAY_AMP_DEG = 2.5f

    /** Share of each flight phase used to blend orientation between
     *  the cumulative tumble and the dwell pose. The last
     *  [TRANSITION_FRACTION] of outbound (and the first
     *  [TRANSITION_FRACTION] of inbound) lerps the rendered orientation
     *  smoothly from "still tumbling" to "fully aligned with cataloger,
     *  ready to read" — no snap when crossing into dwell. 0.35 →
     *  ~50 ticks of transition out of a 140-tick flight, long enough
     *  to read as a deliberate settling. */
    private const val TRANSITION_FRACTION = 0.35f

    /** Ticks at the start (and end) of dwell over which the pages
     *  ramp from closed to open (and back). 35 ≈ 1.75 s — long enough
     *  to read as a deliberate opening flourish, short enough to leave
     *  most of the dwell window with the book fully open. */
    private const val OPEN_RAMP_TICKS = 35f

    /** Additional downward pitch (degrees) applied to the dwell pose
     *  on top of the head's own pitch — tilts the open book toward
     *  the cataloger's eye line so the reader looks down at the page
     *  rather than at a perfectly vertical face. */
    private const val DWELL_PITCH_OFFSET_DEG = 18f

    /** Lone item stack passed to the BEWLR helpers — they need it for
     *  foil/render-type checks. We never expose this stack to the
     *  player, so a default Wylland Tome instance is fine. */
    private val TOME_STACK: ItemStack by lazy { ItemStack(EKItems.WYLLAND_TOME.get()) }

    /** Render the floating tome if [cataloger] currently has an active
     *  summon. Driven from the level-renderer's post-entity event
     *  (see [CatalogerTomeWorldRenderer]) so the book draws even when
     *  the cataloger's own render is frustum-culled — the only thing
     *  required to render is that *some* of the tome's flight arc be
     *  in view. The pose stack is expected to be at the level-renderer
     *  world origin (camera-relative). */
    fun renderForCataloger(
        cataloger: Cataloger,
        partialTicks: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        packedOverlay: Int,
    ) {
        val sourceBookshelf = cataloger.tomeSummonBookshelf ?: return
        val returnBookshelf = cataloger.tomeReturnBookshelf ?: sourceBookshelf
        val startTick = cataloger.clientTomeSummonStartTick
        if (startTick < 0) return
        renderInternal(
            entity = cataloger,
            sourceBookshelf = sourceBookshelf,
            returnBookshelf = returnBookshelf,
            startTick = startTick,
            totalTicks = Cataloger.TOME_TOTAL_TICKS,
            partialTicks = partialTicks,
            poseStack = poseStack,
            bufferSource = bufferSource,
            cameraX = cameraX,
            cameraY = cameraY,
            cameraZ = cameraZ,
            packedOverlay = packedOverlay,
            holdUpdater = { target ->
                cataloger.updateSmoothedTomeHold(target.x, target.y, target.z)
                doubleArrayOf(
                    cataloger.clientSmoothedTomeHoldX,
                    cataloger.clientSmoothedTomeHoldY,
                    cataloger.clientSmoothedTomeHoldZ,
                )
            },
        )
    }

    /** Player-side counterpart to [renderForCataloger]. Pulls the
     *  summon state from [PlayerTomeSummonClient] (broadcast from
     *  server). Smoothing state lives in a private UUID-keyed cache
     *  since `Player` has no `clientSmoothedTomeHold*` fields. */
    fun renderForPlayer(
        player: Player,
        partialTicks: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        packedOverlay: Int,
    ) {
        val state = PlayerTomeSummonClient.get(player) ?: return
        renderInternal(
            entity = player,
            sourceBookshelf = state.sourceBookshelf,
            returnBookshelf = state.returnBookshelf,
            startTick = state.startTick,
            totalTicks = state.totalTicks,
            partialTicks = partialTicks,
            poseStack = poseStack,
            bufferSource = bufferSource,
            cameraX = cameraX,
            cameraY = cameraY,
            cameraZ = cameraZ,
            packedOverlay = packedOverlay,
            holdUpdater = { target ->
                updatePlayerSmoothedHold(player.uuid, target.x, target.y, target.z, player.tickCount)
            },
        )
    }

    /** UUID-keyed smoothed hold cache for players. Same exponential
     *  ease as the cataloger's per-entity fields, just stored
     *  externally because we can't add fields to `Player`. */
    private val playerSmoothedHold = HashMap<UUID, DoubleArray>()
    private val playerSmoothedHoldTick = HashMap<UUID, Int>()

    private fun updatePlayerSmoothedHold(
        uuid: UUID, targetX: Double, targetY: Double, targetZ: Double, currentTick: Int,
    ): DoubleArray {
        val existing = playerSmoothedHold[uuid]
        val lastTick = playerSmoothedHoldTick[uuid] ?: -1
        val deltaT = currentTick - lastTick
        playerSmoothedHoldTick[uuid] = currentTick
        if (existing == null || deltaT < 0 || deltaT > 50) {
            val fresh = doubleArrayOf(targetX, targetY, targetZ)
            playerSmoothedHold[uuid] = fresh
            return fresh
        }
        if (deltaT > 0) {
            val keep = Math.pow(1.0 - HOLD_EASE_PER_TICK, deltaT.toDouble())
            existing[0] = targetX + (existing[0] - targetX) * keep
            existing[1] = targetY + (existing[1] - targetY) * keep
            existing[2] = targetZ + (existing[2] - targetZ) * keep
        }
        return existing
    }

    private const val HOLD_EASE_PER_TICK: Double = 0.18

    /** Generic render routine shared by [renderForCataloger] and
     *  [renderForPlayer]. Takes everything it needs as parameters; the
     *  only entity-specific bit is [holdUpdater], which applies the
     *  exponential ease and returns the smoothed `(x, y, z)` hold-point. */
    private fun renderInternal(
        entity: LivingEntity,
        sourceBookshelf: BlockPos,
        returnBookshelf: BlockPos,
        startTick: Int,
        totalTicks: Int,
        partialTicks: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        packedOverlay: Int,
        holdUpdater: (target: net.minecraft.world.phys.Vec3) -> DoubleArray,
    ) {
        val elapsed = (entity.tickCount - startTick).toFloat() + partialTicks
        if (elapsed < 0f || elapsed > totalTicks.toFloat()) return

        val level = entity.level()

        // Entity's interpolated world position — anchor for the hold-
        // point math, NOT the pose-stack origin (pose stack is at the
        // level-renderer's world origin in camera-relative coords; we
        // translate to the book's world position below).
        val ex = Mth.lerp(partialTicks.toDouble(), entity.xOld, entity.x)
        val ey = Mth.lerp(partialTicks.toDouble(), entity.yOld, entity.y)
        val ez = Mth.lerp(partialTicks.toDouble(), entity.zOld, entity.z)

        val headYawDeg = Mth.lerp(partialTicks, entity.yHeadRotO, entity.yHeadRot)
        val headPitchDeg = Mth.lerp(partialTicks, entity.xRotO, entity.xRot)
        val eyeY = ey + entity.eyeHeight
        val (targetHoldX, targetHoldY, targetHoldZ) = CatalogerTomePath.holdPoint(
            ex, eyeY, ez, headYawDeg, headPitchDeg,
        )
        val smoothed = holdUpdater(net.minecraft.world.phys.Vec3(targetHoldX, targetHoldY, targetHoldZ))
        val holdX = smoothed[0]
        val holdY = smoothed[1]
        val holdZ = smoothed[2]
        // The downstream Cataloger summon code expects to read the
        // phase / position math from the entity's TICK COUNT — we
        // already supplied that via `entity.tickCount`. The TOME_* phase
        // splits below are read from constants on Cataloger directly so
        // both summons share the same out / dwell / in proportions
        // (140 / 240 / 140 ticks at the time of writing).

        val phase = CatalogerTomePath.phaseOf(elapsed)
        val open = phase == CatalogerTomePath.Phase.DWELL
        val phaseProgress = CatalogerTomePath.progressInPhase(phase, elapsed)

        // Path point — Bezier curve tangent to the shelf face at the
        // shelf endpoint, smoothly curving to the hold-point. Outbound
        // leaves the source shelf; inbound heads for the return shelf.
        // Dwell hovers at the hold-point.
        val (px, py, pz) = CatalogerTomePath.computePosition(
            phase, phaseProgress,
            sourceBookshelf.x + 0.5, sourceBookshelf.y + 0.5, sourceBookshelf.z + 0.5,
            returnBookshelf.x + 0.5, returnBookshelf.y + 0.5, returnBookshelf.z + 0.5,
            holdX, holdY, holdZ,
            ex, eyeY, ez,
        )

        // Light at the tome's block.
        val tomeBlockPos = BlockPos(
            Math.floor(px).toInt(),
            Math.floor(py).toInt(),
            Math.floor(pz).toInt(),
        )
        val packedLight = LevelRenderer.getLightColor(level, tomeBlockPos)

        poseStack.pushPose()
        // From level-renderer's world origin (camera-relative) → tome
        // world position (camera-relative).
        poseStack.translate(px - cameraX, py - cameraY, pz - cameraZ)
        // Uniform scale, then rotation. Apply scale BEFORE the
        // pivot/rotate sequence so the pivot offsets read in the
        // scaled local frame.
        poseStack.scale(TOME_SCALE, TOME_SCALE, TOME_SCALE)
        // Centre the model on its geometric centre rather than its
        // baked-origin corner. The book's vertices span roughly
        // (0, 0, 0)..(1, 0.3, 1) in baked space — without this
        // pre-translate, the corner sits at the hold-point and the
        // visible book is offset by `+MODEL_CENTER × scale` from where
        // the gaze-vector says it should be (visibly to the cataloger's
        // left at yaw 0, rotated equivalently at other yaws).
        poseStack.translate(-MODEL_CENTER_X, -MODEL_CENTER_Y, -MODEL_CENTER_Z)
        // Always render the open-tome mesh — flight phases just close
        // its pages (openness=0). The dwell ramp opens them in the
        // first ~35 ticks and closes them in the last ~35 ticks, so the
        // book visibly opens on arrival and visibly closes before
        // departure.
        val openness = if (open) dwellOpenness(phaseProgress) else 0f
        // Alpha fade matches the shelf-orientation blend: book is
        // invisible while its centre is inside the shelf's AABB, eases
        // to fully opaque over [BOOKSHELF_FADE_DISTANCE]. Looks like
        // the book is materialising out of the shelf on exit, and
        // dissolving into it on entry.
        // Bookshelf-aligned reference pose: outbound uses the source
        // shelf, inbound uses the return shelf. The book inserts/
        // emerges perpendicular to the matching shelf face.
        val bookshelfForOrientation =
            if (phase == CatalogerTomePath.Phase.IN) returnBookshelf else sourceBookshelf
        val (bookshelfYawDeg, bookshelfPitchDeg) = CatalogerTomePath.bookshelfFaceAngles(
            bookshelfForOrientation.x + 0.5,
            bookshelfForOrientation.y + 0.5,
            bookshelfForOrientation.z + 0.5,
            ex, eyeY, ez,
        )
        // Distance-based bookshelf blend: stays at 1 while the tome's
        // centre is inside the shelf's block AABB, then fades to 0
        // over [BOOKSHELF_FADE_DISTANCE] blocks. The book holds its
        // shelf-aligned pose all the way through the shelf face, then
        // starts tumbling once clear — and the mirror on inbound, so
        // tumble decays into shelf alignment before the centre enters
        // the return shelf's space.
        val distFromShelf = CatalogerTomePath.distanceToBlockAABB(
            px, py, pz,
            bookshelfForOrientation.x, bookshelfForOrientation.y, bookshelfForOrientation.z,
        )
        val bookshelfBlend = bookshelfBlendByDistance(distFromShelf, phase)
        applyTomeOrientation(
            poseStack, phase, phaseProgress, elapsed,
            headYawDeg, headPitchDeg, bookshelfYawDeg, bookshelfPitchDeg, bookshelfBlend,
        )
        // Alpha mirrors the shelf-orientation blend: book is invisible
        // while its centre is inside the AABB (blend = 1 → alpha = 0)
        // and eases to fully opaque over the same fade window. Reads
        // as the book materialising out of the shelf face on exit and
        // dissolving back into it on entry.
        val alpha = 1f - bookshelfBlend
        WyllandTomeBEWLR.renderOpenWithOpenness(
            TOME_STACK, openness, poseStack, bufferSource, packedLight, packedOverlay, alpha,
        )
        poseStack.popPose()
    }

    /** Opens-then-closes shape across the dwell window. Ramps from 0 → 1
     *  with [CatalogerTomePath.easeInOut] over the first [OPEN_RAMP_TICKS],
     *  holds at 1 through the middle, then 1 → 0 over the last
     *  [OPEN_RAMP_TICKS] so the inbound flight begins with the pages
     *  already closed. */
    private fun dwellOpenness(dwellProgress: Float): Float {
        val dwellTicks = Cataloger.TOME_DWELL_TICKS.toFloat()
        val tickElapsed = dwellProgress * dwellTicks
        return when {
            tickElapsed < OPEN_RAMP_TICKS ->
                CatalogerTomePath.easeInOut(tickElapsed / OPEN_RAMP_TICKS)
            tickElapsed > dwellTicks - OPEN_RAMP_TICKS ->
                CatalogerTomePath.easeInOut((dwellTicks - tickElapsed) / OPEN_RAMP_TICKS)
            else -> 1f
        }
    }

    /** Unified orientation that smoothly blends two reference poses:
     *
     *   - **Tumble pose** — `Y(tumbleYaw) · X(tumblePitch) · Z(0) ·
     *     X(0)` with cumulative angles `easeInOut(t) · TUMBLE_*_TOTAL`.
     *     Smoothstep gives zero angular velocity at both endpoints of
     *     each flight phase, so the tumble winds down cleanly on
     *     arrival.
     *   - **Dwell pose** — `Y(-headYaw) · X(headPitch) · Z(180°) ·
     *     X(-90°)`. The full chain takes the baked book (pages-up,
     *     page 1 on its −X side, spine along Z) and lands it as: book
     *     held vertical in front of the cataloger's face along the
     *     head's gaze direction, pages facing the cataloger's eyes
     *     (yaw AND pitch — book tilts to match head pitch), spine
     *     running down the middle, page 1 on the cataloger's LEFT and
     *     page 2 on the RIGHT.
     *
     *  Both poses share the same four-axis decomposition (outer Y,
     *  outer X, Z, inner X) so each axis can be lerped independently
     *  via [lerpAngleDeg]. Blend factor is 0 during pure-tumble (most
     *  of OUT/IN), 1 during dwell, and lerps via [easeInOut] over the
     *  last [TRANSITION_FRACTION] of OUT and the first
     *  [TRANSITION_FRACTION] of IN. The shortest-arc lerp avoids 270°
     *  backwards sweeps when the tumble's accumulated yaw is on the
     *  far side of the dwell yaw. Dwell sway adds on top of the roll
     *  and is multiplied by the blend factor so it only appears once
     *  the dwell pose is dominant. */
    private fun applyTomeOrientation(
        poseStack: PoseStack,
        phase: CatalogerTomePath.Phase,
        phaseProgress: Float,
        elapsed: Float,
        headYawDeg: Float,
        headPitchDeg: Float,
        bookshelfYawDeg: Float,
        bookshelfPitchDeg: Float,
        bookshelfBlend: Float,
    ) {
        val tumbleProgress = when (phase) {
            CatalogerTomePath.Phase.OUT, CatalogerTomePath.Phase.IN ->
                CatalogerTomePath.easeInOut(phaseProgress)
            CatalogerTomePath.Phase.DWELL -> 0f
        }
        val tumbleOuterY = tumbleProgress * TUMBLE_YAW_TOTAL_DEG
        val tumbleOuterX = tumbleProgress * TUMBLE_PITCH_TOTAL_DEG
        val tumbleZ = 0f
        val tumbleInnerX = 0f

        // Dwell reference — book held vertical in front of head.
        val dwellBlend = dwellBlendOf(phase, phaseProgress)
        val dwellOuterY = -headYawDeg
        val dwellOuterX = headPitchDeg + DWELL_PITCH_OFFSET_DEG
        val dwellZ = 180f
        val dwellInnerX = -90f

        // Bookshelf reference — pages-up aligned INTO the shelf face,
        // spine facing the cataloger. Like a real book sitting in a
        // slot. The blend is distance-based (see caller) so we hold
        // this pose all the way through the AABB.
        val bookshelfOuterY = -bookshelfYawDeg
        val bookshelfOuterX = bookshelfPitchDeg
        val bookshelfZ = 180f
        val bookshelfInnerX = -90f

        // Pick the active non-tumble reference. The two blend windows
        // don't overlap (bookshelf is the early-OUT and late-IN tails;
        // dwell is the late-OUT and early-IN tails plus all of dwell),
        // so the active blend is a simple sum and the active reference
        // is whichever has non-zero weight.
        val refOuterY: Float
        val refOuterX: Float
        val refZ: Float
        val refInnerX: Float
        if (bookshelfBlend > 0f) {
            refOuterY = bookshelfOuterY
            refOuterX = bookshelfOuterX
            refZ = bookshelfZ
            refInnerX = bookshelfInnerX
        } else {
            refOuterY = dwellOuterY
            refOuterX = dwellOuterX
            refZ = dwellZ
            refInnerX = dwellInnerX
        }
        val transitionBlend = bookshelfBlend + dwellBlend

        val finalOuterY = lerpAngleDeg(transitionBlend, tumbleOuterY, refOuterY)
        val finalOuterX = lerpAngleDeg(transitionBlend, tumbleOuterX, refOuterX)
        val swayDeg = Math.sin(elapsed * 2.0 * Math.PI / DWELL_SWAY_PERIOD).toFloat() *
            DWELL_SWAY_AMP_DEG * dwellBlend
        val finalZ = lerpAngleDeg(transitionBlend, tumbleZ, refZ) + swayDeg
        val finalInnerX = lerpAngleDeg(transitionBlend, tumbleInnerX, refInnerX)

        poseStack.translate(MODEL_CENTER_X, MODEL_CENTER_Y, MODEL_CENTER_Z)
        poseStack.mulPose(Axis.YP.rotationDegrees(finalOuterY))
        poseStack.mulPose(Axis.XP.rotationDegrees(finalOuterX))
        poseStack.mulPose(Axis.ZP.rotationDegrees(finalZ))
        poseStack.mulPose(Axis.XP.rotationDegrees(finalInnerX))
        poseStack.translate(-MODEL_CENTER_X, -MODEL_CENTER_Y, -MODEL_CENTER_Z)
    }

    /** Bookshelf-reference blend keyed on the tome's distance to the
     *  shelf's block AABB (not phase progress): `1` while the centre
     *  is inside the shelf, eases to `0` over [BOOKSHELF_FADE_DISTANCE]
     *  blocks of clearance. This means the shelf-aligned pose holds
     *  for as long as the book is physically inside the shelf — so
     *  the book exits straight through the face and only starts
     *  tumbling once clear, and inbound the inverse: tumble decays
     *  into alignment as the book approaches and crosses the face.
     *
     *  Suppressed during dwell so a hold-point that happens to drift
     *  near a stray shelf (rare, but possible) doesn't re-align the
     *  open book at the cataloger's face. */
    private fun bookshelfBlendByDistance(
        distFromAABB: Float, phase: CatalogerTomePath.Phase,
    ): Float {
        if (phase == CatalogerTomePath.Phase.DWELL) return 0f
        if (distFromAABB <= 0f) return 1f
        if (distFromAABB >= BOOKSHELF_FADE_DISTANCE) return 0f
        return CatalogerTomePath.easeInOut(1f - distFromAABB / BOOKSHELF_FADE_DISTANCE)
    }

    /** Distance (blocks) past the shelf face over which the
     *  bookshelf-aligned pose fades out. Kept in sync with
     *  [CatalogerTomePath.BOOKSHELF_FADE_DISTANCE] — the position
     *  Bezier and the orientation blend use the same range so the
     *  book holds its shelf-aligned pose for exactly as long as the
     *  Bezier is moving in the shelf-perpendicular direction. */
    private const val BOOKSHELF_FADE_DISTANCE: Float =
        CatalogerTomePath.BOOKSHELF_FADE_DISTANCE.toFloat()

    /** How much of the dwell pose to mix into the rendered orientation
     *  at the current frame. 1 throughout dwell; ramps up over the last
     *  [TRANSITION_FRACTION] of OUT and down over the first
     *  [TRANSITION_FRACTION] of IN. */
    private fun dwellBlendOf(phase: CatalogerTomePath.Phase, phaseProgress: Float): Float = when (phase) {
        CatalogerTomePath.Phase.OUT -> {
            val start = 1f - TRANSITION_FRACTION
            if (phaseProgress > start) {
                CatalogerTomePath.easeInOut((phaseProgress - start) / TRANSITION_FRACTION)
            } else 0f
        }
        CatalogerTomePath.Phase.IN -> {
            if (phaseProgress < TRANSITION_FRACTION) {
                CatalogerTomePath.easeInOut(1f - phaseProgress / TRANSITION_FRACTION)
            } else 0f
        }
        CatalogerTomePath.Phase.DWELL -> 1f
    }

    /** Shortest-path angle lerp. `lerp(t, a, b)` returns an angle
     *  between `a` and `b` along the shorter arc (≤ 180°) — without
     *  this, lerping from 423° to 180° would sweep 243° clockwise
     *  instead of 117° counter-clockwise, and the book would visibly
     *  un-rotate during the transition. */
    private fun lerpAngleDeg(t: Float, a: Float, b: Float): Float {
        var diff = ((b - a) % 360f + 360f) % 360f
        if (diff > 180f) diff -= 360f
        return a + diff * t
    }
}
