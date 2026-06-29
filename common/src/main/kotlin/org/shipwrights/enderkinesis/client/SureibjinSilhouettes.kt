package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import java.util.Random
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.Blocks
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.mixin.LocalPlayerCrouchingAccessor

/**
 * Four cardinal-direction silhouettes drawn at the "translucent" transition inside
 * `LevelRenderer.renderLevel` so they depth-test against world geometry but precede
 * translucents.
 *
 * Vertex color is forced to `(0,0,0,255)` via a wrapped [MultiBufferSource]; vanilla entity
 * shaders multiply texture × vertex color, so RGB ⇒ 0 while alpha-cutout still respects
 * the skin transparency. Fog uniforms are pushed to "no fog" before the batch and restored
 * — without that, distant silhouettes fade toward the fog tint and lose the black.
 *
 * The barrier ceiling at Y ≥ 100 would dominate any heightmap query, so the sand-top scan
 * walks down from Y=99 skipping barrier/obsidian. Local player fields ([Player.pose],
 * swing state, attackAnim/oAttackAnim) are snapshotted before each silhouette ticks its
 * faked animation and restored after — otherwise the fake state leaks into the real
 * player's first/third-person render.
 */
object SureibjinSilhouettes {

    private const val OFFSET_BLOCKS: Int = 16
    private const val SCAN_TOP_Y: Int = 99
    /** Matches `SureibjinChunkGenerator.SEA_LEVEL_Y`. Scan floor and the
     *  unloaded-column fallback so silhouettes never drop into the void. */
    private const val SEA_LEVEL_Y: Int = 63

    /** Parabolic jump peak in blocks. */
    private const val JUMP_PEAK_BLOCKS: Double = 1.25
    /** Strafe amplitude — bound on the random-walk target offset. */
    private const val STRAFE_AMP_BLOCKS: Double = 0.7
    /** Per-tick fraction of the gap closed between current strafe and
     *  target. Low values feel like a heavy, deliberate sidestep; high
     *  values feel twitchy. 0.05 ≈ ~1 second to settle. */
    private const val STRAFE_APPROACH_RATE: Double = 0.05

    /** Per-frame catch-up factor for the smoothed sand-top Y. Fast enough
     *  to look snappy while killing the integer-snap as the silhouette
     *  walks across block boundaries. */
    private const val Y_SMOOTH_FACTOR: Double = 0.30
    /** Above this delta the smoothed Y snaps instead of lerping — covers
     *  dimension change, teleport, render-distance change. */
    private const val Y_SNAP_THRESHOLD: Double = 8.0

    /** Y offset for the name-tag billboard above a standing silhouette
     *  (vanilla player bbHeight = 1.8 + 0.5 clearance). */
    private const val NAME_TAG_Y_STAND: Float = 2.3f
    /** Y offset for the name-tag billboard above a crouching silhouette
     *  (vanilla crouching bbHeight = 1.5 + 0.5 clearance). */
    private const val NAME_TAG_Y_CROUCH: Float = 2.0f
    /** Character pool for the silhouettes' random names. */
    private val NAME_CHARS: CharArray =
        ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ").toCharArray()

    /** Pre-allocated mutable BlockPos for the sand-top scan. Renderer
     *  runs on the render thread only, so no synchronization needed. */
    private val scratchPos = BlockPos.MutableBlockPos()

    /** One independent behavior cycle per silhouette. Seeds offset so the
     *  four silhouettes never march in lockstep. */
    private val states = arrayOf(
        SilhouetteState(0xA1L),
        SilhouetteState(0xA2L),
        SilhouetteState(0xA3L),
        SilhouetteState(0xA4L),
    )
    private val smoothedYs = doubleArrayOf(
        Double.NaN, Double.NaN, Double.NaN, Double.NaN,
    )
    private var lastGameTime: Long = Long.MIN_VALUE

