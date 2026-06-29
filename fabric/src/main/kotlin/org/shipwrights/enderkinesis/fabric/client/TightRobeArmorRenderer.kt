package org.shipwrights.enderkinesis.fabric.client

import com.mojang.blaze3d.vertex.PoseStack
import dev.architectury.event.CompoundEventResult
import dev.architectury.event.events.common.InteractionEvent
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.ItemRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.client.model.TightRobeArmorModel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fabric [ArmorRenderer] for the robe items. Mirrors `HumanoidArmorLayer`'s slot →
 * layer mapping (LEGS → `_layer_2`, otherwise `_layer_1`) but pulls textures from
 * `enderkinesis:textures/models/armor/{materialName}_layer_{1|2}.png`. Swaps in
 * [TightRobeArmorModel] baked with `CubeDeformation(0.15f)` for the tighter silhouette.
 */
class TightRobeArmorRenderer : ArmorRenderer {

    // Lazy bakes — the EntityModels manager isn't ready at constructor time.
    private var innerModel: HumanoidModel<LivingEntity>? = null
    private var outerModel: HumanoidModel<LivingEntity>? = null
    /** Standalone witch-hat model (head root + brim + hat_1/2/3 cone stack). */
    private var witchHatRoot: ModelPart? = null

    /** Per-entity jiggle-physics state. Each part (hood pieces + the three chest pearls
     *  + the witch-hat brim and cone segments) is an independent damped harmonic
     *  oscillator kicked by impulses derived from the wearer's motion. Keyed by entity
     *  UUID so multiplayer works without state cross-talk. */
    private val flop2Jiggle: MutableMap<UUID, JiggleState> = HashMap()
    private val flop1Jiggle: MutableMap<UUID, JiggleState> = HashMap()
    private val pearlCentreJiggle: MutableMap<UUID, JiggleState> = HashMap()
    private val pearlLeftJiggle: MutableMap<UUID, JiggleState> = HashMap()
    private val pearlRightJiggle: MutableMap<UUID, JiggleState> = HashMap()
    private val brimJiggle: MutableMap<UUID, JiggleState> = HashMap()
    private val hat2Jiggle: MutableMap<UUID, JiggleState> = HashMap()
    private val hat3Jiggle: MutableMap<UUID, JiggleState> = HashMap()

    /** Yaw-only spring for the Scholar's hat + helmet layer. The existing
     *  [JiggleState] tracks pitch + roll (xRot/zRot); this is a thinner state
     *  that just holds a single Y-axis spring. */
    private class YawJiggleState {
        var pos: Float = 0f                                 // yaw offset (rad)
        var vel: Float = 0f
        var prevHeadYawDeg: Float = Float.NaN
        var prevLateralLag: Double = 0.0
        var lastNs: Long = 0L
    }
    private val scholarHeadYawJiggle: MutableMap<UUID, YawJiggleState> = HashMap()

    /** Wallclock nanosecond timestamps for the **Mystic Wind** pulse. While
     *  `System.nanoTime() < mysticWindUntilNs[uuid]`, a constant backward
     *  wind boost is added to that entity's wind input — robes briefly
     *  flap outward when the wearer casts. Set in the
     *  [InteractionEvent.RIGHT_CLICK_ITEM] handler when the player uses an
     *  item in the `enderkinesis:mystic_wind_influencer` tag. */
    private val mysticWindUntilNs: MutableMap<UUID, Long> = ConcurrentHashMap()

    /** When the most recent Mystic Wind pulse *started* for this entity.
     *  Drives the ease-in envelope so the wind ramps from 0 over
     *  [MYSTIC_WIND_EASE_IN_NS] instead of snapping to full strength.
     *  Reset to the current wallclock time whenever the pulse window
     *  transitions from "expired" to "open." */
    private val mysticWindStartNs: MutableMap<UUID, Long> = ConcurrentHashMap()


    private class JiggleState {
        var posX: Float = 0f            // forward/back pitch (radians)
        var velX: Float = 0f
        var posZ: Float = 0f            // side-to-side roll (radians)
        var velZ: Float = 0f
        var prevFwdLag: Double = 0.0    // last frame's forward-lag, for impulse derivation
        var prevLateralLag: Double = 0.0
        var prevDy: Double = 0.0
        var prevHeadYawDeg: Float = Float.NaN  // last frame's head yaw, for rate impulse
        var lastNs: Long = 0L
    }

    /** Wearer-derived inputs shared by every jiggle-physics part (hood pieces, pearls).
     *  Computed once per entity per frame in [gatherJiggleInputs] so the hood and pearls
     *  see identical lag projections, step phases, and wind. */
    private data class JiggleInputs(
        val forwardLag: Double,
        val lateralLag: Double,
        val dy: Double,
        val stepPhase: Float,
        val limbSwingAmount: Float,
        val headPitchDeg: Float,
        val headYawDeg: Float,
        val windForward: Double,
        val windLateral: Double,
    )

    /** Wind vector projected onto body-forward/right axes. Positive `forward` means wind
     *  pushes the part *backward* in body frame (consistent with forwardLag's sign — both
     *  produce positive xRot deflection); same flip on the lateral component. */
    private data class WindVector(val forward: Double, val lateral: Double) {
        companion object { val ZERO = WindVector(0.0, 0.0) }
    }

