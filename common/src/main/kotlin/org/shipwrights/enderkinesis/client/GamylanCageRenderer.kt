package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.valkyrienskies.mod.common.shipObjectWorld

/** Called from the LevelRenderer mixin once per frame after the
 *  translucent entity pass. Iterates every cage in the player's
 *  current dimension and emits, per cage:
 *
 *   1. The frozen captured entity at an interpolated position —
 *      lerped from the cage's [captureOrigin] up to its [hoverPos] over
 *      [FADE_TICKS]. This is the "levitate up" portion of the spec:
 *      the captured body rises into the crystal as the shell fades in.
 *   2. The hex double-terminated crystal at the cage's hover position,
 *      rotating around the vertical (termination) axis at
 *      [ROT_RAD_PER_TICK]. Drawn in
 *      [AegisRenderType.FACE_GLOW] face-gradient + line-outline style
 *      using the same trapezoidal-strip pattern as the Aegis box.
 *
 *  Both layers share a single fade-in envelope so the crystal appears
 *  in step with the captured entity rising.
 */
object GamylanCageRenderer {

    /** Total fade-in window. 30 ticks ≈ 1.5 s. Crystal alpha + entity
     *  rise interpolate over this window starting at the cage's
     *  `addedAtGameTime`. */
    private const val FADE_TICKS: Double = 30.0

    /** Number of full Y-axis revolutions the crystal completes during
     *  the fade-in. The end angle is `N · 2π`, which is `0` modulo the
     *  hex's 6-fold symmetry — so the visual mesh ends exactly aligned
     *  with the (un-spun) collision compound. Choose 1: at 30 ticks /
     *  1.5 s that's 240°/s peak (smoothstep eases in and out), which
     *  reads as a brisk crystallization spin. */
    private const val SPIN_REVOLUTIONS: Double = 1.0

    /** Gentle idle-spin angular speed (radians per tick) applied while
     *  the crystal is sitting on a Crystal Pedestal. `~0.015 rad/tick`
     *  at 20 tps ≈ 17°/s — a full revolution every ~21 s. Purely
     *  visual; the physics body is welded rigid at identity. */
    private const val IDLE_SPIN_RAD_PER_TICK: Double = 0.015

    /** Max blocks below `livePos` scanned for the crystal's home
     *  pedestal. Matches the server-side [org.shipwrights.enderkinesis
     *  .body.GamylanCagePedestals] discovery window so client and
     *  server agree on which pedestal the crystal is docked to. */
    private const val PEDESTAL_SCAN_MAX_DROP: Int = 8

    /** Soder bottle's BODY-LABEL BOX dimensions in blocks at scale = 1.
     *  Drives the fit-to-AABB scale applied before [SoderRenderer.render]
     *  in the Tribulla branch — sizing on the label region (not the
     *  whole bottle) makes the entity fit cleanly inside the label.
     *  Matches the -0.1 visual deform `SoderRenderer` uses for the body
     *  box so the body doesn't clip into the shoulder / base; kept in
     *  lockstep with [SoderShape]'s constants. */
    private const val SODER_NATURAL_WIDTH_BLOCKS: Double = 4.8 / 16.0
    private const val SODER_NATURAL_HEIGHT_BLOCKS: Double = 6.8 / 16.0

    /** Outer face alpha at the mesh edge. Translucent (not additive) so
     *  the crystal reads as a tinted shell. The thin rim gradient
     *  produced by [GLOW_INSET] doubles as the crystal outline — the
     *  separate LINES pass was removed, so this needs to be high enough
     *  to read as a crisp edge on its own. */
    private const val GLOW_OUTER_ALPHA: Float = 230f

    /** Strip-inset fraction toward face centroid. Small value → the
     *  bright-to-transparent fade covers only a thin band near each
     *  edge, giving an outline-like rim; large value → the fade
     *  covers most of the face, giving a wide haze. 0.15 puts the
     *  fade in the outer 15% of each face — reads as a crisp bright
     *  border around every crystal facet without a separate LINES
     *  draw. */
    private const val GLOW_INSET: Float = 0.15f

    /** Vanilla block-destroy texture stages, used as a damage overlay
     *  on the crystal so it reads like a breaking block. Indexed by
     *  damage stage in `[0, 9]`. */
    private val CRUMBLE_TYPES: List<RenderType> = (0 until 10).map { stage ->
        RenderType.crumbling(ResourceLocation("textures/block/destroy_stage_$stage.png"))
    }