    @JvmStatic
    fun render(poseStack: PoseStack, camera: Camera, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level: ClientLevel = mc.level ?: return
        if (level.dimension() != Sureibjin.LEVEL_KEY) return
        val player = mc.player ?: return

        // Tick the behavior states for each game tick that's elapsed since
        // the last render call. Capped to avoid runaway after long pauses.
        val now = level.gameTime
        val tickDelta = when {
            lastGameTime == Long.MIN_VALUE -> 0
            else -> (now - lastGameTime).coerceIn(0L, 5L).toInt()
        }
        lastGameTime = now
        if (tickDelta > 0) {
            for (i in 0 until tickDelta) {
                for (state in states) state.tick()
            }
        }

        // Entity render distance — vanilla's `Entity.shouldRenderAtSqrDistance`
        // gates at `boundingBox.size × 64 × Entity.viewScale`. Standing here
        // OFFSET_BLOCKS inside that radius keeps the silhouettes safely
        // within the entity-culling cutoff.
        val playerBoxSize = player.boundingBox.size
        val entityRenderRange = playerBoxSize * 64.0 * Entity.getViewScale()
        val dist = (entityRenderRange - OFFSET_BLOCKS).toInt()
        if (dist <= 0) return

        // player.x/z are post-tick (20 Hz); raw use makes silhouettes snap
        // each tick while the camera glides between ticks. Mth.lerp here
        // reproduces vanilla's sub-tick entity interpolation.
        val partialD = partialTick.toDouble()
        val px = Mth.lerp(partialD, player.xo, player.x)
        val pz = Mth.lerp(partialD, player.zo, player.z)

        // Cardinal offsets and the yaw each silhouette wears so its body
        // faces the local player. Yaw convention: 0 = +Z (south),
        // 90 = -X (west), 180 = -Z (north), -90 = +X (east).
        val sx = doubleArrayOf(px, px, px + dist, px - dist)
        val sz = doubleArrayOf(pz - dist, pz + dist, pz, pz)
        val yaw = floatArrayOf(0f, 180f, 90f, -90f)

        val baseBufferSource = mc.renderBuffers().bufferSource()
        val tintBufferSource = BlackTintBufferSource(baseBufferSource)
        val dispatcher = mc.entityRenderDispatcher
        @Suppress("UNCHECKED_CAST")
        val playerRenderer = dispatcher.getRenderer(player)
            as net.minecraft.client.renderer.entity.EntityRenderer<Player>

        val cameraPos = camera.position

        // Save fog so we can suppress mixing for our batch only.
        val savedFogStart = RenderSystem.getShaderFogStart()
        val savedFogEnd = RenderSystem.getShaderFogEnd()
        RenderSystem.setShaderFogStart(1.0e6f)
        RenderSystem.setShaderFogEnd(1.0e7f)

        // Snapshot the player fields we're about to mutate. Restored
        // after the silhouette loop so first-person hand rendering and
        // any other consumers see the player's true state.
        val savedSwinging = player.swinging
        val savedSwingTime = player.swingTime
        val savedSwingingArm = player.swingingArm
        val savedAttackAnim = player.attackAnim
        val savedOAttackAnim = player.oAttackAnim
        // LivingEntityRenderer ignores the entityYaw param we pass and
        // instead rotLerps the entity's own yBodyRot/yBodyRotO each
        // frame — same for the head. Without override the silhouette
        // mirrors the local player's facing instead of looking at them.
        val savedYBodyRot = player.yBodyRot
        val savedYBodyRotO = player.yBodyRotO
        val savedYHeadRot = player.yHeadRot
        val savedYHeadRotO = player.yHeadRotO
        // LocalPlayer overrides isCrouching() to read its own private
        // `crouching` field (not Pose.CROUCHING), so setPose has no effect
        // on what PlayerRenderer.setModelProperties sees. Use the
        // accessor mixin to flip the field directly.
        val crouchAccessor = player as? LocalPlayerCrouchingAccessor
        val savedCrouching = crouchAccessor?.`enderkinesis$getCrouching`() ?: false

        // Cache rotation + font for the name-tag billboard so we don't
        // resolve them per silhouette.
        val cameraRot = camera.rotation()
        val font = mc.font

        try {
            for (i in 0 until 4) {
                val state = states[i]

                // Strafe perpendicular to facing. N/S face along Z, so
                // strafe slides them along X; E/W face along X, so
                // strafe slides them along Z.
                val strafe = state.strafeOffset(partialTick)
                val finalSx: Double
                val finalSz: Double
                if (i <= 1) {
                    finalSx = sx[i] + strafe
                    finalSz = sz[i]
                } else {
                    finalSx = sx[i]
                    finalSz = sz[i] + strafe
                }

                val bx = Math.floor(finalSx).toInt()
                val bz = Math.floor(finalSz).toInt()
                val sandTopY = findSandTopY(level, bx, bz).toDouble()

                // Smooth the sand-top Y across frames so block-boundary
                // crossings don't pop. Snap on big deltas (dim change,
                // teleport) so we don't lerp across the world.
                val prevY = smoothedYs[i]
                val smoothY = when {
                    prevY.isNaN() -> sandTopY
                    Math.abs(sandTopY - prevY) > Y_SNAP_THRESHOLD -> sandTopY
                    else -> Mth.lerp(Y_SMOOTH_FACTOR, prevY, sandTopY)
                }
                smoothedYs[i] = smoothY
                val finalY = smoothY + state.jumpYOffset(partialTick)

                // Apply this silhouette's behavior to the player so the
                // PlayerRenderer reads the right state when it builds
                // the model.
                crouchAccessor?.`enderkinesis$setCrouching`(state.isSneaking)
                // Force body and head yaw — setting current and previous
                // tick to the same value keeps the rotLerp result pinned
                // to our target yaw across the whole frame.
                val yawI = yaw[i]
                player.yBodyRot = yawI
                player.yBodyRotO = yawI
                player.yHeadRot = yawI
                player.yHeadRotO = yawI
                val swingPair = state.swingProgress(partialTick)
                val swingPrev = swingPair[0]
                val swingCurr = swingPair[1]
                if (swingCurr > 0f || swingPrev > 0f) {
                    player.swinging = true
                    player.swingingArm = InteractionHand.MAIN_HAND
                    player.oAttackAnim = swingPrev
                    player.attackAnim = swingCurr
                } else {
                    player.swinging = false
                    player.oAttackAnim = 0f
                    player.attackAnim = 0f
                }

                poseStack.pushPose()
                poseStack.translate(
                    finalSx - cameraPos.x,
                    finalY - cameraPos.y,
                    finalSz - cameraPos.z,
                )
                playerRenderer.render(
                    player,
                    yaw[i],
                    partialTick,
                    poseStack,
                    tintBufferSource,
                    LightTexture.FULL_BRIGHT,
                )
                // Name tag above the silhouette — rendered into the
                // UNTINTED base buffer so the text reads as normal white,
                // not blacked out. Height matches vanilla offsets
                // (bbHeight + 0.5).
                val tagHeight = if (state.isSneaking) NAME_TAG_Y_CROUCH else NAME_TAG_Y_STAND
                renderSilhouetteNameTag(
                    poseStack, baseBufferSource, font,
                    cameraRot, state.name, tagHeight,
                )
                poseStack.popPose()
            }
        } finally {
            // Restore all snapshotted fields even if a renderer throws,
            // otherwise the local player would be stuck mid-swing or
            // crouched in their own view.
            crouchAccessor?.`enderkinesis$setCrouching`(savedCrouching)
            player.swinging = savedSwinging
            player.swingTime = savedSwingTime
            player.swingingArm = savedSwingingArm
            player.attackAnim = savedAttackAnim
            player.oAttackAnim = savedOAttackAnim
            player.yBodyRot = savedYBodyRot
            player.yBodyRotO = savedYBodyRotO
            player.yHeadRot = savedYHeadRot
            player.yHeadRotO = savedYHeadRotO

            // Flush our additions immediately so they actually draw in
            // this pass instead of pooling into the next flush boundary.
            baseBufferSource.endBatch()

            RenderSystem.setShaderFogStart(savedFogStart)
            RenderSystem.setShaderFogEnd(savedFogEnd)
        }
    }

