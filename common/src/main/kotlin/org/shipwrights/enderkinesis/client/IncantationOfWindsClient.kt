package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.item.IncantationItem
import org.shipwrights.enderkinesis.item.IncantationOfTheAbyssItem
import org.shipwrights.enderkinesis.item.IncantationOfTheRiverItem
import org.shipwrights.enderkinesis.item.IncantationOfWindsItem
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKParticles
import kotlin.math.cos
import kotlin.math.sin

/** Client hand visuals for every held [IncantationItem], and the Incantation of Winds stream.
 *
 *  Two particle paths:
 *  - **First person (local player)**: motes live in HAND SPACE, not world
 *    space — simulated here and drawn at the end of the first-person hand
 *    pass (see [org.shipwrights.enderkinesis.mixin.ItemInHandRendererWindsEmptyHandMixin]),
 *    so they depth-test against the arm geometry (drift behind fingers,
 *    in front of the wrist) and ride the cast animation with the hand.
 *  - **Third person / remote players**: regular world particles at the
 *    model's hanging-hand position.
 *
 *  Also owns the cast-pose curve (hand pushes forward and oscillates
 *  gently, bow-full-draw style) that the arm-render mixin applies. */
object IncantationOfWindsClient {

    // ---------------------------------------------------------------- tick

    fun tick(mc: Minecraft) {
        val level = mc.level ?: return
        if (mc.isPaused) return
        IncantationConeVolume.tick(level)
        AbyssDepthVolume.tick(level)
        RiverWasher.tick(level)
        val localFirstPerson = mc.options.cameraType.isFirstPerson

        for (player in level.players()) {
            val isLocalFp = player === mc.player && localFirstPerson
            for (hand in InteractionHand.values()) {
                val item = player.getItemInHand(hand).item as? IncantationItem ?: continue
                if (isCastingIncantation(player, hand)) {
                    when (item) {
                        is IncantationOfWindsItem -> spawnStream(player)
                        is IncantationOfTheRiverItem -> RiverJetClient.spawn(player)
                        is IncantationOfTheAbyssItem -> AbyssDepthClient.spawn(player)
                    }
                    IncantationSounds.loop(player, item)
                    continue
                }
                if (isLocalFp) continue // handled by the hand-space motes below
                spawnWorldHandDust(player, hand, item.handColour)
            }
        }

        tickFirstPersonMotes(mc, localFirstPerson)
    }

    /** Third-person / remote hand dust — a world particle at the humanoid
     *  model's hanging hand, randomised in a wide radius around it. */
    private fun spawnWorldHandDust(player: Player, hand: InteractionHand, colour: Int) {
        val level = player.level()
        val rnd = level.random
        if (rnd.nextFloat() >= HAND_DUST_CHANCE) return
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val sideSign = if (arm == HumanoidArm.RIGHT) 1.0 else -1.0
        val yawRad = Math.toRadians(player.yBodyRot.toDouble())
        val right = Vec3(-cos(yawRad), 0.0, -sin(yawRad))
        Minecraft.getInstance().particleEngine.createParticle(
            EKParticles.windHandDust(),
            player.x + right.x * 0.38 * sideSign +
                (rnd.nextDouble() - 0.5) * 2.0 * SPAWN_RADIUS_XZ,
            player.y + 0.85 + (rnd.nextDouble() - 0.5) * 2.0 * SPAWN_RADIUS_Y,
            player.z + right.z * 0.38 * sideSign +
                (rnd.nextDouble() - 0.5) * 2.0 * SPAWN_RADIUS_XZ,
            (rnd.nextDouble() - 0.5) * 0.01,
            HAND_DUST_RISE,
            (rnd.nextDouble() - 0.5) * 0.01,
        )?.setColor(red(colour), green(colour), blue(colour))
    }

    private fun red(rgb: Int) = (rgb shr 16 and 0xFF) / 255f
    private fun green(rgb: Int) = (rgb shr 8 and 0xFF) / 255f
    private fun blue(rgb: Int) = (rgb and 0xFF) / 255f