    fun render(poseStack: PoseStack, camera: Camera, partialTick: Float) {
        // Shatter debris outlives the cage entries themselves — render
        // it even when no live cages remain.
        GamylanCageShatter.render(poseStack, camera, partialTick)
        val entries = GamylanCageClientRegistry.currentLevelEntries()
        if (entries.isEmpty()) return
        val mc = Minecraft.getInstance()
        val buffer = mc.renderBuffers().bufferSource()
        val now = ((mc.level?.gameTime ?: 0L) + partialTick.toDouble())
        val camPos = camera.position
        for (entry in entries) {
            val elapsed = (now - entry.addedAtGameTime.toDouble()).coerceAtLeast(0.0)
            val fade = (elapsed / FADE_TICKS).coerceIn(0.0, 1.0)
            val (rx, midH, tipLen) = computeRadii(entry)
            // Live body transform — during fade the body is pinned at
            // hoverPos and rotation-locked to the server-driven yaw;
            // after fade it falls and tumbles, and the renderer follows
            // both translation and orientation.
            val livePos = GamylanCageClientRegistry.livePosition(entry)
            val liveRot = GamylanCageClientRegistry.liveRotation(entry)
            val liveScale = GamylanCageClientRegistry.liveScale(entry)

            // Lerp the rendered crystal & entity centre from the
            // capture origin up to the live body position over the
            // fade window. Pre-fade, both the entity and the crystal
            // start at the entity's pre-capture position; post-fade,
            // both sit at the body's true position (so collision and
            // visuals coincide). bbox half-height matches the renderer
            // adjustment in renderFrozen.
            val ghost = GamylanCageClientRegistry.renderEntityFor(entry)
            val halfH = ghost?.bbHeight?.toDouble()?.times(0.5) ?: 0.0
            // Custom-name easter egg: a captured entity with the
            // display name "Tribulla" swaps the hex crystal for a
            // Soder bottle. Damage overlay routes to SoderRenderer
            // (its own crumble emitter handles the bottle geometry).
            // Match a name-tagged mob (customName) OR a player whose
            // game-profile name is literally "Tribulla" (RemotePlayer
            // has null customName, so the profile-name path is the
            // only signal for the player-capture branch).
            val isTribulla = ghost?.customName?.string == "Tribulla" ||
                entry.playerName == "Tribulla"
            val centerX = Mth.lerp(fade, entry.captureOrigin.x, livePos.x)
            val centerY = Mth.lerp(fade, entry.captureOrigin.y + halfH, livePos.y)
            val centerZ = Mth.lerp(fade, entry.captureOrigin.z, livePos.z)

            // 1. Frozen captured entity at the lerped centre. Sample the
            //    world lightmap at the centre position and pass it in so
            //    the ghost picks up the same block+sky illumination as
            //    the surrounding world instead of rendering full-bright.
            val ghostLight = worldLightAt(mc, centerX, centerY, centerZ)
            renderFrozen(
                entry, partialTick, poseStack, buffer,
                camPos.x, camPos.y, camPos.z,
                centerX, centerY, centerZ, halfH, liveRot, liveScale, ghostLight,
            )

            // 2. Hex crystal at the same lerped centre. Body rotation
            //    (post-fade tumble) is applied first; then a smoothstep
            //    crystallization spin that ends at SPIN_REVOLUTIONS · 2π
            //    — a multiple of the hex's 60° rotational symmetry, so
            //    the visual mesh lands exactly aligned with the
            //    (un-spun) collision compound.
            poseStack.pushPose()
            poseStack.translate(centerX - camPos.x, centerY - camPos.y, centerZ - camPos.z)
            poseStack.mulPose(liveRot)
            // Scale before the spin so the crystal scales uniformly
            // around the body centre; mesh dims (rx/midH/tipLen) stay
            // in body-local units and the matrix carries the factor.
            poseStack.scale(liveScale, liveScale, liveScale)
            val smooth = fade * fade * (3.0 - 2.0 * fade)
            val fadeSpin = smooth * SPIN_REVOLUTIONS * 2.0 * Math.PI
            // Wrapped in Double: the Float cast below loses the per-frame step in an old world.
            val idleSpin = if (isOnPedestal(mc, livePos)) (now * IDLE_SPIN_RAD_PER_TICK) % (2.0 * Math.PI) else 0.0
            poseStack.mulPose(Quaternionf().rotateY((fadeSpin + idleSpin).toFloat()))
            if (isTribulla) {
                // Soder bottle emits at its natural model size. Pick
                // ONE uniform scale large enough that the body box
                // contains the entity's AABB on both axes — width-fit
                // OR height-fit, whichever is bigger. The bottle's
                // proportions stay intact; the unconstrained axis just
                // gains slack inside the label region. ghost is
                // guaranteed non-null here because isTribulla derived
                // from its customName.
                val bbW = ghost?.bbWidth?.toDouble() ?: 1.0
                val bbH = ghost?.bbHeight?.toDouble() ?: 1.0
                val fitW = bbW / SODER_NATURAL_WIDTH_BLOCKS
                val fitH = bbH / SODER_NATURAL_HEIGHT_BLOCKS
                val fit = Math.max(fitW, fitH).toFloat().coerceAtLeast(0.5f)
                poseStack.pushPose()
                poseStack.scale(fit, fit, fit)
                SoderRenderer.render(poseStack, buffer, fade.toFloat())
                if (entry.hp < entry.maxHp) {
                    val damageFrac = 1f - entry.hp.toFloat() / entry.maxHp.toFloat()
                    val stage = (damageFrac * 10f).toInt().coerceIn(0, 9)
                    SoderRenderer.renderCrumble(poseStack, buffer, stage)
                }
                poseStack.popPose()
            } else {
                val litLight = worldLightAt(mc, centerX, centerY, centerZ)
                // Pedestal-conjured item cages: draw the item at the crystal
                // centre BEFORE the shell so the translucent gradient blends
                // over it (spins with the crystal — we're inside its pose).
                if (!entry.displayedItem.isEmpty) {
                    renderDisplayedItem(entry.displayedItem, poseStack, buffer, litLight)
                }
                // Rim gradient + outline FIRST so their translucent
                // fill hits the framebuffer before the lit rim's
                // additive pass. If we reversed this, the gradient's
                // ~90% source-alpha would overwrite the additive
                // contribution to ~10%, making the light response
                // effectively invisible against the tint.
                renderCrystal(poseStack, buffer, fade.toFloat(), rx, midH, tipLen, entry.crystalColor)
                renderLitFaces(poseStack, buffer, mc, fade.toFloat(), rx, midH, tipLen, entry.crystalColor, litLight, centerX, centerY, centerZ)
                // Block-break overlay if the crystal has taken damage.
                // Stage 0..9 from damage fraction; not drawn while at
                // full HP (regen pushes hp back to maxHp 2 s after the
                // last hit — see GamylanCageManager).
                if (entry.hp < entry.maxHp) {
                    val damageFrac = 1f - entry.hp.toFloat() / entry.maxHp.toFloat()
                    val stage = (damageFrac * 10f).toInt().coerceIn(0, 9)
                    renderCrumble(poseStack, buffer, stage, rx, midH, tipLen)
                }
            }
            poseStack.popPose()
        }
    }