    /** Top of the natural surface at column ([x], [z]) — sand, water, or
     *  tower brick — scanned from [SCAN_TOP_Y] down to [SEA_LEVEL_Y].
     *  Obsidian and crying obsidian (tendrils and rocks piercing the
     *  sand) are skipped so the silhouette doesn't perch on a tendril
     *  tip; the barrier ceiling is skipped because it's not real
     *  geometry; water is NOT skipped — over the ocean the silhouette
     *  stands on the water surface.
     *
     *  Returns `block.y + 1` capped to [SCAN_TOP_Y] so the silhouette
     *  can't clip into the barrier layer. When the column has no surface
     *  in range — typically an unloaded chunk reading as all air — falls
     *  back to `SEA_LEVEL_Y + 1`. */
    private fun findSandTopY(level: ClientLevel, x: Int, z: Int): Int {
        for (y in SCAN_TOP_Y downTo SEA_LEVEL_Y) {
            scratchPos.set(x, y, z)
            val state = level.getBlockState(scratchPos)
            if (state.isAir) continue
            if (state.`is`(Blocks.BARRIER)) continue
            // Dream-variant tendrils/rocks shouldn't pedestal the
            // silhouette — they pierce the sand and we want to land on
            // the sand below, not perch on a tip.
            if (state.`is`(EKBlocks.DREAM_OBSIDIAN.get())) continue
            if (state.`is`(EKBlocks.DREAM_CRYING_OBSIDIAN.get())) continue
            return Math.min(y + 1, SCAN_TOP_Y)
        }
        return SEA_LEVEL_Y + 1
    }