    /** Flames-style stream: wisps and glitter motes, each sent to a random end point in the cone. */
    private fun spawnStream(player: Player) {
        val level = player.level()
        val rnd = level.random
        val palm = castingPalm(player, 1f)
        val hand = palm.position
        val normal = palm.normal
        val axis = streamAxis(player, hand, 1f)
        val u = axis.cross(if (kotlin.math.abs(axis.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)).normalize()
        val w = axis.cross(u)
        val spread = IncantationOfWindsItem.TAN_HALF_ANGLE
        val range = IncantationOfWindsItem.RANGE
        val engine = Minecraft.getInstance().particleEngine

        fun coneDirection(radial: Double): Vec3 {
            val theta = rnd.nextDouble() * 2.0 * Math.PI
            val c = cos(theta) * radial * spread
            val s = sin(theta) * radial * spread
            return Vec3(axis.x + u.x * c + w.x * s, axis.y + u.y * c + w.y * s, axis.z + u.z * c + w.z * s)
        }
        fun jitter() = (rnd.nextDouble() - 0.5) * HAND_JITTER

        repeat(WISPS_PER_TICK) {
            val travel = coneDirection(kotlin.math.sqrt(rnd.nextDouble())).scale(range * (0.8 + rnd.nextDouble() * 0.25))
            // Made through the engine, not level.addParticle, to get the particle back for its launch.
            val wisp = engine.createParticle(
                EKParticles.windWisp(),
                hand.x + jitter(), hand.y + jitter(), hand.z + jitter(),
                travel.x, travel.y, travel.z,
            )
            (wisp as? WindConeParticle)?.launch(
                normal.x * WISP_LAUNCH_SPEED,
                normal.y * WISP_LAUNCH_SPEED,
                normal.z * WISP_LAUNCH_SPEED,
            )
        }
        // Gust motes take a true velocity, not a travel vector.
        repeat(SPARKLES_PER_TICK) {
            val velocity = coneDirection(rnd.nextDouble()).scale(SPARKLE_SPEED).add(normal.scale(SPARKLE_LAUNCH_SPEED))
            level.addParticle(
                EKParticles.windGust(),
                hand.x + jitter(), hand.y + jitter(), hand.z + jitter(),
                velocity.x, velocity.y, velocity.z,
            )
        }
    }

    /** Centre and outward normal of the casting palm: the end face of the blocky arm. */
    class Palm(val position: Vec3, val normal: Vec3)

    /** The first-person hand pass uses a fixed 70° FOV, so a world point that covers the drawn palm
     *  needs the world FOV too. Call each frame with the level projection. */
    fun noteWorldProjection(projection: Matrix4f) {
        worldTanHalfFov = 1.0 / projection.m11()
    }

    private var worldTanHalfFov = HAND_TAN_HALF_FOV

    fun castingPalm(player: Player, partialTick: Float): Palm {
        val mc = Minecraft.getInstance()
        val arm = if (player.usedItemHand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val side = if (arm == HumanoidArm.RIGHT) 1.0 else -1.0
        return if (isViewedFromOwnEyes(player)) {
            firstPersonPalm(player, side, partialTick)
        } else {
            WindPalmCapture.palm(player) ?: modelPalm(player, side, partialTick)
        }
    }

    /** False for a third-person view, and for a camera that left the body (e.g. Freecam) while the camera
     *  type stays first person. */
    fun isViewedFromOwnEyes(player: Player): Boolean {
        val mc = Minecraft.getInstance()
        return player === mc.cameraEntity && !mc.gameRenderer.mainCamera.isDetached
    }

    /** The palm as drawn by the first-person cast pose (tilt, then offset), moved from hand-pass camera space to world. */
    private fun firstPersonPalm(player: Player, side: Double, partialTick: Float): Palm {
        val tilt = Math.toRadians(castTiltDegrees(player, partialTick).toDouble())
        val c = cos(tilt)
        val s = sin(tilt)
        val py = FP_PALM_Y + castOscillation(player, partialTick)
        val pz = FP_PALM_Z - castForwardOffset(player, partialTick)
        val k = worldTanHalfFov / HAND_TAN_HALF_FOV
        val camX = FP_PALM_X * side * k
        val camY = (py * c - pz * s) * k
        val camZ = py * s + pz * c
        val nX = FP_PALM_NORMAL_X * side * k
        val nY = (FP_PALM_NORMAL_Y * c - FP_PALM_NORMAL_Z * s) * k
        val nZ = FP_PALM_NORMAL_Y * s + FP_PALM_NORMAL_Z * c

        val fwd = player.getViewVector(partialTick)
        val yawRad = Math.toRadians(player.getViewYRot(partialTick).toDouble())
        val right = Vec3(-cos(yawRad), 0.0, -sin(yawRad))
        val up = right.cross(fwd)
        val position = player.getEyePosition(partialTick)
            .add(right.scale(camX))
            .add(up.scale(camY))
            .add(fwd.scale(-camZ))
        val normal = right.scale(nX).add(up.scale(nY)).add(fwd.scale(-nZ)).normalize()
        return Palm(position, normal)
    }

    /** Estimate of the third-person palm, for a player the level pass did not draw. */
    private fun modelPalm(player: Player, side: Double, partialTick: Float): Palm {
        val t = player.ticksUsingItem + partialTick
        // The pose lerps from the hanging arm (xRot 0) by the cast progress.
        val armXRot = (TP_CAST_ARM_X_ROT + sin((t * CAST_OSC_FREQ).toDouble()) * TP_CAST_OSC_RAD) *
            castProgress(player, partialTick)
        val ca = cos(armXRot)
        val sa = sin(armXRot)
        // LivingEntityRenderer: rotate by 180 - body yaw, scale (-1, -1, 1), then the player scale.
        val lx = side * TP_PALM_SIDE * PLAYER_MODEL_SCALE
        val ly = (MODEL_ORIGIN_Y - TP_SHOULDER_Y - TP_ARM_LENGTH * ca) * PLAYER_MODEL_SCALE
        val lz = TP_ARM_LENGTH * sa * PLAYER_MODEL_SCALE

        val yawRad = Math.toRadians(Mth.lerp(partialTick, player.yBodyRotO, player.yBodyRot).toDouble())
        val right = Vec3(-cos(yawRad), 0.0, -sin(yawRad))
        val forward = Vec3(-sin(yawRad), 0.0, cos(yawRad))
        val position = player.getPosition(partialTick)
            .add(right.scale(lx))
            .add(0.0, ly, 0.0)
            .add(forward.scale(-lz))
        val normal = forward.scale(-sa).add(0.0, -ca, 0.0)
        return Palm(position, normal)
    }

    /** Aims from the hand at the cone's far centre, so the stream converges on the crosshair. */
    fun streamAxis(player: Player, hand: Vec3, partialTick: Float): Vec3 =
        player.getEyePosition(partialTick)
            .add(player.getViewVector(partialTick).scale(IncantationOfWindsItem.RANGE))
            .subtract(hand)
            .normalize()

    // ------------------------------------------- first-person mote system

    /** A dust mote orbiting the hand's vertical axis. Stored in polar form
     *  around the hand anchor: [angle]/[radius] give the XZ position,
     *  [y] rises with [vy], [spin] advances the angle each tick. */
    private class Mote(
        var angle: Double,
        val radius: Double,
        val spin: Double,
        var y: Double,
        val vy: Double,
        val size: Float,
        val smokeMode: Boolean,
        val colour: Int,
        val lifetime: Int,
        var age: Int = 0,
    )

    /** Indexed by InteractionHand.ordinal. */
    private val fpMotes = arrayOf(ArrayList<Mote>(), ArrayList<Mote>())

    private fun tickFirstPersonMotes(mc: Minecraft, localFirstPerson: Boolean) {
        val player = mc.player
        for (hand in InteractionHand.values()) {
            val list = fpMotes[hand.ordinal]
            // Update existing motes even after the item is put away — they
            // age out in place rather than vanishing.
            val it = list.iterator()
            while (it.hasNext()) {
                val m = it.next()
                m.angle += m.spin
                m.y += m.vy
                if (++m.age >= m.lifetime) it.remove()
            }
            if (player == null || !localFirstPerson) continue
            val item = player.getItemInHand(hand).item as? IncantationItem ?: continue
            // The idle shimmer yields to the blast while casting; existing
            // motes age out in place.
            if (isCastingIncantation(player, hand)) continue
            val rnd = player.random
            if (rnd.nextFloat() >= HAND_DUST_CHANCE) continue
            val smoke = rnd.nextFloat() < 0.5f
            list.add(
                Mote(
                    angle = rnd.nextDouble() * 2.0 * Math.PI,
                    radius = SPAWN_RADIUS_MIN + rnd.nextDouble() * (SPAWN_RADIUS_MAX - SPAWN_RADIUS_MIN),
                    spin = SPIN_MIN + rnd.nextDouble() * (SPIN_MAX - SPIN_MIN),
                    y = (rnd.nextDouble() - 0.5) * 2.0 * SPAWN_HALF_HEIGHT,
                    vy = HAND_DUST_RISE,
                    size = if (smoke) {
                        0.020f + rnd.nextFloat() * 0.015f
                    } else {
                        0.012f + rnd.nextFloat() * 0.016f
                    },
                    smokeMode = smoke,
                    colour = item.handColour,
                    lifetime = HAND_DUST_LIFETIME_MIN + rnd.nextInt(HAND_DUST_LIFETIME_VAR),
                ),
            )
        }
    }

    /** Called from the TAIL of `ItemInHandRenderer.renderHandsWithItems` —
     *  the arm has already been drawn into this pass's depth buffer, so
     *  these quads get true depth against the hand. `poseStack` is camera
     *  space (bobbing applied, −Z into the screen). */
    @JvmStatic
    fun renderFirstPersonMotes(
        partialTicks: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource.BufferSource,
        player: LocalPlayer,
    ) {
        var drewAny = false
        for (hand in InteractionHand.values()) {
            val list = fpMotes[hand.ordinal]
            if (list.isEmpty()) continue
            val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
            val sideSign = if (arm == HumanoidArm.RIGHT) 1.0 else -1.0

            poseStack.pushPose()
            if (isCastingIncantation(player, hand)) {
                // Same composition order as the arm mixin: tilt first,
                // then translate — keeps the dust glued to the tilting hand.
                poseStack.mulPose(
                    com.mojang.math.Axis.XP.rotationDegrees(castTiltDegrees(player, partialTicks)),
                )
            }
            poseStack.translate(FP_SIDE * sideSign, FP_DOWN, FP_FORWARD)
            if (isCastingIncantation(player, hand)) {
                // The tilt rotates about the camera origin, so the RENDERED
                // hand (whose geometry reaches z≈−0.9) swings further than
                // this anchor point does — the FP_CAST_* extras close that
                // gap so the dust stays on the visible fingertips.
                val p = castProgress(player, partialTicks)
                poseStack.translate(
                    FP_CAST_SIDE * sideSign * p,
                    castOscillation(player, partialTicks) + FP_CAST_DOWN * p,
                    -castForwardOffset(player, partialTicks) + FP_CAST_FORWARD * p,
                )
            }
            val pose = poseStack.last().pose()
            val normal = poseStack.last().normal()
            drewAny = true
            for (m in list) {
                val theta = m.angle + m.spin * partialTicks
                val px = (cos(theta) * m.radius).toFloat()
                val py = (m.y + m.vy * partialTicks).toFloat()
                val pz = (sin(theta) * m.radius).toFloat()
                val ageF = (m.age + partialTicks) / m.lifetime
                // No fade-in; fade out over the last 30% of life. Smoke
                // reads softer, glitter brighter — same split as sselith dust.
                val base = if (m.smokeMode) SMOKE_ALPHA else GLITTER_ALPHA
                val alpha = if (ageF > 0.7f) base * (1.0f - ageF) / 0.3f else base
                val frame = (ageF * 8f).toInt().coerceIn(0, 7)
                val consumer = buffer.getBuffer(
                    MOTE_RENDER_TYPES[(if (m.smokeMode) 0 else 8) + frame],
                )
                val s = m.size
                // Camera space: an XY-plane quad faces the viewer.
                val c = m.colour
                moteVertex(consumer, pose, normal, px - s, py - s, pz, 0f, 1f, c, alpha)
                moteVertex(consumer, pose, normal, px + s, py - s, pz, 1f, 1f, c, alpha)
                moteVertex(consumer, pose, normal, px + s, py + s, pz, 1f, 0f, c, alpha)
                moteVertex(consumer, pose, normal, px - s, py + s, pz, 0f, 0f, c, alpha)
            }
            poseStack.popPose()
        }
        // Our TAIL hook runs AFTER renderHandsWithItems' own endBatch(), so
        // without an explicit flush these quads would ride in the shared
        // buffer into the GUI pass and draw with the wrong projection
        // (i.e. never visibly). Flush now, while the hand pass's matrices
        // and light layer are still active.
        if (drewAny) {
            buffer.endBatch()
        }
    }

    private fun moteVertex(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: org.joml.Matrix4f,
        normal: org.joml.Matrix3f,
        x: Float, y: Float, z: Float,
        u: Float, v: Float,
        colour: Int,
        alpha: Float,
    ) {
        consumer.vertex(pose, x, y, z)
            .color(red(colour), green(colour), blue(colour), alpha)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            // Emissive: full-bright lightmap regardless of world light —
            // the incantation's dust glows in the dark.
            .uv2(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)
            .normal(normal, 0f, 0f, 1f)
            .endVertex()
    }

    // --------------------------------------------------- cast-pose curve

    /** True while [hand] casts any incantation; drives the shared cast pose, bare arm and palm capture. */
    @JvmStatic
    fun isCastingIncantation(player: Player, hand: InteractionHand): Boolean =
        player.isUsingItem &&
            player.usedItemHand == hand &&
            player.getItemInHand(hand).item is IncantationItem

    @JvmStatic
    fun isCastingWinds(player: Player, hand: InteractionHand): Boolean =
        player.isUsingItem &&
            player.usedItemHand == hand &&
            player.getItemInHand(hand).`is`(EKItems.INCANTATION_OF_WINDS.get())

    /** 0 → [CAST_FORWARD_MAX] over the first [CAST_RAMP_TICKS] of the cast,
     *  smoothstepped — the hand eases forward like a bow reaching full draw. */
    @JvmStatic
    fun castForwardOffset(player: Player, partialTicks: Float): Double {
        val s = castProgress(player, partialTicks)
        return CAST_FORWARD_MAX * s
    }

    /** Vertical cast offset: gentle sine oscillation plus a rise that
     *  compensates the on-screen dip the tilt causes (the tilt rotates
     *  about the camera origin, swinging the whole arm lower). The rise
     *  ramps on sqrt(progress) — deliberately AHEAD of the tilt's
     *  smoothstep — so the hand never sinks during the right-click
     *  ramp-in; it lifts first, then tips. */
    @JvmStatic
    fun castOscillation(player: Player, partialTicks: Float): Double {
        val t = player.ticksUsingItem + partialTicks
        val progress = castProgress(player, partialTicks)
        return sin((t * CAST_OSC_FREQ).toDouble()) * CAST_OSC_AMPLITUDE * progress +
            CAST_RAISE_MAX * kotlin.math.sqrt(progress)
    }

    /** Downward pitch of the casting hand, degrees — ramps in with the
     *  same smoothstep as the forward push. Negative: in the first-person
     *  hand pass, −X rotation is the one that pitches the hand downward. */
    @JvmStatic
    fun castTiltDegrees(player: Player, partialTicks: Float): Float =
        (-CAST_TILT_DEG * castProgress(player, partialTicks)).toFloat()

    /** Third-person cast pose: raises the casting arm up-and-forward and
     *  tilts it on the SAME sine the first-person hand oscillates with.
     *  Lerped by the cast ramp so the arm eases from wherever the walk
     *  cycle left it instead of snapping. */
    @JvmStatic
    fun applyThirdPersonCastPose(
        arm: net.minecraft.client.model.geom.ModelPart,
        player: Player,
        partialTicks: Float,
    ) {
        val progress = castProgress(player, partialTicks).toFloat()
        if (progress <= 0f) return
        val t = player.ticksUsingItem + partialTicks
        val osc = sin((t * CAST_OSC_FREQ).toDouble()).toFloat() * TP_CAST_OSC_RAD
        arm.xRot = net.minecraft.util.Mth.lerp(progress, arm.xRot, TP_CAST_ARM_X_ROT + osc)
        arm.yRot = net.minecraft.util.Mth.lerp(progress, arm.yRot, 0f)
        arm.zRot = net.minecraft.util.Mth.lerp(progress, arm.zRot, 0f)
    }

    private fun castProgress(player: Player, partialTicks: Float): Double {
        val t = ((player.ticksUsingItem + partialTicks) / CAST_RAMP_TICKS).coerceIn(0f, 1f)
        return (t * t * (3.0f - 2.0f * t)).toDouble()
    }

    // -------------------------------------------------------------- tuning

    private const val WISPS_PER_TICK = 6
    private const val SPARKLES_PER_TICK = 2

    /** Gust mote speed along the cone, blocks/tick; with its ~17-tick life it reaches about [IncantationOfWindsItem.RANGE]. */
    private const val SPARKLE_SPEED = 1.4

    private const val HAND_JITTER = 0.15

    /** Start speed along the palm normal, blocks/tick. Wisps lose theirs; gust motes keep it. */
    private const val WISP_LAUNCH_SPEED = 0.5
    private const val SPARKLE_LAUNCH_SPEED = 0.3

    /** tan(35°): the hand pass always draws at 70° FOV. */
    private const val HAND_TAN_HALF_FOV = 0.7002075382097097

    /** Right-arm end-face centre and normal in hand-pass camera space before the cast pose, from vanilla
     *  renderPlayerArm (no swing, fully equipped) and the PlayerModel arm (wide skin, zRot 0.1). */
    private const val FP_PALM_X = 0.755
    private const val FP_PALM_Y = -0.438
    private const val FP_PALM_Z = -1.095
    private const val FP_PALM_NORMAL_X = 0.369
    private const val FP_PALM_NORMAL_Y = 0.541
    private const val FP_PALM_NORMAL_Z = -0.756

    /** Wide-skin PlayerModel arm, blocks: shoulder pivot height, pivot to end face, palm off the body centre line. */
    private const val TP_SHOULDER_Y = 2.0 / 16.0
    private const val TP_ARM_LENGTH = 10.0 / 16.0
    private const val TP_PALM_SIDE = 6.0 / 16.0
    private const val MODEL_ORIGIN_Y = 1.501
    private const val PLAYER_MODEL_SCALE = 0.9375

    /** Per-tick spawn chance per held hand — 0.7 ≈ 1.4× the old 1-in-2 rate. */
    private const val HAND_DUST_CHANCE = 0.7f

    /** Mote lifetime range, ticks. */
    private const val HAND_DUST_LIFETIME_MIN = 14
    private const val HAND_DUST_LIFETIME_VAR = 8

    /** Upward drift, blocks/tick. */
    private const val HAND_DUST_RISE = 0.02

    /** Tight orbital spawn band around the hand's vertical axis. */
    private const val SPAWN_RADIUS_MIN = 0.03
    private const val SPAWN_RADIUS_MAX = 0.08
    private const val SPAWN_HALF_HEIGHT = 0.04

    /** Orbit speed around the hand axis, radians/tick — one revolution in
     *  roughly 20–35 ticks, all motes turning the same direction. */
    private const val SPIN_MIN = 0.18
    private const val SPIN_MAX = 0.30

    /** Third-person world-particle scatter (kept matching the FP band). */
    private const val SPAWN_RADIUS_XZ = 0.08
    private const val SPAWN_RADIUS_Y = 0.04

    /** First-person hand anchor in camera space (−Z into the screen). */
    private const val FP_SIDE = 0.32
    private const val FP_DOWN = -0.28
    private const val FP_FORWARD = -0.55

    /** Cast animation: forward push distance, ramp length, oscillation,
     *  downward pitch. */
    private const val CAST_FORWARD_MAX = 0.30
    private const val CAST_RAMP_TICKS = 10f
    private const val CAST_OSC_FREQ = 0.35f
    private const val CAST_OSC_AMPLITUDE = 0.035
    private const val CAST_TILT_DEG = 20.0

    /** Counteracts the arm's on-screen drop from the 20° origin-rotation
     *  tilt. Sized past the pure-geometry estimate (~0.21 at the wrist,
     *  ~0.31 at the fingertips) so the cast reads as the hand lifting
     *  INTO the gale, not merely holding level. */
    private const val CAST_RAISE_MAX = 0.40

    /** Third-person cast arm: raised up-and-forward (0 = hanging,
     *  −π/2 = level forward; −1.75 ≈ 10° above horizontal), with the
     *  oscillation mapped to rotation. */
    private const val TP_CAST_ARM_X_ROT = -1.75f
    private const val TP_CAST_OSC_RAD = 0.09f

    /** First-person cast anchor correction (applied × ramp progress) —
     *  closes the gap between the anchor point's origin-rotation swing
     *  and the rendered fingertips' larger one. */
    private const val FP_CAST_SIDE = 0.22
    private const val FP_CAST_DOWN = -0.19
    private const val FP_CAST_FORWARD = -0.30

    /** Same alpha split as sselith dust: smoke soft, glitter bright. */
    private const val SMOKE_ALPHA = 0.45f
    private const val GLITTER_ALPHA = 0.9f

    /** The full sselith-dust sprite family (8 generic smoke + 8 glitter),
     *  bound as standalone textures so the hand pass needs no particle-
     *  atlas access. Index = smoke ? frame : 8 + frame — matches the
     *  age-driven frame cycle in [renderFirstPersonMotes].
     *
     *  The EMISSIVE entity type: plain `entityTranslucent` applies the
     *  directional Light0/Light1 diffuse term against the vertex normal,
     *  which dims flat camera-facing quads — the emissive variant skips
     *  both that and the lightmap, giving true fullbright. */
    private val MOTE_RENDER_TYPES: Array<RenderType> = Array(16) { i ->
        val family = if (i < 8) "generic" else "glitter"
        RenderType.entityTranslucentEmissive(
            ResourceLocation("minecraft", "textures/particle/${family}_${i % 8}.png"),
        )
    }
}