    private fun renderFrozen(
        entry: GamylanCageClientRegistry.Entry, partialTick: Float,
        poseStack: PoseStack, buffer: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double,
        centerX: Double, centerY: Double, centerZ: Double, halfH: Double,
        liveRot: Quaternionf, liveScale: Float, packedLight: Int,
    ) {
        val ghost = GamylanCageClientRegistry.renderEntityFor(entry) ?: return
        // Vanilla entity rendering puts the entity's FEET at the render
        // origin and the bbox centre at feet + bbHeight/2. Translate to
        // the precomputed bbox centre, apply body rotation, scale by
        // the body's transform scale (Staff-of-Scales shrinks the cage
        // AND its ghost uniformly), then step down by halfH so the feet
        // land at the rotated + scaled origin.
        //
        // Vanilla's camera-oriented sub-renders (name tag via
        // `EntityRenderer.renderNameTag`, fire via
        // `EntityRenderDispatcher.renderFlame`) multiply the camera
        // orientation onto the CURRENT pose. If the pose is pre-rotated
        // by [liveRot], the billboard ends up tilted. [pendingCameraCompensation]
        // hands the inverse rotation to the mixins covering those two
        // methods so the tilt cancels before their camera-facing
        // multiplication runs — leaving the entity model itself
        // rotated with the cage while its name tag / fire billboard
        // correctly to the camera.
        poseStack.pushPose()
        poseStack.translate(centerX - camX, centerY - camY, centerZ - camZ)
        poseStack.mulPose(liveRot)
        poseStack.scale(liveScale, liveScale, liveScale)
        poseStack.translate(0.0, -halfH, 0.0)
        pendingGhostRender = true
        val dispatcher = Minecraft.getInstance().entityRenderDispatcher
        // Crystal caged the entity off the ground — a contact shadow
        // pinned to its (rotating, possibly upside-down) feet reads as
        // a render bug. The field is private and has no getter; vanilla
        // keeps it `true` throughout the render loop, so flip off
        // around our call and restore the vanilla default after.
        dispatcher.setRenderShadow(false)
        try {
            dispatcher.render(
                ghost, 0.0, 0.0, 0.0,
                ghost.yRot, partialTick,
                poseStack, buffer,
                packedLight,
            )
        } finally {
            dispatcher.setRenderShadow(true)
            pendingGhostRender = false
        }
        poseStack.popPose()
    }

    /** Yaw-only quaternion that rotates around the pose's current local
     *  +Y axis so its local -Z axis points at the camera. Used by the
     *  cage's name-tag / fire mixins to replace vanilla's full-3D
     *  billboard with a "labels lean with the mob but face the player"
     *  effect.
     *
     *  Camera is at world-frame origin in the pose-relative coord
     *  system MC sets up in `LevelRenderer.renderLevel`, so the
     *  direction from local origin to camera is just `-translation`
     *  of the current matrix. Projecting that direction onto local XZ
     *  gives the yaw target; `atan2` recovers the rotation angle. */
    @JvmStatic
    fun computeLocalYawTowardCamera(pose: PoseStack): Quaternionf {
        val m = pose.last().pose()
        val lx = m.m30(); val ly = m.m31(); val lz = m.m32()
        val distSq = lx * lx + ly * ly + lz * lz
        if (distSq < 1e-8f) return Quaternionf()
        val invLen = 1.0f / Math.sqrt(distSq.toDouble()).toFloat()
        val dx = -lx * invLen
        val dy = -ly * invLen
        val dz = -lz * invLen
        val lXcomp = dx * m.m00() + dy * m.m01() + dz * m.m02()
        val lZcomp = dx * m.m20() + dy * m.m21() + dz * m.m22()
        val xzLenSq = lXcomp * lXcomp + lZcomp * lZcomp
        if (xzLenSq < 1e-8f) return Quaternionf()
        val theta = Math.atan2((-lXcomp).toDouble(), (-lZcomp).toDouble()).toFloat()
        return Quaternionf().rotateY(theta)
    }

    /** True while [renderFrozen] is inside `EntityRenderDispatcher.render`
     *  for a cage-caught ghost. Read by the two mixins on
     *  `EntityRenderer.renderNameTag` (camera-orientation) and
     *  `EntityRenderDispatcher.renderFlame` (yaw billboard) so they can
     *  swap vanilla's full-3D camera billboard for a yaw-only rotation
     *  around the mob's local +Y axis toward the camera — the mob's
     *  tilt still shows on the name tag / fire, but the text/flame
     *  rotates in local yaw to face the player. */
    @Volatile
    @JvmStatic
    var pendingGhostRender: Boolean = false
        private set

    /** Half-width for outline lines in body-local units. A quad-strip
     *  is emitted per edge with this half-width offset perpendicular to
     *  both the edge and the view direction (in local frame), so the
     *  line reads as a view-facing wire that shrinks with perspective
     *  distance — unlike GL_LINES which stays a fixed pixel width. */
    private const val LINE_HALF_WIDTH: Float = 0.012f

    /** Peak vertex α for the additive lit-rim pass at full fade.
     *  Emitted AFTER the rim gradient so the additive contribution
     *  lands on top rather than being overwritten. Per-face Phong
     *  factor scales this further; typical range 0.3–1.3 × base. */
    private const val LIT_FACE_ALPHA: Float = 120f

    /** Minimum world-light gradient magnitude for the sampled light
     *  direction to override the world-up fallback. Below this, we
     *  treat lighting as uniform in every direction and use the
     *  transformed world-up so the crystal at least catches "sun from
     *  above" in caves without any bright neighbours. */
    private const val LIGHT_GRAD_MIN: Float = 0.05f

