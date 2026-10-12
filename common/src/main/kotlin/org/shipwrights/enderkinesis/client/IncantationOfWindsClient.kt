package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKParticles
import kotlin.math.cos
import kotlin.math.sin

/** Client visuals for the held Incantation of Winds.
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
        val item = EKItems.INCANTATION_OF_WINDS.get()
        val localFirstPerson = mc.options.cameraType.isFirstPerson

        for (player in level.players()) {
            val isLocalFp = player === mc.player && localFirstPerson
            for (hand in InteractionHand.values()) {
                if (!player.getItemInHand(hand).`is`(item)) continue
                if (isLocalFp) continue // handled by the hand-space motes below
                // The idle shimmer yields to the blast while casting.
                if (isCastingWinds(player, hand)) continue
                spawnWorldHandDust(player, hand)
            }
        }

        tickFirstPersonMotes(mc, localFirstPerson)
    }

    /** Third-person / remote hand dust — a world particle at the humanoid
     *  model's hanging hand, randomised in a wide radius around it. */
    private fun spawnWorldHandDust(player: Player, hand: InteractionHand) {
        val level = player.level()
        val rnd = level.random
        if (rnd.nextFloat() >= HAND_DUST_CHANCE) return
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val sideSign = if (arm == HumanoidArm.RIGHT) 1.0 else -1.0
        val yawRad = Math.toRadians(player.yBodyRot.toDouble())
        val right = Vec3(-cos(yawRad), 0.0, -sin(yawRad))
        level.addParticle(
            EKParticles.windHandDust(),
            player.x + right.x * 0.38 * sideSign +
                (rnd.nextDouble() - 0.5) * 2.0 * SPAWN_RADIUS_XZ,
            player.y + 0.85 + (rnd.nextDouble() - 0.5) * 2.0 * SPAWN_RADIUS_Y,
            player.z + right.z * 0.38 * sideSign +
                (rnd.nextDouble() - 0.5) * 2.0 * SPAWN_RADIUS_XZ,
            (rnd.nextDouble() - 0.5) * 0.01,
            HAND_DUST_RISE,
            (rnd.nextDouble() - 0.5) * 0.01,
        )
    }

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
        val lifetime: Int,
        var age: Int = 0,
    )

    /** Indexed by InteractionHand.ordinal. */
    private val fpMotes = arrayOf(ArrayList<Mote>(), ArrayList<Mote>())

    private fun tickFirstPersonMotes(mc: Minecraft, localFirstPerson: Boolean) {
        val player = mc.player
        val item = EKItems.INCANTATION_OF_WINDS.get()
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
            if (!player.getItemInHand(hand).`is`(item)) continue
            // The idle shimmer yields to the blast while casting; existing
            // motes age out in place.
            if (isCastingWinds(player, hand)) continue
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
        combinedLight: Int,
    ) {
        var drewAny = false
        for (hand in InteractionHand.values()) {
            val list = fpMotes[hand.ordinal]
            if (list.isEmpty()) continue
            val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
            val sideSign = if (arm == HumanoidArm.RIGHT) 1.0 else -1.0

            poseStack.pushPose()
            if (isCastingWinds(player, hand)) {
                // Same composition order as the arm mixin: tilt first,
                // then translate — keeps the dust glued to the tilting hand.
                poseStack.mulPose(
                    com.mojang.math.Axis.XP.rotationDegrees(castTiltDegrees(player, partialTicks)),
                )
            }
            poseStack.translate(FP_SIDE * sideSign, FP_DOWN, FP_FORWARD)
            if (isCastingWinds(player, hand)) {
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
                moteVertex(consumer, pose, normal, px - s, py - s, pz, 0f, 1f, alpha, combinedLight)
                moteVertex(consumer, pose, normal, px + s, py - s, pz, 1f, 1f, alpha, combinedLight)
                moteVertex(consumer, pose, normal, px + s, py + s, pz, 1f, 0f, alpha, combinedLight)
                moteVertex(consumer, pose, normal, px - s, py + s, pz, 0f, 0f, alpha, combinedLight)
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
        alpha: Float,
        @Suppress("UNUSED_PARAMETER") light: Int,
    ) {
        consumer.vertex(pose, x, y, z)
            .color(1.0f, 0.92f, 0.55f, alpha)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            // Emissive: full-bright lightmap regardless of world light —
            // the incantation's dust glows in the dark.
            .uv2(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)
            .normal(normal, 0f, 0f, 1f)
            .endVertex()
    }

    // --------------------------------------------------- cast-pose curve

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