    /** Draws a vanilla-style billboard name tag at the current PoseStack
     *  origin, lifted by [yOffset]. Bypasses
     *  `EntityRenderer.renderNameTag`'s 64-block distance gate and team /
     *  visibility checks so the silhouettes' tags read at any range.
     *  Two passes: a faint SEE_THROUGH layer (visible through any
     *  geometry the silhouette is occluded by) and a full-opacity NORMAL
     *  layer for the unoccluded portion. */
    private fun renderSilhouetteNameTag(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        font: Font,
        cameraRotation: org.joml.Quaternionf,
        text: String,
        yOffset: Float,
    ) {
        poseStack.pushPose()
        poseStack.translate(0.0, yOffset.toDouble(), 0.0)
        poseStack.mulPose(cameraRotation)
        // Vanilla name-tag scale. Negative X/Y flip into the
        // PoseStack-after-camera-rotation orientation that puts +X right
        // and +Y down in the text plane.
        poseStack.scale(-0.025f, -0.025f, 0.025f)

        val matrix = poseStack.last().pose()
        val mc = Minecraft.getInstance()
        val bgAlpha = (mc.options.getBackgroundOpacity(0.25f) * 255f).toInt()
        val bgColor = bgAlpha shl 24
        val halfWidth = -font.width(text) / 2f
        val light = LightTexture.FULL_BRIGHT

        // First pass: faint through-walls layer (32-alpha white) so the
        // silhouette can be located even when something's between us.
        font.drawInBatch(
            text, halfWidth, 0f, 0x20FFFFFF, false,
            matrix, bufferSource, Font.DisplayMode.SEE_THROUGH, bgColor, light,
        )
        // Second pass: full-opacity in-front-only layer.
        font.drawInBatch(
            text, halfWidth, 0f, -1, false,
            matrix, bufferSource, Font.DisplayMode.NORMAL, 0, light,
        )
        poseStack.popPose()
    }

    /** Per-silhouette behavior state. Behaviors run in parallel but each
     *  on a sparse schedule (seconds-long gaps) so the silhouette mostly
     *  stands still and occasionally does *one* thing — not a
     *  choreographed multi-behavior loop. */
    private class SilhouetteState(seed: Long) {
        private val rng = Random(seed)
        private var ageTicks: Int = 0

        /** Random-length name shown on the silhouette's name tag. Picked
         *  once at construction so it doesn't flicker between frames. */
        val name: String = run {
            val len = 3 + rng.nextInt(10)              // 3..12 chars
            val sb = StringBuilder(len)
            for (k in 0 until len) {
                sb.append(NAME_CHARS[rng.nextInt(NAME_CHARS.size)])
            }
            sb.toString()
        }