    /** Ambient / Lambert-diffuse / specular contributions to the
     *  per-face Phong factor. Range at max lambert + specular:
     *  0.30 + 0.55 + 0.45 = 1.30. Range at min (face facing away):
     *  0.30 (ambient only). Factor multiplies the world-light-curved
     *  alpha, so a lit face on a daylight crystal peaks at 1.30 × 120
     *  = 156 (out of 255) — a bright highlight without blowing out. */
    private const val PHONG_AMBIENT: Float = 0.30f
    private const val PHONG_DIFFUSE: Float = 0.55f
    private const val PHONG_SPECULAR: Float = 0.45f

    /** True if an unpowered Crystal Pedestal sits within
     *  [PEDESTAL_SCAN_MAX_DROP] blocks below `livePos` (XZ column
     *  aligned to the body centre) in either the world frame or any
     *  ship whose bbox overlaps the crystal column. Client-side signal
     *  for the idle spin — mirrors the server-side discovery pass in
     *  [org.shipwrights.enderkinesis.body.GamylanCagePedestals] so both
     *  sides agree on which pedestal counts as home. */
    private fun isOnPedestal(mc: Minecraft, livePos: org.joml.Vector3d): Boolean {
        val level = mc.level ?: return false
        if (columnHitsPedestal(level, livePos, livePos)) return true
        val scan = org.joml.primitives.AABBd(
            livePos.x - 1.0, livePos.y - PEDESTAL_SCAN_MAX_DROP.toDouble() - 1.0, livePos.z - 1.0,
            livePos.x + 1.0, livePos.y,                                            livePos.z + 1.0,
        )
        for (ship in level.shipObjectWorld.allShips.getIntersecting(scan)) {
            val shipyard = org.joml.Vector3d(livePos)
            ship.transform.worldToShip.transformPosition(shipyard)
            if (columnHitsPedestal(level, livePos, shipyard)) return true
        }
        return false
    }