    /** Compute the ambient-wind force on the wearer's clothing plus any
     *  active **Mystic Wind** boost. Ambient wind is active only when the
     *  wearer can see the sky, it's daytime, and they're elevated (below
     *  `y = 80` it's zero; above `y = 180` it's at full strength).
     *  Direction varies via two detuned sin/cos harmonics so it never
     *  repeats and never has a flat dead point. The Mystic Wind boost
     *  applies independently of sky/altitude — it pushes purely in
     *  body-forward (which deflects xRot backward) for [MYSTIC_WIND_DURATION_NS]
     *  after a `mystic_wind_influencer`-tagged item is used. */
    private fun computeWind(entity: LivingEntity, partialTick: Float): WindVector {
        val level = entity.level()
        val pos = entity.blockPosition()
        val isDay = level.isDay
        val canSeeSky = level.canSeeSky(pos)

        var ambientForward = 0.0
        var ambientLateral = 0.0
        if (isDay && canSeeSky) {
            val altitudeRamp = ((entity.y - 80.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
            if (altitudeRamp > 0f) {
                val gameTime = level.gameTime + partialTick.toDouble()
                val windPhase = (gameTime * 0.025).toFloat()
                val rawX = (Mth.sin(windPhase * 0.7f) + 0.4f * Mth.sin(windPhase * 1.9f)) * altitudeRamp
                val rawZ = (Mth.cos(windPhase * 0.5f) + 0.4f * Mth.cos(windPhase * 1.6f)) * altitudeRamp

                val yawDeg = Mth.lerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
                val yawRad = yawDeg * Mth.DEG_TO_RAD
                val sinYaw = Mth.sin(yawRad).toDouble()
                val negCosYaw = -Mth.cos(yawRad).toDouble()

                // Project world wind onto body-forward + body-right. Negated so positive `forward`
                // here matches positive `forwardLag` upstream — both deflect xRot backward.
                ambientForward = -(rawX.toDouble() * sinYaw + rawZ.toDouble() * negCosYaw)
                ambientLateral = -(rawX.toDouble() * negCosYaw - rawZ.toDouble() * sinYaw)
            }
        }

        // Mystic Wind — body-forward boost while the 0.1-second window is
        // open. Three refresh paths cover all interaction shapes:
        //  - one-shot right-click (instant-use tomes) → ridden by the
        //    [InteractionEvent.RIGHT_CLICK_ITEM] timer set in the handler;
        //  - held right-click (Staff-of-Aegis etc.) → `isUsingItem` is
        //    true and the wearer's [Player.useItem] is the tagged stack;
        //  - left-click swing (block / entity attack OR a swung air
        //    swing) → `swinging` is true during the ~6-tick animation
        //    and the wearer's main-hand stack is the tagged item.
        val mc = Minecraft.getInstance()
        if (entity.isUsingItem && entity.useItem.`is`(MYSTIC_WIND_INFLUENCER)) {
            refreshMysticWind(entity)
        } else if (entity.swinging && entity.mainHandItem.`is`(MYSTIC_WIND_INFLUENCER)) {
            refreshMysticWind(entity)
        } else if (
            entity === mc.player &&
            mc.options.keyAttack.isDown &&
            entity.mainHandItem.`is`(MYSTIC_WIND_INFLUENCER)
        ) {
            // Local-player attack-key polling. Catches items whose left-click
            // is intercepted before vanilla's swing animation runs — the
            // Wylland tome cancels [Minecraft.startAttack] via mixin, so
            // `swinging` is never set even though the player IS attacking.
            // Other players in multiplayer don't trip this path (we can't
            // read their input), but the visual is local-player-focused so
            // that's acceptable.
            refreshMysticWind(entity)
        }
        val mysticBoost = mysticWindBoost(entity)

        if (ambientForward == 0.0 && ambientLateral == 0.0 && mysticBoost == 0.0) {
            return WindVector.ZERO
        }
        return WindVector(forward = ambientForward + mysticBoost, lateral = ambientLateral)
    }

    private fun gatherJiggleInputs(entity: LivingEntity): JiggleInputs {
        val partialTick = Minecraft.getInstance().frameTime
        val partialD = partialTick.toDouble()

        val dx: Double
        val dy: Double
        val dz: Double
        if (entity is AbstractClientPlayer) {
            dx = Mth.lerp(partialD, entity.xCloakO, entity.xCloak) - Mth.lerp(partialD, entity.xo, entity.x)
            dy = Mth.lerp(partialD, entity.yCloakO, entity.yCloak) - Mth.lerp(partialD, entity.yo, entity.y)
            dz = Mth.lerp(partialD, entity.zCloakO, entity.zCloak) - Mth.lerp(partialD, entity.zo, entity.z)
        } else {
            val vel = entity.deltaMovement
            dx = -vel.x
            dy = -vel.y
            dz = -vel.z
        }

        val yawDeg = Mth.lerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
        val yawRad = yawDeg * Mth.DEG_TO_RAD
        val sinYaw = Mth.sin(yawRad).toDouble()
        val negCosYaw = -Mth.cos(yawRad).toDouble()
        // forwardLag: positive when wearer accelerates forward (the lagging part trails
        // behind). lateralLag: positive when wearer accelerates rightward (vanilla cape
        // f4 convention). Feed raw projections; gains downstream convert to angle/impulse.
        val forwardLag = dx * sinYaw + dz * negCosYaw
        val lateralLag = dx * negCosYaw - dz * sinYaw

        val limbSwing = entity.walkAnimation.position(partialTick)
        val limbSwingAmount = entity.walkAnimation.speed(partialTick).coerceAtMost(1.0f)
        val stepPhase = limbSwing * 0.6662f

        // Head orientation — drives pitch gravity bias (hood) and roll yaw impulse (hood).
        // Body-attached parts (pearls) pass gravityGain=0 and headYawImpulseGain=0 so
        // these are ignored downstream.
        val headPitchDeg = Mth.lerp(partialTick, entity.xRotO, entity.xRot)
        val headYawDeg = Mth.lerp(partialTick, entity.yHeadRotO, entity.yHeadRot)

        val wind = computeWind(entity, partialTick)

        return JiggleInputs(
            forwardLag, lateralLag, dy, stepPhase, limbSwingAmount,
            headPitchDeg, headYawDeg,
            wind.forward, wind.lateral,
        )
    }

    /** True when the local player is being drawn inside a screen — e.g.
     *  the inventory paperdoll, the death screen, a recipe-book preview.
     *  In those contexts we want the wearer to stand still in their rest
     *  pose with zero jiggle (no flapping cape, no swaying cone), so each
     *  `apply*` function short-circuits to a rest-pose write. The check
     *  also doubles as a freeze for the local player while their input is
     *  captured by a UI — they aren't moving anyway. Other players are
     *  never affected. */
    private fun isInventoryRender(entity: LivingEntity): Boolean {
        val mc = Minecraft.getInstance()
        return mc.screen != null && mc.player === entity
    }

    /** Refresh a Mystic Wind pulse for [entity]. Extends the closing-edge
     *  expiry by another [MYSTIC_WIND_DURATION_NS]. If the pulse window
     *  was already closed (so this is a *fresh* activation rather than a
     *  refresh of an ongoing one), also restamps [mysticWindStartNs] so
     *  the ease-in ramp starts from 0. */
    private fun refreshMysticWind(entity: LivingEntity) {
        val now = System.nanoTime()
        val until = mysticWindUntilNs[entity.uuid] ?: 0L
        if (now >= until) {
            mysticWindStartNs[entity.uuid] = now
        }
        mysticWindUntilNs[entity.uuid] = now + MYSTIC_WIND_DURATION_NS
    }

    /** Current Mystic Wind body-forward boost for [entity]. 0 when the
     *  pulse window has expired; otherwise [MYSTIC_WIND_STRENGTH] modulated
     *  by an ease-in × ease-out × chaotic-shimmer envelope. The shimmer is
     *  three detuned sin harmonics so the visible intensity wanders
     *  unpredictably instead of locking to a single beat — feels more like
     *  arcane breeze than a metronome. */
    private fun mysticWindBoost(entity: LivingEntity): Double {
        val until = mysticWindUntilNs[entity.uuid] ?: return 0.0
        val now = System.nanoTime()
        if (now >= until) return 0.0
        val start = mysticWindStartNs[entity.uuid] ?: now

        val sinceStart = (now - start).toDouble()
        val untilEnd = (until - now).toDouble()
        // Ease-in over [0, EASE_IN] — smoothstep so the wind doesn't snap
        // to full strength on a fresh activation.
        val easeIn = (sinceStart / MYSTIC_WIND_EASE_IN_NS).coerceIn(0.0, 1.0)
        val easeInSmooth = easeIn * easeIn * (3.0 - 2.0 * easeIn)
        // Ease-out over the last [EASE_OUT] of the pulse window so the
        // wind bleeds off rather than cutting hard.
        val easeOut = (untilEnd / MYSTIC_WIND_EASE_OUT_NS).coerceIn(0.0, 1.0)
        val easeOutSmooth = easeOut * easeOut * (3.0 - 2.0 * easeOut)

        // Chaotic shimmer in [0.45, 1.0] around 0.7. Three detuned
        // harmonics keyed off wallclock seconds + a per-entity phase
        // offset hashed from the UUID so two wearers don't shimmer in
        // lockstep. Net envelope stays positive (no sign flips, so the
        // wind always pushes backward).
        val tSec = now / 1_000_000_000.0
        val phase = (entity.uuid.leastSignificantBits.toDouble() * 1.61803398875) % (Math.PI * 2.0)
        val shimmer = 0.7 + 0.15 * Math.sin(tSec * 11.3 + phase) +
            0.10 * Math.sin(tSec * 17.9 + phase * 1.4) +
            0.05 * Math.sin(tSec * 27.7 + phase * 2.1)
        val shimmerClamped = shimmer.coerceIn(0.45, 1.0)

        return MYSTIC_WIND_STRENGTH * easeInSmooth * easeOutSmooth * shimmerClamped
    }

    /** Zero a [JiggleState] so the spring doesn't snap when the wearer
     *  re-enters world view from an inventory paperdoll. */
    private fun resetState(state: JiggleState?) {
        if (state == null) return
        state.posX = 0f; state.velX = 0f
        state.posZ = 0f; state.velZ = 0f
        state.prevFwdLag = 0.0
        state.prevLateralLag = 0.0
        state.prevDy = 0.0
        state.prevHeadYawDeg = Float.NaN
    }

    override fun render(
        matrices: PoseStack,
        vertexConsumers: MultiBufferSource,
        stack: ItemStack,
        entity: LivingEntity,
        slot: EquipmentSlot,
        light: Int,
        contextModel: HumanoidModel<LivingEntity>,
    ) {
        val armor = stack.item as? ArmorItem ?: return
        val materialName = armor.material.name

        val useLeggings = slot == EquipmentSlot.LEGS
        val model = obtainModel(useLeggings)

        // Copy pose (limb angles, head rotation, sneaking, etc.) from the entity's
        // armor-layer context model so the robe follows the player's animation.
        contextModel.copyPropertiesTo(model)
        setPartVisibility(model, slot)

        // Cape-style flap on the coat-tail piece, only when the leggings layer is being
        // drawn (that's where the `robe` part lives).
        if (useLeggings) applyCoatTailFlap(model, entity)

        val textureSuffix = if (useLeggings) "layer_2" else "layer_1"
        val texture = ResourceLocation("enderkinesis", "textures/models/armor/${materialName}_${textureSuffix}.png")

        if (slot == EquipmentSlot.HEAD) {
            val showHood = materialName == "end_cult"
            if (showHood) applyHoodFlap(model, entity)
            // Scholar's helmet (head cube + hat overlay) gets a very slight yaw
            // jiggle — head turns drag it slightly, Mystic Wind shimmers it
            // sideways. Must run AFTER copyPropertiesTo (so the rest pose is
            // current) and BEFORE the render passes (which read .yRot).
            if (materialName == "scholar") applyScholarHeadYaw(model, entity)
            renderHelmetWithTranslucentHead(matrices, vertexConsumers, light, model, texture, showHood, stack.hasFoil())
            // Blue Witch hat = second render pass with a different texture +
            // its own jiggle physics on brim / hat_2 / hat_3.
            if (materialName == "blue_witch") {
                renderWitchHat(matrices, vertexConsumers, light, model.head, entity, stack.hasFoil())
            }
        } else {
            // Pearls only render with the End Cult chestplate. Toggle their visibility
            // before each render so the cached model doesn't carry stale flags across
            // entity/material combinations.
            if (slot == EquipmentSlot.CHEST) {
                val showPearls = materialName == "end_cult"
                setPearlVisibility(model, showPearls)
                if (showPearls) applyPearlJiggle(model, entity)
            } else {
                setPearlVisibility(model, false)
            }
            ArmorRenderer.renderPart(matrices, vertexConsumers, light, stack, model, texture)
        }
    }

    private fun setPearlVisibility(model: HumanoidModel<LivingEntity>, visible: Boolean) {
        for (name in arrayOf("pearl_center", "pearl_left", "pearl_right")) {
            try {
                model.body.getChild(name).visible = visible
            } catch (_: NoSuchElementException) {
                // Outer (leggings) model doesn't have the pearls — no-op.
            }
        }
    }

    /** Three-pearl chestplate jiggle. Each pearl is its own jiggle-physics pendulum
     *  hanging from a chain bead at the top-centre pivot. Same dynamics as the hood:
     *  motion impulses from wearer acceleration, per-step kicks from footfalls,
     *  steady-state lean from forward-lag. No gravity or head-yaw response — pearls are
     *  body-attached, so head pitch/yaw don't influence them.
     *
     *  Pearls have slightly **detuned natural frequencies** per piece (centre on-base,
     *  left faster, right slower) and a ±π/2 offset on the step phase, so the three
     *  beads drift in and out of sync over time even when sharing the same impulses. */
    private fun applyPearlJiggle(model: HumanoidModel<LivingEntity>, entity: LivingEntity) {
        val pearlCentre = try { model.body.getChild("pearl_center") } catch (_: NoSuchElementException) { return }
        val pearlLeft = try { model.body.getChild("pearl_left") } catch (_: NoSuchElementException) { return }
        val pearlRight = try { model.body.getChild("pearl_right") } catch (_: NoSuchElementException) { return }

        if (isInventoryRender(entity)) {
            resetState(pearlCentreJiggle[entity.uuid])
            resetState(pearlLeftJiggle[entity.uuid])
            resetState(pearlRightJiggle[entity.uuid])
            pearlCentre.xRot = 0f; pearlCentre.zRot = 0f
            pearlLeft.xRot = 0f;   pearlLeft.zRot = 0f
            pearlRight.xRot = 0f;  pearlRight.zRot = 0f
            return
        }

        val inputs = gatherJiggleInputs(entity)
        val halfPi = (Math.PI * 0.5).toFloat()

        // Centre — base natural frequencies, on-beat step phase.
        stepJiggle(
            state = pearlCentreJiggle.getOrPut(entity.uuid) { JiggleState() },
            forwardLag = inputs.forwardLag, lateralLag = inputs.lateralLag, dy = inputs.dy,
            stepPhase = inputs.stepPhase, limbSwingAmount = inputs.limbSwingAmount,
            headPitchDeg = inputs.headPitchDeg, headYawDeg = inputs.headYawDeg,
            windForward = inputs.windForward, windLateral = inputs.windLateral,
            omegaX = 16f, dampingX = 0.13f,
            motionImpulseGainX = 5f, vertImpulseGain = 2.5f,
            stepImpulseGain = 6f, restGainXDeg = 22f,
            gravityGain = 0f, droopLimitDeg = 0f,
            windGainXDeg = 2.5f,
            capXDeg = 12f, floorXDeg = -12f,
            omegaZ = 17f, dampingZ = 0.13f,
            motionImpulseGainZ = 4f, restGainZDeg = 18f,
            headYawImpulseGain = 0f,
            windGainZDeg = 2f,
            capZDeg = 10f,
            target = pearlCentre,
        )

        // Left — faster natural frequency (~+10%), leading step phase (+π/2). The
        // detune means the spring drifts out of sync with the centre even though
        // motion impulses arrive simultaneously.
        stepJiggle(
            state = pearlLeftJiggle.getOrPut(entity.uuid) { JiggleState() },
            forwardLag = inputs.forwardLag, lateralLag = inputs.lateralLag, dy = inputs.dy,
            stepPhase = inputs.stepPhase + halfPi, limbSwingAmount = inputs.limbSwingAmount,
            headPitchDeg = inputs.headPitchDeg, headYawDeg = inputs.headYawDeg,
            windForward = inputs.windForward, windLateral = inputs.windLateral,
            omegaX = 17.5f, dampingX = 0.12f,
            motionImpulseGainX = 5f, vertImpulseGain = 2.5f,
            stepImpulseGain = 6f, restGainXDeg = 22f,
            gravityGain = 0f, droopLimitDeg = 0f,
            windGainXDeg = 2.5f,
            capXDeg = 12f, floorXDeg = -12f,
            omegaZ = 18.5f, dampingZ = 0.12f,
            motionImpulseGainZ = 4f, restGainZDeg = 18f,
            headYawImpulseGain = 0f,
            windGainZDeg = 2f,
            capZDeg = 10f,
            target = pearlLeft,
        )

        // Right — slower natural frequency (~-10%), lagging step phase (-π/2).
        stepJiggle(
            state = pearlRightJiggle.getOrPut(entity.uuid) { JiggleState() },
            forwardLag = inputs.forwardLag, lateralLag = inputs.lateralLag, dy = inputs.dy,
            stepPhase = inputs.stepPhase - halfPi, limbSwingAmount = inputs.limbSwingAmount,
            headPitchDeg = inputs.headPitchDeg, headYawDeg = inputs.headYawDeg,
            windForward = inputs.windForward, windLateral = inputs.windLateral,
            omegaX = 14.5f, dampingX = 0.14f,
            motionImpulseGainX = 5f, vertImpulseGain = 2.5f,
            stepImpulseGain = 6f, restGainXDeg = 22f,
            gravityGain = 0f, droopLimitDeg = 0f,
            windGainXDeg = 2.5f,
            capXDeg = 12f, floorXDeg = -12f,
            omegaZ = 15.5f, dampingZ = 0.14f,
            motionImpulseGainZ = 4f, restGainZDeg = 18f,
            headYawImpulseGain = 0f,
            windGainZDeg = 2f,
            capZDeg = 10f,
            target = pearlRight,
        )
    }

    /** Helmet slot rendered in up to three passes. **Order matters**: the cutout passes
     *  go first so their depth-buffer writes establish "what's visible behind the
     *  translucent head," then the translucent head draws on top without occluding the
     *  back faces of the hat or hood that should be visible through it.
     *
     *   1. `hat` overlay alone, vanilla cutout (glint OK). Writes depth.
     *   2. `hood_flop_2` + children (End Cult only), vanilla cutout. Head cube is skipped
     *      via `skipDraw` but the transform still runs so the hood pivots correctly.
     *      Writes depth.
     *   3. `head` cube alone, translucent, no glint. Blended on top of the already-drawn
     *      hat/hood without occluding their back faces. */
    private fun renderHelmetWithTranslucentHead(
        matrices: PoseStack,
        vertexConsumers: MultiBufferSource,
        light: Int,
        model: HumanoidModel<LivingEntity>,
        texture: ResourceLocation,
        showHood: Boolean,
        hasGlint: Boolean,
    ) {
        val hood = try {
            model.head.getChild("hood_flop_2")
        } catch (_: NoSuchElementException) {
            null
        }

        val cutoutType = RenderType.armorCutoutNoCull(texture)
        // Wrap the cutout buffer so it chains a foil pass when the item is enchanted —
        // same trick vanilla's HumanoidArmorLayer.renderModel uses. The plain
        // `getBuffer(cutoutType)` would skip glint entirely.
        val cutoutFoilBuffer = ItemRenderer.getArmorFoilBuffer(vertexConsumers, cutoutType, false, hasGlint)

        // Pass 1: hat overlay only, vanilla cutout + glint.
        model.head.visible = false
        model.head.skipDraw = false
        model.hat.visible = true
        hood?.visible = false
        model.renderToBuffer(matrices, cutoutFoilBuffer, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f)

        // Pass 2: hood (End Cult only), vanilla cutout + glint. Head visible so child
        // renders, but skipDraw suppresses head's cube — only the transform runs.
        if (showHood && hood != null) {
            model.head.visible = true
            model.head.skipDraw = true
            model.hat.visible = false
            hood.visible = true
            model.renderToBuffer(matrices, cutoutFoilBuffer, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f)
        }

        // Pass 3: head cube only, translucent, NO glint (intentional — the see-through
        // pass shouldn't pick up the foil overlay). Drawn LAST so it blends over the
        // hat/hood without depth-occluding their back faces.
        model.head.visible = true
        model.head.skipDraw = false
        model.hat.visible = false
        hood?.visible = false
        model.renderToBuffer(matrices, vertexConsumers.getBuffer(RenderType.entityTranslucent(texture)), light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f)

        // Restore — the model is cached and shared across entities/frames.
        model.head.visible = true
        model.head.skipDraw = false
        model.hat.visible = true
        hood?.visible = true
    }

    /** Jiggle-physics hood flap. Each piece (`hood_flop_2`, `hood_flop_1`) is its own
     *  damped harmonic oscillator with persistent pos+vel state — driven by impulses
     *  derived from the wearer's motion rather than tracking a procedural target:
     *
     *  - **Motion impulse**: per-frame derivative of forward-lag and vertical-lag kicks
     *    the spring's velocity. Sudden starts/stops/jumps/landings produce big impulses
     *    → visible wobble that decays naturally.
     *  - **Step impulse**: a cosine pulse at walk frequency adds a small kick per
     *    footfall, so walking produces real spring-driven bob (no baked sine).
     *  - **Steady-state lean**: spring rests at a small forward-deflected angle
     *    proportional to current forward-lag, so sprinting sits slightly back instead of
     *    snapping to rest.
     *
     *  Positive xRot = backward flap (toward `+Z` in head-local), away from the face. */
    private fun applyHoodFlap(model: HumanoidModel<LivingEntity>, entity: LivingEntity) {
        val hoodFlop2 = try {
            model.head.getChild("hood_flop_2")
        } catch (_: NoSuchElementException) {
            return
        }
        val hoodFlop1 = try {
            hoodFlop2.getChild("hood_flop_1")
        } catch (_: NoSuchElementException) {
            return
        }

        if (isInventoryRender(entity)) {
            resetState(flop2Jiggle[entity.uuid])
            resetState(flop1Jiggle[entity.uuid])
            hoodFlop2.xRot = 0f; hoodFlop2.zRot = 0f
            hoodFlop1.xRot = 0f; hoodFlop1.zRot = 0f
            return
        }

        val inputs = gatherJiggleInputs(entity)

        // Outer piece — STIFFER (larger physical volume → more inertia, more damping):
        // higher damping, weaker impulse response, weaker per-step kick. Less gravity
        // sensitivity since the bulk of the hood near the head has less leverage. Lateral
        // roll is even stiffer so the big piece barely sways side-to-side.
        stepJiggle(
            state = flop2Jiggle.getOrPut(entity.uuid) { JiggleState() },
            forwardLag = inputs.forwardLag, lateralLag = inputs.lateralLag, dy = inputs.dy,
            stepPhase = inputs.stepPhase, limbSwingAmount = inputs.limbSwingAmount,
            headPitchDeg = inputs.headPitchDeg, headYawDeg = inputs.headYawDeg,
            windForward = inputs.windForward, windLateral = inputs.windLateral,
            omegaX = 10f, dampingX = 0.28f,
            motionImpulseGainX = 3.5f, vertImpulseGain = 2.5f,
            stepImpulseGain = 5f, restGainXDeg = 32f,
            gravityGain = 0.55f, droopLimitDeg = 27.5f,
            windGainXDeg = 8f,
            capXDeg = 55f, floorXDeg = -40f,
            omegaZ = 11f, dampingZ = 0.30f,
            motionImpulseGainZ = 2.5f, restGainZDeg = 7f,
            headYawImpulseGain = 1.5f,
            windGainZDeg = 5f,
            capZDeg = 5f,
            target = hoodFlop2,
        )

        // Inner piece — springier, lighter, more gravity-sensitive (lower mass on a
        // longer lever from the head pivot). Its pivot inherits the outer's rotation, so
        // visible motion compounds.
        stepJiggle(
            state = flop1Jiggle.getOrPut(entity.uuid) { JiggleState() },
            forwardLag = inputs.forwardLag, lateralLag = inputs.lateralLag, dy = inputs.dy,
            stepPhase = inputs.stepPhase + 0.65f, limbSwingAmount = inputs.limbSwingAmount,
            headPitchDeg = inputs.headPitchDeg, headYawDeg = inputs.headYawDeg,
            windForward = inputs.windForward, windLateral = inputs.windLateral,
            omegaX = 13f, dampingX = 0.10f,
            motionImpulseGainX = 7f, vertImpulseGain = 4f,
            stepImpulseGain = 13f, restGainXDeg = 44f,
            gravityGain = 0.75f, droopLimitDeg = 30f,
            windGainXDeg = 11f,
            capXDeg = 70f, floorXDeg = -55f,
            omegaZ = 14f, dampingZ = 0.12f,
            motionImpulseGainZ = 4f, restGainZDeg = 12f,
            headYawImpulseGain = 2.5f,
            windGainZDeg = 7f,
            capZDeg = 8f,
            target = hoodFlop1,
        )
    }

    /** Very-slight yaw jiggle for the Scholar's helmet — applied to both
     *  `model.head` (the inner armor layer) and `model.hat` (the outer
     *  overlay), so the whole helmet wobbles together rather than the two
     *  layers drifting apart. Both parts are already aligned to the head
     *  pose by [HumanoidModel.copyPropertiesTo] (which copies `hat` as well
     *  as `head`), so this just adds a shared spring offset on top.
     *
     *  Driven by three small impulses, all routed into a single Y-axis damped
     *  harmonic oscillator:
     *   - **Head-yaw rate** — when the player snaps their view, the helmet
     *     trails by [SCHOLAR_HEAD_YAW_RATE_GAIN] proportionally (sign
     *     negated so the offset opposes the turn → lags behind it).
     *   - **Lateral acceleration** — sideways motion nudges the helmet
     *     left/right; gain stays small because the cap is tight.
     *   - **Mystic Wind shimmer** — when the wearer triggers a pulse via a
     *     tagged item, the existing chaotic-shimmer wind term modulates a
     *     signed sin so the kick alternates direction and reads as "arcane
     *     breeze" rather than a one-way push (the regular jiggles use the
     *     same boost as a unidirectional backward thrust, but a yaw axis has
     *     no preferred backward direction).
     *
     *  Capped at [SCHOLAR_HEAD_YAW_CAP_DEG] so the effect stays
     *  "did I just see that?" subtle. */
    private fun applyScholarHeadYaw(model: HumanoidModel<LivingEntity>, entity: LivingEntity) {
        if (isInventoryRender(entity)) {
            scholarHeadYawJiggle[entity.uuid]?.let {
                it.pos = 0f; it.vel = 0f
                it.prevHeadYawDeg = Float.NaN
                it.prevLateralLag = 0.0
                it.lastNs = 0L
            }
            return
        }

        val state = scholarHeadYawJiggle.getOrPut(entity.uuid) { YawJiggleState() }
        val inputs = gatherJiggleInputs(entity)

        val now = System.nanoTime()
        val dt = if (state.lastNs == 0L) 1f / 60f
        else ((now - state.lastNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.1f)
        state.lastNs = now

        // Head-yaw rate impulse. `Mth.wrapDegrees` handles the 359→0 wrap so a
        // pass through north doesn't register as a 359°/dt spike.
        val yawRate: Float = if (state.prevHeadYawDeg.isNaN()) {
            state.prevHeadYawDeg = inputs.headYawDeg
            0f
        } else {
            val wrapped = Mth.wrapDegrees((inputs.headYawDeg - state.prevHeadYawDeg).toDouble()).toFloat()
            state.prevHeadYawDeg = inputs.headYawDeg
            wrapped / dt
        }

        // Lateral acceleration from the lag projection.
        val latAcc = ((inputs.lateralLag - state.prevLateralLag) / dt).toFloat()
        state.prevLateralLag = inputs.lateralLag

        // Mystic Wind shimmer — two detuned harmonics with a per-entity phase
        // offset so two wearers don't shimmer in lockstep. SIGNED (so the wind
        // can push the yaw both ways), unlike the unidirectional wind term the
        // hood/witch-hat pitch springs use.
        val windBoost = mysticWindBoost(entity).toFloat()
        val windYawImpulse = if (windBoost > 0f) {
            val tSec = now / 1_000_000_000.0
            val phase = (entity.uuid.leastSignificantBits.toDouble() * 0.6180339887) % (Math.PI * 2.0)
            val wave = (Math.sin(tSec * 8.3 + phase) + 0.6 * Math.sin(tSec * 14.7 + phase * 1.3)).toFloat()
            windBoost * wave * SCHOLAR_HEAD_WIND_GAIN
        } else 0f

        // Sign on yaw rate is negated: a snap-right turn (+yawRate) should
        // leave the helmet briefly trailing to the LEFT (negative pos).
        val impulse = -yawRate * SCHOLAR_HEAD_YAW_RATE_GAIN +
            latAcc * SCHOLAR_HEAD_LATERAL_GAIN +
            windYawImpulse

        val springAcc = -2f * SCHOLAR_HEAD_YAW_DAMPING * SCHOLAR_HEAD_YAW_OMEGA * state.vel -
            SCHOLAR_HEAD_YAW_OMEGA * SCHOLAR_HEAD_YAW_OMEGA * state.pos
        state.vel += (springAcc + impulse) * dt
        state.pos += state.vel * dt

        val cap = SCHOLAR_HEAD_YAW_CAP_DEG * Mth.DEG_TO_RAD
        if (state.pos > cap) { state.pos = cap; if (state.vel > 0f) state.vel = 0f }
        if (state.pos < -cap) { state.pos = -cap; if (state.vel < 0f) state.vel = 0f }

        // Apply to BOTH parts — vanilla HumanoidModel renders `head` and `hat`
        // as siblings, not parent/child, so the same offset has to be written
        // to each independently for the helmet to wobble as one unit.
        model.head.yRot += state.pos
        model.hat.yRot += state.pos
    }

    /** Dual-axis jiggle-physics integration step. Each hood piece is a pair of damped
     *  harmonic oscillators — pitch (xRot) for forward/back flap, roll (zRot) for
     *  side-to-side sway — both kicked by impulses derived from the wearer's motion.
     *  Writes the resulting angles directly onto [target].
     *
     *  Per-axis suffixes (X for pitch, Z for roll):
     *  - omegaX/omegaZ + dampingX/dampingZ: frequency and damping ratio per axis.
     *  - motionImpulseGainX/Z: convert blocks/sec² of acceleration into rad/sec² of
     *    angular impulse on that axis.
     *  - vertImpulseGain: vertical acceleration → pitch impulse (jumps/landings).
     *  - stepImpulseGain: peak per-footfall pitch impulse.
     *  - restGainXDeg/restGainZDeg: steady-state deflection (degrees per unit of lag).
     *  - capXDeg/capZDeg + floorXDeg: soft caps. Roll is symmetric; pitch is back-only
     *    with a smaller forward floor for natural rebound. */
    private fun stepJiggle(
        state: JiggleState,
        forwardLag: Double, lateralLag: Double, dy: Double,
        stepPhase: Float, limbSwingAmount: Float,
        headPitchDeg: Float, headYawDeg: Float,
        windForward: Double, windLateral: Double,
        omegaX: Float, dampingX: Float,
        motionImpulseGainX: Float, vertImpulseGain: Float,
        stepImpulseGain: Float, restGainXDeg: Float,
        gravityGain: Float, droopLimitDeg: Float,
        windGainXDeg: Float,
        capXDeg: Float, floorXDeg: Float,
        omegaZ: Float, dampingZ: Float,
        motionImpulseGainZ: Float, restGainZDeg: Float,
        headYawImpulseGain: Float,
        windGainZDeg: Float,
        capZDeg: Float,
        target: ModelPart,
        /** Pitch-axis output sign. `+1f` for parts whose mass is behind the
         *  pivot (hood, pearls) where `+xRot = backward lag` per MC
         *  convention. `-1f` for the witch hat brim + cone segments whose
         *  mass sits *above* the pivot — there `+xRot` rotates the cube
         *  forward instead of backward, so we negate the final write while
         *  keeping the spring dynamics, gravity bias, and clamps all in
         *  the well-tested "hood frame". */
        xRotSign: Float = 1f,
        /** Roll-axis output sign — see [xRotSign]. Same geometric reason
         *  inverts the sway direction when the mass is above the pivot. */
        zRotSign: Float = 1f,
    ) {
        val now = System.nanoTime()
        val dt = if (state.lastNs == 0L) 1f / 60f
        else ((now - state.lastNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.1f)
        state.lastNs = now

        val fwdAcc = ((forwardLag - state.prevFwdLag) / dt).toFloat()
        val upAcc = ((dy - state.prevDy) / dt).toFloat()
        state.prevFwdLag = forwardLag
        state.prevDy = dy
        val pitchImpulse = fwdAcc * motionImpulseGainX + upAcc * vertImpulseGain +
            Mth.cos(stepPhase) * limbSwingAmount * stepImpulseGain

        // Gravity bias: when head pitches up (entity.xRot negative in MC convention),
        // hood droops back in head-local frame to stay vertical in world (hood-local
        // xRot offset = -headPitch). Looking down produces a negative bias (the hood
        // tips forward) but the magnitude is clamped at -[droopLimitDeg] so the piece
        // can't fall past a physically sensible angle, regardless of how far the wearer
        // looks down.
        val gravityBiasDeg = (-headPitchDeg * gravityGain).coerceAtLeast(-droopLimitDeg)
        val windPitchDeg = windForward.toFloat() * windGainXDeg
        val targetXDeg = (forwardLag.toFloat() * restGainXDeg + gravityBiasDeg + windPitchDeg)
            .coerceIn(floorXDeg, capXDeg)
        val targetXRad = targetXDeg * Mth.DEG_TO_RAD
        val springXAcc = -2f * dampingX * omegaX * state.velX -
            omegaX * omegaX * (state.posX - targetXRad)
        state.velX += (springXAcc + pitchImpulse) * dt
        state.posX += state.velX * dt

        val capXRad = capXDeg * Mth.DEG_TO_RAD
        val floorXRad = floorXDeg * Mth.DEG_TO_RAD
        if (state.posX > capXRad) {
            state.posX = capXRad
            if (state.velX > 0f) state.velX = 0f
        } else if (state.posX < floorXRad) {
            state.posX = floorXRad
            if (state.velX < 0f) state.velX = 0f
        }

        val latAcc = ((lateralLag - state.prevLateralLag) / dt).toFloat()
        state.prevLateralLag = lateralLag
        // Head-yaw rate: per-frame change in head yaw (wrapped to [-180, 180] for the
        // 359↔0 boundary), converted to rad/sec. Snapping the head left/right kicks
        // the hood's roll velocity — hood lags behind the head's rotation.
        val yawRate: Float = if (state.prevHeadYawDeg.isNaN()) {
            state.prevHeadYawDeg = headYawDeg
            0f
        } else {
            val wrapped = Mth.wrapDegrees((headYawDeg - state.prevHeadYawDeg).toDouble()).toFloat()
            state.prevHeadYawDeg = headYawDeg
            wrapped * Mth.DEG_TO_RAD / dt
        }
        val rollImpulse = latAcc * motionImpulseGainZ + yawRate * headYawImpulseGain

        val windRollDeg = windLateral.toFloat() * windGainZDeg
        val targetZDeg = (lateralLag.toFloat() * restGainZDeg + windRollDeg)
            .coerceIn(-capZDeg, capZDeg)
        val targetZRad = targetZDeg * Mth.DEG_TO_RAD
        val springZAcc = -2f * dampingZ * omegaZ * state.velZ -
            omegaZ * omegaZ * (state.posZ - targetZRad)
        state.velZ += (springZAcc + rollImpulse) * dt
        state.posZ += state.velZ * dt

        val capZRad = capZDeg * Mth.DEG_TO_RAD
        if (state.posZ > capZRad) {
            state.posZ = capZRad
            if (state.velZ > 0f) state.velZ = 0f
        } else if (state.posZ < -capZRad) {
            state.posZ = -capZRad
            if (state.velZ < 0f) state.velZ = 0f
        }

        target.xRot = state.posX * xRotSign
        target.zRot = state.posZ * zRotSign
    }

    /** Cape-style flap on the coat-tail `robe` part, mirroring `PlayerRenderer.renderCape`'s
     *  math: project the cape-position lag onto the body's forward direction, plus a
     *  walking-bob sine, plus a crouching offset. Side flaps inherit via being children.
     *
     *  For [AbstractClientPlayer] we read the same `xCloak`/`yCloak`/`zCloak` fields the
     *  vanilla cape uses — those have the lag-spring baked in by the player's per-tick
     *  update, so the flap settles and overshoots naturally. For other entities (mobs in
     *  custom armor, etc.) we approximate the lag with `-deltaMovement`. */
    private fun applyCoatTailFlap(model: HumanoidModel<LivingEntity>, entity: LivingEntity) {
        val robe = try {
            model.body.getChild("robe")
        } catch (_: NoSuchElementException) {
            return
        }

        if (isInventoryRender(entity)) {
            // Coat-tail's rest pose is the baked-in tilt — not zero.
            robe.xRot = BASE_ROBE_XROT
            return
        }

        val partialTick = Minecraft.getInstance().frameTime
        val partialD = partialTick.toDouble()

        // X/Y/Z position lag in world space. Player path uses vanilla cape fields
        // directly. Vertical lag (`dy`) makes the coat-tail react to jumps/falls/elytra
        // pitch — mirrors vanilla cape's `e * 10` vertical term.
        val dx: Double
        val dy: Double
        val dz: Double
        if (entity is AbstractClientPlayer) {
            dx = Mth.lerp(partialD, entity.xCloakO, entity.xCloak) - Mth.lerp(partialD, entity.xo, entity.x)
            dy = Mth.lerp(partialD, entity.yCloakO, entity.yCloak) - Mth.lerp(partialD, entity.yo, entity.y)
            dz = Mth.lerp(partialD, entity.zCloakO, entity.zCloak) - Mth.lerp(partialD, entity.zo, entity.z)
        } else {
            // Cape lag emulation: cape lags BEHIND motion, so subtract velocity.
            val vel = entity.deltaMovement
            dx = -vel.x
            dy = -vel.y
            dz = -vel.z
        }

        // Project lag onto body-forward direction. Sign convention matches vanilla cape:
        //   forward_x = sin(yaw),  forward_z = -cos(yaw)
        // → forwardFlap > 0 when the entity is moving forward (cape lag points backward).
        val yawDeg = Mth.lerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
        val yawRad = yawDeg * Mth.DEG_TO_RAD
        val sinYaw = Mth.sin(yawRad).toDouble()
        val negCosYaw = -Mth.cos(yawRad).toDouble()
        var forwardFlap = ((dx * sinYaw + dz * negCosYaw) * 100.0).coerceIn(0.0, 150.0)
        // Vertical-lag term, mirroring vanilla cape's `e * 10` contribution. Positive on
        // ascent (player up faster than cape) → coat-tail tilts back more; negative on
        // descent. Clamped so a long fall doesn't fling the coat-tail past horizontal.
        val verticalLag = (dy * 8.0).coerceIn(-10.0, 25.0)

        // Walking bob + crouching, matching vanilla cape exactly.
        var verticalFlap = 0.0
        if (entity is AbstractClientPlayer) {
            val bob = Mth.lerp(partialTick, entity.oBob, entity.bob)
            val walkDist = Mth.lerp(partialTick, entity.walkDistO, entity.walkDist)
            // abs() the walking bob so the sine trough doesn't briefly subtract from the
            // back-tilt. Amplitude reduced from vanilla cape's 32° to 8° — the coat-tail
            // should only need a small rhythmic flutter, not a cape-sized swish.
            verticalFlap += Math.abs(Mth.sin(walkDist * 6.0f)) * 8.0f * bob.toDouble()
        }
        // Crouching offset is added below, outside the movement clamp.

        // Total adjustment in DEGREES (vanilla's o/2 + n), converted to rad and SUBTRACTED
        // from the baked-in resting tilt. The robe wrapper carries a Z=π flip from the
        // artist's Blockbench export, which inverts the world-direction of xRot — so a
        // positive flap value (meaning "tilt back" in world) becomes a negative delta on
        // the local xRot. Vanilla also adds 6° as the cape's natural hang offset; we skip
        // it because BASE_ROBE_XROT already gives the resting pose.
        // The legs swing ±1.4·limbSwingAmount rad on the walk cycle — peaks around 56° at
        // walking pace, 80° at sprint. The visible back-tilt needs to stay above those by
        // a small margin, not by a lot, or the coat-tail looks rigid/exaggerated.
        // Vanilla cape uses forwardFlap*0.5; we use 0.8 to push past the walking leg
        // peak. Combined with the small (8°) abs-rectified bob, walking visible tilt
        // lands around 65-73° (above 56° leg), sprint at 84-93° (above 80° leg).
        // Clamp at the ground-sprint practical peak. Without this, elytra dives and any
        // other super-velocity state (riptide, etc.) drive `forwardFlap` way past sprint's
        // ~87 and the coat-tail flips up almost flush with the back.
        // Ground sprint = 87 · 0.95 + 8 ≈ 91°, so 95° gives a thin safety margin without
        // letting flight states go beyond what the player sees during normal sprinting.
        val movementDeg = (forwardFlap * 0.95 + verticalFlap + verticalLag).coerceAtMost(95.0)
        val crouchDeg = if (entity.isCrouching) 4.0 else 0.0
        // Ambient wind — same projection used for the hood. Coat-tail has the biggest
        // surface area of any cloth piece, so it gets the strongest billow: gain 13° per
        // wind unit (≈ ±18° peak at full strength). Applied *outside* the movement clamp
        // so wind can lightly lift the coat-tail above sprinting peaks when idle.
        val wind = computeWind(entity, partialTick)
        val windDeg = wind.forward * 13.0
        val adjustDeg = movementDeg + crouchDeg + windDeg
        robe.xRot = BASE_ROBE_XROT - adjustDeg.toFloat() * Mth.DEG_TO_RAD
    }

    companion object {
        /** Resting xRot baked into the robe wrapper part by [TightRobeArmorModel]'s
         *  `meshWithVanillaHelmetAndCoatTails`. The artist's re-export sets this to -15°
         *  (-0.2618 rad); the Z=π flip on the wrapper means the world-visible tilt is
         *  forward despite the negative sign. */
        private const val BASE_ROBE_XROT: Float = -0.2618f

        /** Items in this tag fire a 0.1-second Mystic Wind pulse on use. */
        private val MYSTIC_WIND_INFLUENCER: TagKey<Item> = TagKey.create(
            Registries.ITEM,
            ResourceLocation("enderkinesis", "mystic_wind_influencer"),
        )

        /** Duration of one Mystic Wind pulse in nanoseconds — 100 ms. */
        private const val MYSTIC_WIND_DURATION_NS: Long = 100_000_000L

        /** Peak strength of the body-forward boost during a Mystic Wind
         *  pulse. The chaotic envelope multiplier oscillates in
         *  `[0.45, 1.0]` of this around 0.7 — so the visible peak is
         *  exactly this and the trough is ~45% of it. */
        private const val MYSTIC_WIND_STRENGTH: Double = 3.0

        /** Ease-in ramp length — the boost is multiplied by
         *  `smoothstep(0, EASE_IN, elapsed)` so a fresh pulse starts at
         *  0 and reaches full intensity smoothly instead of snapping. */
        private const val MYSTIC_WIND_EASE_IN_NS: Long = 80_000_000L                 // 80 ms

        /** Tail-off ramp length — `smoothstep(0, EASE_OUT, untilEnd)`
         *  fades the boost out as the pulse window closes. 0.1 s = same
         *  scale as a single refresh window so the wind naturally bleeds
         *  off when right-click is released or the swing ends. */
        private const val MYSTIC_WIND_EASE_OUT_NS: Long = 100_000_000L              // 100 ms

        // Tuned for "did I just see that?" subtle — the cap is the dominant
        // shape control; the gains keep the spring inside it under all but
        // the most violent inputs.
        /** Natural frequency (rad/s). 13 = ~2 Hz settle, gentle wobble. */
        private const val SCHOLAR_HEAD_YAW_OMEGA: Float = 13f
        /** Damping ratio (unitless). 0.18 = visibly under-damped without
         *  ringing for too many cycles after a single kick. */
        private const val SCHOLAR_HEAD_YAW_DAMPING: Float = 0.18f
        /** Head-yaw-rate → angular-impulse conversion. A 180°/s snap-turn
         *  produces `-180 × this` rad/s² of trailing impulse on the spring;
         *  with the spring constants above that peaks around 0.8° of lag. */
        private const val SCHOLAR_HEAD_YAW_RATE_GAIN: Float = 0.012f
        /** Lateral acceleration → angular impulse. Lower than the rate gain
         *  because lateral lag swings larger raw values during normal walk. */
        private const val SCHOLAR_HEAD_LATERAL_GAIN: Float = 0.6f
        /** Mystic Wind chaotic-shimmer → angular impulse. Combined with the
         *  signed-sin envelope this peaks around the cap during a fresh
         *  pulse, then bleeds off with the ease-out. */
        private const val SCHOLAR_HEAD_WIND_GAIN: Float = 1.5f
        /** Soft cap on the yaw spring offset, in degrees. Hard clamp — the
         *  spring keeps integrating but [pos] is held at ±this. */
        private const val SCHOLAR_HEAD_YAW_CAP_DEG: Float = 3.5f

        /** Register the right-click + raw-mouse hooks that trip Mystic
         *  Wind pulses. Called once from the Fabric client init. */
        @JvmStatic
        fun registerEvents(renderer: TightRobeArmorRenderer) {
            InteractionEvent.RIGHT_CLICK_ITEM.register { player, hand ->
                val stack = player.getItemInHand(hand)
                if (stack.`is`(MYSTIC_WIND_INFLUENCER)) {
                    renderer.refreshMysticWind(player)
                }
                CompoundEventResult.pass()
            }
            // Left-click coverage is handled by attack-key polling inside
            // [computeWind] rather than a raw-input hook —
            // `ClientRawInputEvent.MOUSE_CLICKED_PRE` only fires when a
            // Screen is open, not for in-game clicks.
        }
    }

    /** Lazy bake of the witch-hat root. The mesh contains a head pivot with
     *  brim + hat_1/2/3 cone stack as children — see
     *  [TightRobeArmorModel.createWitchHatLayer]. */
    private fun obtainWitchHatRoot(): ModelPart {
        return witchHatRoot ?: Minecraft.getInstance().entityModels
            .bakeLayer(TightRobeArmorModel.WITCH_HAT_LAYER)
            .also { witchHatRoot = it }
    }

    /** Renders the Blue Witch hat (brim + 3-segment cone) over the helmet's
     *  head. The witch-hat layer has its own head root — we copy the live
     *  helmet head's transform onto it so the hat tracks the player's head
     *  rotation exactly, then apply per-piece jiggle physics before the
     *  draw. Uses the dedicated `blue_witch_hat.png` texture at 128×128. */
    private fun renderWitchHat(
        matrices: PoseStack,
        vertexConsumers: MultiBufferSource,
        light: Int,
        helmetHead: ModelPart,
        entity: LivingEntity,
        hasGlint: Boolean,
    ) {
        val hatHead = obtainWitchHatRoot()
        // Mirror the helmet head's pose so the hat sits on the player's
        // head and rotates with it. Both share the same vanilla pivot.
        hatHead.xRot = helmetHead.xRot
        hatHead.yRot = helmetHead.yRot
        hatHead.zRot = helmetHead.zRot
        hatHead.x = helmetHead.x
        hatHead.y = helmetHead.y
        hatHead.z = helmetHead.z
        hatHead.visible = true

        applyWitchHatJiggle(hatHead, entity)

        val texture = ResourceLocation("enderkinesis", "textures/entity/blue_witch_hat.png")
        val cutoutType = RenderType.armorCutoutNoCull(texture)
        val buffer = ItemRenderer.getArmorFoilBuffer(vertexConsumers, cutoutType, false, hasGlint)
        hatHead.render(matrices, buffer, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f)
    }

    /** Jiggle physics for the witch hat. The brim gets a *very slight*
     *  oscillation on all axes (much smaller gains than the End Cult hood);
     *  hat_2 and hat_3 share the hood's response curves but with gravity
     *  droop so the cone tip droops backward when the wearer pitches up. */
    private fun applyWitchHatJiggle(hatHead: ModelPart, entity: LivingEntity) {
        val brim = try { hatHead.getChild("brim") } catch (_: NoSuchElementException) { return }
        val hat1 = try { hatHead.getChild("hat_1") } catch (_: NoSuchElementException) { return }
        val hat2 = try { hat1.getChild("hat_2") } catch (_: NoSuchElementException) { return }
        val hat3 = try { hat2.getChild("hat_3") } catch (_: NoSuchElementException) { return }

        if (isInventoryRender(entity)) {
            resetState(brimJiggle[entity.uuid])
            resetState(hat2Jiggle[entity.uuid])
            resetState(hat3Jiggle[entity.uuid])
            brim.xRot = 0f; brim.zRot = 0f
            hat2.xRot = 0f; hat2.zRot = 0f
            hat3.xRot = 0f; hat3.zRot = 0f
            return
        }

        val inputs = gatherJiggleInputs(entity)

        // Brim — heavy floppy-cloth feel with a rigid rim. Low omega +
        // light damping so it oscillates lazily after a kick (multiple
        // bounces, slow return); raised impulse gains so the brim
        // genuinely catches the wearer's motion. Angular caps stay tight
        // (±7° pitch, ±5° roll) so the brim never tips far enough off
        // hat_1's cone base to reveal the texture gap underneath.
        // Mass above the pivot ⇒ both pitch and roll axes are inverted.
        stepJiggle(
            state = brimJiggle.getOrPut(entity.uuid) { JiggleState() },
            forwardLag = inputs.forwardLag, lateralLag = inputs.lateralLag, dy = inputs.dy,
            stepPhase = inputs.stepPhase, limbSwingAmount = inputs.limbSwingAmount,
            headPitchDeg = inputs.headPitchDeg, headYawDeg = inputs.headYawDeg,
            windForward = inputs.windForward, windLateral = inputs.windLateral,
            omegaX = 6f, dampingX = 0.22f,
            motionImpulseGainX = 5f, vertImpulseGain = 3.5f,
            stepImpulseGain = 4f, restGainXDeg = 9f,
            gravityGain = 0.15f, droopLimitDeg = 3f,
            windGainXDeg = 4f,
            // Tighter envelope so the brim never tilts off hat_1's cone
            // base and never reveals the underside texture gap.
            capXDeg = 4f, floorXDeg = -4f,
            omegaZ = 6.5f, dampingZ = 0.24f,
            motionImpulseGainZ = 4f, restGainZDeg = 7f,
            headYawImpulseGain = 1.6f,
            windGainZDeg = 3f,
            capZDeg = 3f,
            target = brim,
            xRotSign = -1f, zRotSign = -1f,
        )

        // hat_2 — mid-cone segment. Hood-like response with moderate gravity
        // droop so the cone tilts back when the wearer looks up. Cone mass
        // above the pivot ⇒ both axes inverted.
        stepJiggle(
            state = hat2Jiggle.getOrPut(entity.uuid) { JiggleState() },
            forwardLag = inputs.forwardLag, lateralLag = inputs.lateralLag, dy = inputs.dy,
            stepPhase = inputs.stepPhase, limbSwingAmount = inputs.limbSwingAmount,
            headPitchDeg = inputs.headPitchDeg, headYawDeg = inputs.headYawDeg,
            windForward = inputs.windForward, windLateral = inputs.windLateral,
            // Higher pitch + roll damping per the user's "more resistance"
            // tuning pass — hat_2's mid-cone settles faster than hat_3.
            omegaX = 11f, dampingX = 0.30f,
            motionImpulseGainX = 4f, vertImpulseGain = 3f,
            stepImpulseGain = 5.5f, restGainXDeg = 24f,
            // Stiff forward droop on hat_2 — looking straight down only
            // pushes the mid-cone ~3.6° forward, not the old 20°. hat_3
            // (longer lever from head pivot) still droops more.
            gravityGain = 0.45f, droopLimitDeg = 3.6f,
            windGainXDeg = 6f,
            // Pitch envelope ±22.5° (in final xRot, after inversion). With
            // xRotSign = -1f the cap maps to -22.5° final and the floor
            // maps to +22.5° final → symmetric ±22.5° in the user-facing
            // angle convention.
            capXDeg = 22.5f, floorXDeg = -22.5f,
            omegaZ = 12f, dampingZ = 0.32f,
            motionImpulseGainZ = 3f, restGainZDeg = 9f,
            headYawImpulseGain = 1.8f,
            windGainZDeg = 4f,
            capZDeg = 8f,
            target = hat2,
            xRotSign = -1f, zRotSign = -1f,
        )

        // hat_3 — cone tip. Lighter / springier than hat_2, with more
        // gravity droop (longer lever from head pivot) and bigger caps —
        // the tip swings more visibly when motion impulses arrive.
        // Cone mass above the pivot ⇒ both axes inverted.
        stepJiggle(
            state = hat3Jiggle.getOrPut(entity.uuid) { JiggleState() },
            forwardLag = inputs.forwardLag, lateralLag = inputs.lateralLag, dy = inputs.dy,
            stepPhase = inputs.stepPhase + 0.5f, limbSwingAmount = inputs.limbSwingAmount,
            headPitchDeg = inputs.headPitchDeg, headYawDeg = inputs.headYawDeg,
            windForward = inputs.windForward, windLateral = inputs.windLateral,
            // Higher damping (by half of hat_2's increase) so hat_3 still
            // swings more freely than hat_2 but settles a bit faster than
            // before.
            omegaX = 14f, dampingX = 0.15f,
            motionImpulseGainX = 6f, vertImpulseGain = 4f,
            stepImpulseGain = 10f, restGainXDeg = 36f,
            gravityGain = 0.65f, droopLimitDeg = 26f,
            windGainXDeg = 9f,
            // Pitch envelope: -35° back / +25° forward (final xRot, after
            // inversion). Cap = 35 → final xRot = -35° (max back). Floor
            // = -25 → final xRot = +25° (max forward).
            capXDeg = 35f, floorXDeg = -25f,
            omegaZ = 15f, dampingZ = 0.17f,
            motionImpulseGainZ = 4f, restGainZDeg = 12f,
            headYawImpulseGain = 2.2f,
            windGainZDeg = 6f,
            capZDeg = 10f,
            target = hat3,
            xRotSign = -1f, zRotSign = -1f,
        )
    }

    private fun obtainModel(useLeggings: Boolean): HumanoidModel<LivingEntity> {
        val em = Minecraft.getInstance().entityModels
        return if (useLeggings) {
            outerModel ?: HumanoidModel<LivingEntity>(em.bakeLayer(TightRobeArmorModel.OUTER_LAYER))
                .also { outerModel = it }
        } else {
            innerModel ?: HumanoidModel<LivingEntity>(em.bakeLayer(TightRobeArmorModel.INNER_LAYER))
                .also { innerModel = it }
        }
    }

    /** Per-slot piece visibility, mirroring vanilla `HumanoidArmorLayer.setPartVisibility`.
     *  Reset skipDraw flags every call — the model is cached across frames/entities, so
     *  flags set by [renderHelmetWithTranslucentHead] must not leak into the next render. */
    private fun setPartVisibility(model: HumanoidModel<LivingEntity>, slot: EquipmentSlot) {
        model.setAllVisible(false)
        model.head.skipDraw = false
        model.body.skipDraw = false
        when (slot) {
            EquipmentSlot.HEAD -> {
                model.head.visible = true
                model.hat.visible = true
            }
            EquipmentSlot.CHEST -> {
                model.body.visible = true
                model.rightArm.visible = true
                model.leftArm.visible = true
            }
            EquipmentSlot.LEGS -> {
                model.body.visible = true
                model.rightLeg.visible = true
                model.leftLeg.visible = true
            }
            EquipmentSlot.FEET -> {
                model.rightLeg.visible = true
                model.leftLeg.visible = true
            }
            else -> {}
        }
    }
}