        /** Tick when the current jump started; <0 means grounded. */
        private var jumpStartTick: Int = -1
        private var jumpDurationTicks: Int = 0
        private var nextJumpAt: Int

        // The "dance" is now a bursty thing: long stretches of standing
        // followed by a short flurry of toggles, instead of constant
        // metronomic flipping.
        private var sneakOn: Boolean = false
        private var inSneakBurst: Boolean = false
        private var sneakBurstUntil: Int = 0
        private var sneakNextToggleAt: Int = 0
        private var nextSneakBurstAt: Int

        // Random-walk style: pick a target offset, smoothly approach it,
        // hold it, eventually pick another. Reads as deliberate weight
        // shifts rather than a sine-wave oscillation.
        private var strafeTarget: Double
        private var strafeCurrent: Double = 0.0
        private var strafePrev: Double = 0.0
        private var nextStrafeChangeAt: Int

        /** Tick when the current swing started. Older than the swing
         *  duration means no swing currently visible. */
        private var lastSwingStartTick: Int = -1000
        private var swingDurationTicks: Int = 6
        private var nextSwingAt: Int

        // Shared scratch — swingProgress() returns prev+curr in this
        // array to avoid allocating a Pair per silhouette per frame.
        private val swingScratch = floatArrayOf(0f, 0f)

        init {
            // Stagger first-trigger times across the four silhouettes so
            // they aren't all firing on the same tick.
            nextJumpAt = 100 + rng.nextInt(300)            // 5-20s
            nextSneakBurstAt = 200 + rng.nextInt(400)      // 10-30s
            nextStrafeChangeAt = 40 + rng.nextInt(160)     // 2-10s
            nextSwingAt = 80 + rng.nextInt(240)            // 4-16s
            strafeTarget = (rng.nextDouble() - 0.5) * 2.0 * STRAFE_AMP_BLOCKS
        }

        fun tick() {
            ageTicks++

            // Jump cycle: rare trigger, brief airtime, long cooldown.
            if (jumpStartTick < 0 && ageTicks >= nextJumpAt) {
                jumpStartTick = ageTicks
                jumpDurationTicks = 10 + rng.nextInt(8)
            } else if (jumpStartTick >= 0 &&
                ageTicks - jumpStartTick >= jumpDurationTicks
            ) {
                jumpStartTick = -1
                nextJumpAt = ageTicks + 100 + rng.nextInt(300)  // 5-20s
            }

            // Sneak dance — burst-and-rest. During a burst, rapid toggles
            // every 3-10 ticks; outside a burst, fully still.
            if (inSneakBurst) {
                if (ageTicks >= sneakBurstUntil) {
                    inSneakBurst = false
                    sneakOn = false
                    nextSneakBurstAt = ageTicks + 200 + rng.nextInt(400)  // 10-30s rest
                } else if (ageTicks >= sneakNextToggleAt) {
                    sneakOn = !sneakOn
                    sneakNextToggleAt = ageTicks + 3 + rng.nextInt(8)
                }
            } else if (ageTicks >= nextSneakBurstAt) {
                inSneakBurst = true
                sneakBurstUntil = ageTicks + 20 + rng.nextInt(60)         // 1-4s burst
                sneakNextToggleAt = ageTicks
            }

            // Strafe — random-walk. Snapshot prev for sub-tick lerp, ease
            // current toward the target, occasionally pick a new target.
            strafePrev = strafeCurrent
            if (ageTicks >= nextStrafeChangeAt) {
                strafeTarget = (rng.nextDouble() - 0.5) * 2.0 * STRAFE_AMP_BLOCKS
                nextStrafeChangeAt = ageTicks + 40 + rng.nextInt(200)     // 2-12s
            }
            strafeCurrent += (strafeTarget - strafeCurrent) * STRAFE_APPROACH_RATE

            // Wave — rare arm swing. Duration matches vanilla (5-7 ticks).
            if (ageTicks >= nextSwingAt) {
                lastSwingStartTick = ageTicks
                swingDurationTicks = 5 + rng.nextInt(3)
                nextSwingAt = ageTicks + 80 + rng.nextInt(240)            // 4-16s
            }
        }