    /** Walks a single owning-frame column at `centreInFrame` for the
     *  topmost pedestal. `centreInFrame` is `livePos` for the world
     *  scan and the shipyard-mapped position for a per-ship scan. */
    private fun columnHitsPedestal(
        level: net.minecraft.client.multiplayer.ClientLevel,
        livePosWorld: org.joml.Vector3d,
        centreInFrame: org.joml.Vector3d,
    ): Boolean {
        val pedestalBlock = org.shipwrights.enderkinesis.registry.EKBlocks.CRYSTAL_PEDESTAL.get()
        val px = Math.floor(centreInFrame.x).toInt()
        val pz = Math.floor(centreInFrame.z).toInt()
        val startY = Math.floor(centreInFrame.y).toInt()
        val cursor = net.minecraft.core.BlockPos.MutableBlockPos()
        for (dy in 1..PEDESTAL_SCAN_MAX_DROP) {
            cursor.set(px, startY - dy, pz)
            val state = level.getBlockState(cursor)
            if (!state.`is`(pedestalBlock)) continue
            return !state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)
        }
        return false
    }

    /** Items rendered through the handheld transform inside item cages —
     *  the `enderkinesis:staffs` tag; their models are authored for the
     *  in-hand pose and read wrong through FIXED. */
    private val STAFFS: net.minecraft.tags.TagKey<net.minecraft.world.item.Item> =
        net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.ITEM,
            org.shipwrights.enderkinesis.EnderkinesisMod.id("staffs"),
        )

    private const val DISPLAY_ITEM_SCALE: Float = 0.6f
    private const val DISPLAY_STAFF_SCALE: Float = 0.5f

    private fun renderDisplayedItem(
        stack: net.minecraft.world.item.ItemStack,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ) {
        val mc = Minecraft.getInstance()
        // Tridents special-case in ItemRenderer: GUI/GROUND/FIXED contexts swap
        // to the flat inventory sprite; only hand contexts route through the
        // BEWLR 3-D model. Staffs want the hand transform for their long
        // diagonal. Both therefore render THIRD_PERSON_RIGHT_HAND.
        val handheld = stack.`is`(STAFFS) || stack.item is net.minecraft.world.item.TridentItem
        poseStack.pushPose()
        val scale = if (handheld) DISPLAY_STAFF_SCALE else DISPLAY_ITEM_SCALE
        poseStack.scale(scale, scale, scale)
        mc.itemRenderer.renderStatic(
            stack,
            if (handheld) net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
            else net.minecraft.world.item.ItemDisplayContext.FIXED,
            packedLight,
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
            poseStack, buffer, mc.level, 0,
        )
        poseStack.popPose()
    }

    /** Look up the vanilla lightmap value at a world position — the
     *  crystal is small enough that per-vertex variation isn't worth
     *  the extra BlockPos allocations, so we sample once at the centre
     *  and reuse. Full-bright fallback when the level isn't ready. */
    private fun worldLightAt(
        mc: Minecraft, x: Double, y: Double, z: Double,
    ): Int {
        val level = mc.level ?: return 0xF000F0
        val pos = net.minecraft.core.BlockPos.containing(x, y, z)
        return net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos)
    }

    /** Additive light-based flat highlight with a pseudo-Phong
     *  per-face shading pass, driven by the surrounding world
     *  block+sky light:
     *
     *   1. World-lightmap curve — `pow(brightness, 6)` on the
     *      centre's max(sky, block) light gates the whole pass to
     *      near-daylight conditions.
     *   2. Sampled light direction — six axial-neighbour block+sky
     *      brightness samples give a gradient vector pointing toward
     *      the brightest surroundings. If the gradient is too small
     *      (uniform lighting), we fall back to world-up so cave-lit
     *      crystals still get an "overhead sun" feel.
     *   3. Per-face Phong — ambient + Lambert diffuse + specular
     *      against that dynamic light direction, in body-local
     *      space. View direction for the specular reflection comes
     *      from the camera position (same trick the outline lines
     *      use). */
    private fun renderLitFaces(
        poseStack: PoseStack, buffer: MultiBufferSource,
        mc: Minecraft,
        fade: Float, rx: Float, midH: Float, tipLen: Float, tintRgb: Int,
        packedLight: Int, worldX: Double, worldY: Double, worldZ: Double,
    ) {
        val skyLight = (packedLight shr 20) and 0xF
        val blockLight = (packedLight shr 4) and 0xF
        val brightness = kotlin.math.max(skyLight, blockLight) / 15f
        val b2c = brightness * brightness
        val b4c = b2c * b2c
        val curved = b4c * b2c
        val baseAlpha = fade * LIT_FACE_ALPHA * curved
        if (baseAlpha <= 0.5f) return

        val matrix = poseStack.last().pose()
        val r = (tintRgb shr 16) and 0xFF
        val g = (tintRgb shr 8) and 0xFF
        val b = tintRgb and 0xFF

        // Inverse pose matrix — same one we use to bring camera into
        // body-local for the outline lines. Reused here to transform
        // the world-space light-direction sample into body-local so
        // it can be dot-producted against face normals.
        val invMatrix = org.joml.Matrix4f(matrix).invert()

        // Camera in body-local — view direction for the specular term.
        val camLocal = invMatrix.transformPosition(org.joml.Vector3f())
        val camLen = kotlin.math.sqrt(
            camLocal.x * camLocal.x + camLocal.y * camLocal.y + camLocal.z * camLocal.z
        ).coerceAtLeast(1e-4f)
        val vX = camLocal.x / camLen
        val vY = camLocal.y / camLen
        val vZ = camLocal.z / camLen

        // Sampled light direction in body-local.
        val lightBody = sampleLightDirection(mc, worldX, worldY, worldZ, invMatrix)
        val lX = lightBody.x
        val lY = lightBody.y
        val lZ = lightBody.z

        val topEq = Array(6) { i ->
            val a = i * Math.PI.toFloat() / 3f
            Vec3f(rx * Mth.cos(a), midH, rx * Mth.sin(a))
        }
        val bottomEq = Array(6) { i ->
            val a = i * Math.PI.toFloat() / 3f
            Vec3f(rx * Mth.cos(a), -midH, rx * Mth.sin(a))
        }
        val topApex = Vec3f(0f, midH + tipLen, 0f)
        val bottomApex = Vec3f(0f, -midH - tipLen, 0f)

        val v = buffer.getBuffer(GamylanCageRenderType.FACE_LIT)
        for (i in 0 until 6) {
            emitPhongTri(v, matrix, topApex, topEq[(i + 1) % 6], topEq[i], baseAlpha, r, g, b, lX, lY, lZ, vX, vY, vZ)
            emitPhongTri(v, matrix, bottomApex, bottomEq[i], bottomEq[(i + 1) % 6], baseAlpha, r, g, b, lX, lY, lZ, vX, vY, vZ)
            emitPhongQuad(
                v, matrix,
                topEq[i], topEq[(i + 1) % 6], bottomEq[(i + 1) % 6], bottomEq[i],
                baseAlpha, r, g, b, lX, lY, lZ, vX, vY, vZ,
            )
        }
    }

    /** Sample block+sky brightness in the 6 axial neighbours of the
     *  crystal's world position and return a body-local unit vector
     *  pointing TOWARD the brightest side. Fallback is world-up
     *  (transformed to body-local) when the gradient is negligible. */
    private fun sampleLightDirection(
        mc: Minecraft, x: Double, y: Double, z: Double,
        invMatrix: org.joml.Matrix4f,
    ): org.joml.Vector3f {
        val level = mc.level
        val worldDir = org.joml.Vector3f()
        if (level == null) {
            worldDir.set(0f, 1f, 0f)
        } else {
            val pos = net.minecraft.core.BlockPos.containing(x, y, z)
            val east = neighborBrightness(level, pos.east())
            val west = neighborBrightness(level, pos.west())
            val up = neighborBrightness(level, pos.above())
            val down = neighborBrightness(level, pos.below())
            val south = neighborBrightness(level, pos.south())
            val north = neighborBrightness(level, pos.north())
            val dx = east - west
            val dy = up - down
            val dz = south - north
            val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            if (len < LIGHT_GRAD_MIN) worldDir.set(0f, 1f, 0f)
            else worldDir.set(dx / len, dy / len, dz / len)
        }
        // World → body-local; transformDirection ignores translation.
        val bodyDir = org.joml.Vector3f()
        invMatrix.transformDirection(worldDir, bodyDir)
        val len = kotlin.math.sqrt(bodyDir.x * bodyDir.x + bodyDir.y * bodyDir.y + bodyDir.z * bodyDir.z)
            .coerceAtLeast(1e-4f)
        bodyDir.set(bodyDir.x / len, bodyDir.y / len, bodyDir.z / len)
        return bodyDir
    }

    /** Max of block and sky light at [pos], normalized to `[0, 1]`. */
    private fun neighborBrightness(level: net.minecraft.world.level.Level, pos: net.minecraft.core.BlockPos): Float {
        val sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos)
        val block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos)
        return kotlin.math.max(sky, block) / 15f
    }

    /** Ambient + Lambert diffuse + specular factor for one face's
     *  normal, in body-local coords. `lX/lY/lZ` = light direction,
     *  `vX/vY/vZ` = view direction. Range roughly `[PHONG_AMBIENT,
     *  PHONG_AMBIENT + PHONG_DIFFUSE + PHONG_SPECULAR]`. */
    private fun phongFactor(
        nx: Float, ny: Float, nz: Float,
        lX: Float, lY: Float, lZ: Float,
        vX: Float, vY: Float, vZ: Float,
    ): Float {
        val lambert = kotlin.math.max(nx * lX + ny * lY + nz * lZ, 0f)
        val rx = -lX + 2f * lambert * nx
        val ry = -lY + 2f * lambert * ny
        val rz = -lZ + 2f * lambert * nz
        val specDot = kotlin.math.max(rx * vX + ry * vY + rz * vZ, 0f)
        val s2 = specDot * specDot
        val s4 = s2 * s2
        val specular = s4 * s4
        return PHONG_AMBIENT + PHONG_DIFFUSE * lambert + PHONG_SPECULAR * specular
    }

    private fun emitPhongTri(
        v: VertexConsumer, matrix: Matrix4f,
        a: Vec3f, b: Vec3f, c: Vec3f,
        baseAlpha: Float, r: Int, g: Int, b2: Int,
        lX: Float, lY: Float, lZ: Float,
        vX: Float, vY: Float, vZ: Float,
    ) {
        val (nx, ny, nz) = faceNormal(a, b, c)
        val alpha = (baseAlpha * phongFactor(nx, ny, nz, lX, lY, lZ, vX, vY, vZ)).toInt().coerceIn(0, 255)
        emitFlatTri(v, matrix, a, b, c, alpha, r, g, b2)
    }

    private fun emitPhongQuad(
        v: VertexConsumer, matrix: Matrix4f,
        a: Vec3f, b: Vec3f, c: Vec3f, d: Vec3f,
        baseAlpha: Float, r: Int, g: Int, b2: Int,
        lX: Float, lY: Float, lZ: Float,
        vX: Float, vY: Float, vZ: Float,
    ) {
        val (nx, ny, nz) = faceNormal(a, b, c)
        val alpha = (baseAlpha * phongFactor(nx, ny, nz, lX, lY, lZ, vX, vY, vZ)).toInt().coerceIn(0, 255)
        emitFlatQuad(v, matrix, a, b, c, d, alpha, r, g, b2)
    }

    private fun emitFlatTri(
        v: VertexConsumer, matrix: Matrix4f,
        a: Vec3f, b: Vec3f, c: Vec3f,
        alpha: Int, r: Int, g: Int, b2: Int,
    ) {
        v.vertex(matrix, a.x, a.y, a.z).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, b.x, b.y, b.z).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, c.x, c.y, c.z).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, c.x, c.y, c.z).color(r, g, b2, alpha).endVertex()
    }

    private fun emitFlatQuad(
        v: VertexConsumer, matrix: Matrix4f,
        a: Vec3f, b: Vec3f, c: Vec3f, d: Vec3f,
        alpha: Int, r: Int, g: Int, b2: Int,
    ) {
        v.vertex(matrix, a.x, a.y, a.z).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, b.x, b.y, b.z).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, c.x, c.y, c.z).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, d.x, d.y, d.z).color(r, g, b2, alpha).endVertex()
    }


    /** Hex bipyramid + middle prism geometry. 14 verts, 18 faces
     *  (6 top pyramid + 6 middle prism + 6 bottom pyramid), 30 edges.
     *  [tintRgb] is the packed 0xRRGGBB colour used for the shell rim
     *  gradient (per-entry, from the captured mob's spawn egg accent).
     *  Emits at the full tint regardless of world lighting — the
     *  outline reads as an inherent-glow effect. */
    internal fun renderCrystal(
        poseStack: PoseStack, buffer: MultiBufferSource,
        fade: Float, rx: Float, midH: Float, tipLen: Float, tintRgb: Int,
    ) {
        val matrix = poseStack.last().pose()
        val faceAlpha = (fade * GLOW_OUTER_ALPHA).toInt().coerceIn(0, 255)
        val r = (tintRgb shr 16) and 0xFF
        val g = (tintRgb shr 8) and 0xFF
        val b = tintRgb and 0xFF

        val topEq = Array(6) { i ->
            val a = i * Math.PI.toFloat() / 3f
            Vec3f(rx * Mth.cos(a), midH, rx * Mth.sin(a))
        }
        val bottomEq = Array(6) { i ->
            val a = i * Math.PI.toFloat() / 3f
            Vec3f(rx * Mth.cos(a), -midH, rx * Mth.sin(a))
        }
        val topApex = Vec3f(0f, midH + tipLen, 0f)
        val bottomApex = Vec3f(0f, -midH - tipLen, 0f)

        val faceConsumer = buffer.getBuffer(GamylanCageRenderType.FACE_GLOW)
        for (i in 0 until 6) {
            emitTriGradient(faceConsumer, matrix, topApex, topEq[(i + 1) % 6], topEq[i], faceAlpha, r, g, b)
            emitTriGradient(faceConsumer, matrix, bottomApex, bottomEq[i], bottomEq[(i + 1) % 6], faceAlpha, r, g, b)
            emitQuadGradient(
                faceConsumer, matrix,
                topEq[i], topEq[(i + 1) % 6], bottomEq[(i + 1) % 6], bottomEq[i],
                faceAlpha, r, g, b,
            )
        }

        // Outline — 30 crystal edges emitted as view-facing quad
        // strips of fixed local half-width. Because they live in local
        // coords, the perspective divide shrinks them naturally with
        // distance (unlike GL_LINES which stays a fixed pixel width
        // regardless of how far the crystal is). Colour lifts the tint
        // 40% toward white — same formula the shatter uses for its
        // edge lines and what the original crystal LINES pass used,
        // so the outline reads as a brighter version of the crystal's
        // hue rather than a fixed white.
        val outlineR = r + (255 - r) * 4 / 10
        val outlineG = g + (255 - g) * 4 / 10
        val outlineB = b + (255 - b) * 4 / 10
        val camLocal = org.joml.Matrix4f(matrix).invert().transformPosition(org.joml.Vector3f())
        for (i in 0 until 6) {
            emitOutlineLine(faceConsumer, matrix, camLocal, topApex, topEq[i], faceAlpha, outlineR, outlineG, outlineB)
            emitOutlineLine(faceConsumer, matrix, camLocal, bottomApex, bottomEq[i], faceAlpha, outlineR, outlineG, outlineB)
            emitOutlineLine(faceConsumer, matrix, camLocal, topEq[i], topEq[(i + 1) % 6], faceAlpha, outlineR, outlineG, outlineB)
            emitOutlineLine(faceConsumer, matrix, camLocal, bottomEq[i], bottomEq[(i + 1) % 6], faceAlpha, outlineR, outlineG, outlineB)
            emitOutlineLine(faceConsumer, matrix, camLocal, topEq[i], bottomEq[i], faceAlpha, outlineR, outlineG, outlineB)
        }
    }

    /** Emit one crystal edge as a view-facing quad strip: half-width
     *  [LINE_HALF_WIDTH] offset perpendicular to both the edge and
     *  (camera - midpoint), all in local coords. The perspective
     *  divide handles distance falloff — near lines are chunky, far
     *  lines are thin, which is what you want for a wireframe read. */
    private fun emitOutlineLine(
        v: VertexConsumer, matrix: Matrix4f,
        camLocal: org.joml.Vector3f,
        a: Vec3f, b: Vec3f,
        alpha: Int, r: Int, g: Int, b2: Int,
    ) {
        val midX = (a.x + b.x) * 0.5f
        val midY = (a.y + b.y) * 0.5f
        val midZ = (a.z + b.z) * 0.5f
        val viewX = camLocal.x - midX
        val viewY = camLocal.y - midY
        val viewZ = camLocal.z - midZ
        val lineX = b.x - a.x
        val lineY = b.y - a.y
        val lineZ = b.z - a.z
        var perpX = viewY * lineZ - viewZ * lineY
        var perpY = viewZ * lineX - viewX * lineZ
        var perpZ = viewX * lineY - viewY * lineX
        val perpLenSq = perpX * perpX + perpY * perpY + perpZ * perpZ
        if (perpLenSq < 1e-10f) return  // looking exactly along the edge — skip this frame
        val invLen = LINE_HALF_WIDTH / kotlin.math.sqrt(perpLenSq)
        perpX *= invLen; perpY *= invLen; perpZ *= invLen
        v.vertex(matrix, a.x + perpX, a.y + perpY, a.z + perpZ).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, b.x + perpX, b.y + perpY, b.z + perpZ).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, b.x - perpX, b.y - perpY, b.z - perpZ).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, a.x - perpX, a.y - perpY, a.z - perpZ).color(r, g, b2, alpha).endVertex()
    }

    /** Orthonormal tangent frame for a face. T = normalized(b-a); the
     *  bitangent is N × T where N is the face normal. Same choice on
     *  every face means the UV origin is at vertex A and grows along the
     *  A→B edge — stable per-face, no seam-matching across faces. */
    private fun tangentFrame(a: Vec3f, b: Vec3f, c: Vec3f): TangentFrame {
        val (nx, ny, nz) = faceNormal(a, b, c)
        var tx = b.x - a.x; var ty = b.y - a.y; var tz = b.z - a.z
        val invLen = 1f / kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-4f)
        tx *= invLen; ty *= invLen; tz *= invLen
        val bx = ny * tz - nz * ty
        val by = nz * tx - nx * tz
        val bz = nx * ty - ny * tx
        return TangentFrame(a, tx, ty, tz, bx, by, bz)
    }

    private data class TangentFrame(
        val origin: Vec3f,
        val tx: Float, val ty: Float, val tz: Float,
        val bx: Float, val by: Float, val bz: Float,
    )

    /** UV units per world unit for the crumble crack overlay. 1.0
     *  matches the vanilla block convention (16 texels per block face),
     *  and the destroy_stage_N textures default to GL_REPEAT wrap so
     *  any face wider than 1 block tiles the crack pattern instead of
     *  smearing it. Every face samples at the same texel-per-block
     *  rate — the 1:1 pixel-consistent contract also applies here. */
    private const val CRUMBLE_UV_SCALE: Float = 1.0f

    /** Block-break overlay over each face of the hex bipyramid. Picks
     *  the destroy_stage_N texture matching the current damage stage
     *  and maps it as the face texture; the vanilla crumbling render
     *  type handles the "darken underneath" multiply blend. */
    private fun renderCrumble(
        poseStack: PoseStack, buffer: MultiBufferSource,
        stage: Int, rx: Float, midH: Float, tipLen: Float,
    ) {
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()
        val v = buffer.getBuffer(CRUMBLE_TYPES[stage.coerceIn(0, 9)])

        val topEq = Array(6) { i ->
            val a = i * Math.PI.toFloat() / 3f
            Vec3f(rx * Mth.cos(a), midH, rx * Mth.sin(a))
        }
        val bottomEq = Array(6) { i ->
            val a = i * Math.PI.toFloat() / 3f
            Vec3f(rx * Mth.cos(a), -midH, rx * Mth.sin(a))
        }
        val topApex = Vec3f(0f, midH + tipLen, 0f)
        val bottomApex = Vec3f(0f, -midH - tipLen, 0f)

        for (i in 0 until 6) {
            // Top tip triangle (apex → eqB → eqA) — outward normal.
            emitCrumbleTri(v, matrix, normal, topApex, topEq[(i + 1) % 6], topEq[i])
            // Bottom tip (apex → eqA → eqB) — outward normal points down.
            emitCrumbleTri(v, matrix, normal, bottomApex, bottomEq[i], bottomEq[(i + 1) % 6])
            // Middle prism face.
            emitCrumbleQuad(
                v, matrix, normal,
                topEq[i], topEq[(i + 1) % 6], bottomEq[(i + 1) % 6], bottomEq[i],
            )
        }
    }

    private fun emitCrumbleTri(
        v: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        a: Vec3f, b: Vec3f, c: Vec3f,
    ) {
        val f = tangentFrame(a, b, c)
        val (nx, ny, nz) = faceNormal(a, b, c)
        // Degenerate quad: emit C twice so QUADS mode renders the tri.
        crumbleVertex(v, matrix, normal, a, f, nx, ny, nz)
        crumbleVertex(v, matrix, normal, b, f, nx, ny, nz)
        crumbleVertex(v, matrix, normal, c, f, nx, ny, nz)
        crumbleVertex(v, matrix, normal, c, f, nx, ny, nz)
    }

    private fun emitCrumbleQuad(
        v: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        a: Vec3f, b: Vec3f, c: Vec3f, d: Vec3f,
    ) {
        val f = tangentFrame(a, b, c)
        val (nx, ny, nz) = faceNormal(a, b, c)
        crumbleVertex(v, matrix, normal, a, f, nx, ny, nz)
        crumbleVertex(v, matrix, normal, b, f, nx, ny, nz)
        crumbleVertex(v, matrix, normal, c, f, nx, ny, nz)
        crumbleVertex(v, matrix, normal, d, f, nx, ny, nz)
    }

    private fun crumbleVertex(
        v: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        p: Vec3f, f: TangentFrame,
        nx: Float, ny: Float, nz: Float,
    ) {
        val dx = p.x - f.origin.x
        val dy = p.y - f.origin.y
        val dz = p.z - f.origin.z
        val u = (dx * f.tx + dy * f.ty + dz * f.tz) * CRUMBLE_UV_SCALE
        val w = (dx * f.bx + dy * f.by + dz * f.bz) * CRUMBLE_UV_SCALE
        // BLOCK vertex format: position → color → uv → uv2 → normal.
        v.vertex(matrix, p.x, p.y, p.z)
            .color(255, 255, 255, 255)
            .uv(u, w)
            .uv2(0xF000F0)
            .normal(normal, nx, ny, nz)
            .endVertex()
    }

    private fun faceNormal(a: Vec3f, b: Vec3f, c: Vec3f): Triple<Float, Float, Float> {
        val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
        val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val invLen = 1f / kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f)
        nx *= invLen; ny *= invLen; nz *= invLen
        return Triple(nx, ny, nz)
    }

    private fun emitTriGradient(
        v: VertexConsumer, matrix: Matrix4f,
        a: Vec3f, b: Vec3f, c: Vec3f, alpha: Int, r: Int, g: Int, b2: Int,
    ) {
        val cx = (a.x + b.x + c.x) / 3f
        val cy = (a.y + b.y + c.y) / 3f
        val cz = (a.z + b.z + c.z) / 3f
        emitStrip(v, matrix, a, b, cx, cy, cz, alpha, r, g, b2)
        emitStrip(v, matrix, b, c, cx, cy, cz, alpha, r, g, b2)
        emitStrip(v, matrix, c, a, cx, cy, cz, alpha, r, g, b2)
    }

    private fun emitQuadGradient(
        v: VertexConsumer, matrix: Matrix4f,
        a: Vec3f, b: Vec3f, c: Vec3f, d: Vec3f, alpha: Int, r: Int, g: Int, b2: Int,
    ) {
        val cx = (a.x + b.x + c.x + d.x) * 0.25f
        val cy = (a.y + b.y + c.y + d.y) * 0.25f
        val cz = (a.z + b.z + c.z + d.z) * 0.25f
        emitStrip(v, matrix, a, b, cx, cy, cz, alpha, r, g, b2)
        emitStrip(v, matrix, b, c, cx, cy, cz, alpha, r, g, b2)
        emitStrip(v, matrix, c, d, cx, cy, cz, alpha, r, g, b2)
        emitStrip(v, matrix, d, a, cx, cy, cz, alpha, r, g, b2)
    }

    private fun emitStrip(
        v: VertexConsumer, matrix: Matrix4f,
        outerA: Vec3f, outerB: Vec3f,
        cx: Float, cy: Float, cz: Float, alpha: Int, r: Int, g: Int, b2: Int,
    ) {
        val iax = outerA.x + (cx - outerA.x) * GLOW_INSET
        val iay = outerA.y + (cy - outerA.y) * GLOW_INSET
        val iaz = outerA.z + (cz - outerA.z) * GLOW_INSET
        val ibx = outerB.x + (cx - outerB.x) * GLOW_INSET
        val iby = outerB.y + (cy - outerB.y) * GLOW_INSET
        val ibz = outerB.z + (cz - outerB.z) * GLOW_INSET
        v.vertex(matrix, outerA.x, outerA.y, outerA.z).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, outerB.x, outerB.y, outerB.z).color(r, g, b2, alpha).endVertex()
        v.vertex(matrix, ibx, iby, ibz).color(r, g, b2, 0).endVertex()
        v.vertex(matrix, iax, iay, iaz).color(r, g, b2, 0).endVertex()
    }

/** Returns (rx, midH, tipLen) from the server-broadcast shape
     *  dims, so the rendered mesh and the collision compound stay in
     *  exact lockstep. */
    private fun computeRadii(entry: GamylanCageClientRegistry.Entry): Triple<Float, Float, Float> {
        val d = entry.shapeDims
        return Triple(d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
    }

    private data class Vec3f(val x: Float, val y: Float, val z: Float)
}