        /** Parabolic jump offset; 0 when grounded. */
        fun jumpYOffset(partialTick: Float): Double {
            if (jumpStartTick < 0) return 0.0
            val now = ageTicks + partialTick.toDouble()
            val sinceStart = now - jumpStartTick
            val t = sinceStart / jumpDurationTicks
            if (t < 0.0 || t > 1.0) return 0.0
            return JUMP_PEAK_BLOCKS * 4.0 * t * (1.0 - t)
        }

        val isSneaking: Boolean get() = sneakOn

        /** Sub-tick interpolated strafe offset. Linear between the
         *  previous tick's value and the current tick's value, so the
         *  smooth approach reads smoothly per-frame too. */
        fun strafeOffset(partialTick: Float): Double =
            Mth.lerp(partialTick.toDouble(), strafePrev, strafeCurrent)

        /** Writes oAttackAnim into `[0]` and attackAnim into `[1]` of the
         *  shared scratch array, then returns it. Both 0 when no swing
         *  is currently visible. The model interpolates oAttackAnim →
         *  attackAnim by partialTick when computing limb rotation. */
        fun swingProgress(partialTick: Float): FloatArray {
            val now = ageTicks + partialTick.toDouble()
            val sinceStart = now - lastSwingStartTick
            if (sinceStart < 0 || sinceStart > swingDurationTicks) {
                swingScratch[0] = 0f
                swingScratch[1] = 0f
                return swingScratch
            }
            val curr = (sinceStart / swingDurationTicks).toFloat()
                .coerceIn(0f, 1f)
            val prevSince = sinceStart - 1.0
            val prev = if (prevSince < 0.0) 0f
                else (prevSince / swingDurationTicks).toFloat().coerceIn(0f, 1f)
            swingScratch[0] = prev
            swingScratch[1] = curr
            return swingScratch
        }
    }

    /** Wraps another buffer source and returns black-tinted vertex
     *  consumers. Cached per RenderType because PlayerRenderer pulls the
     *  same RenderType (entity_translucent / entity_cutout_no_cull / armor
     *  cutouts / etc.) repeatedly across layers. */
    private class BlackTintBufferSource(
        private val delegate: MultiBufferSource,
    ) : MultiBufferSource {
        private val cache = HashMap<RenderType, BlackTintVertexConsumer>(8)
        override fun getBuffer(renderType: RenderType): VertexConsumer {
            val existing = cache[renderType]
            val fresh = delegate.getBuffer(renderType)
            if (existing != null && existing.delegate === fresh) return existing
            val wrapper = BlackTintVertexConsumer(fresh)
            cache[renderType] = wrapper
            return wrapper
        }
    }

    /** Forwards every call to [delegate] except [color] and [defaultColor],
     *  which are pinned to opaque black. Vanilla entity fragment shaders do
     *  `tex * vertexColor * ColorModulator * lightMap`, so an all-zero
     *  vertex color produces solid black RGB while alpha cutout still
     *  honours the skin texture's transparency.
     *
     *  Chained calls must return `this` (not the delegate) so subsequent
     *  links in the chain stay routed through this wrapper. */
    private class BlackTintVertexConsumer(
        val delegate: VertexConsumer,
    ) : VertexConsumer {

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
            delegate.vertex(x, y, z)
            return this
        }

        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer {
            delegate.color(0, 0, 0, 255)
            return this
        }

        override fun uv(u: Float, v: Float): VertexConsumer {
            delegate.uv(u, v)
            return this
        }

        override fun overlayCoords(u: Int, v: Int): VertexConsumer {
            delegate.overlayCoords(u, v)
            return this
        }

        override fun uv2(u: Int, v: Int): VertexConsumer {
            delegate.uv2(u, v)
            return this
        }

        override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
            delegate.normal(x, y, z)
            return this
        }

        override fun endVertex() {
            delegate.endVertex()
        }

        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) {
            delegate.defaultColor(0, 0, 0, 255)
        }

        override fun unsetDefaultColor() {
            delegate.unsetDefaultColor()
        }
    }
}
