package org.shipwrights.enderkinesis.client

import net.minecraft.client.model.PlayerModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import org.shipwrights.enderkinesis.entity.WikLakHostEntity

/** [PlayerModel] subclass for the Wik-Lak Host. Drives the attack-swing
 *  animation from the entity's self-owned [WikLakHostEntity.clientSwingStartTick]
 *  timer instead of vanilla's `attackAnim` → `model.attackTime` chain — that
 *  chain was observed to not produce visible motion despite damage being
 *  dealt; we sidestep it entirely by recomputing `this.attackTime` from our
 *  own pulse before `super.setupAnim` runs, then letting vanilla's
 *  `HumanoidModel.setupAttackAnimation` swing the main arm against the
 *  forced value. On top of vanilla's swing we then:
 *
 *   1. Mirror the main-arm attack xRot delta onto the off-arm so the host
 *      pumps **both** fists on every hit.
 *   2. Lean the torso forward ~20° through the same envelope, head counter-
 *      rotated so the gaze stays level in world space.
 *   3. Re-copy the body / head / arm parts onto the jacket / hat / sleeves
 *      overlays so the player-skin layers track our post-super pose
 *      adjustments (PlayerModel's own copyFrom runs inside super, before
 *      our mutations). */
class WikLakHostModel(root: ModelPart, slim: Boolean) : PlayerModel<WikLakHostEntity>(root, slim) {

    override fun setupAnim(
        entity: WikLakHostEntity,
        limbSwing: Float,
        limbSwingAmount: Float,
        ageInTicks: Float,
        netHeadYaw: Float,
        headPitch: Float,
    ) {
        val swingFraction = computeSwingFraction(entity, ageInTicks)
        if (swingFraction > 0f) {
            // Force vanilla's setupAttackAnimation (called inside super) to
            // see our timer instead of whatever the renderer set attackTime
            // to. This guarantees the main arm swings in lock-step with our
            // pulse, even when the vanilla swing-broadcast → client
            // attackAnim path is failing for any reason.
            this.attackTime = swingFraction
        }

        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch)

        // Lean has its own envelope — peaks fast alongside the arm punch,
        // decays slowly over [LEAN_SETTLE_TICKS] so the body keeps the
        // forward commitment well after the fists snap back. The off-arm
        // mirror still tracks the (short) swing window only.
        val leanFraction = computeLeanFraction(entity, ageInTicks)

        if (swingFraction > 0f) {
            // Reproduce vanilla's xRot delta for the attacking arm
            // (HumanoidModel.setupAttackAnimation: `modelpart.xRot -= f1 * 1.2F + f2`)
            // so we can add the same amount to the OTHER arm.
            val f = 1f - swingFraction
            val ff = 1f - f * f * f * f
            val f1 = Mth.sin(ff * Math.PI.toFloat())
            val f2 = Mth.sin(swingFraction * Math.PI.toFloat()) * -(this.head.xRot - 0.7f) * 0.75f
            val attackArmXRotDelta = -(f1 * 1.2f + f2)

            val offArm = if (entity.mainArm == HumanoidArm.LEFT) this.rightArm else this.leftArm
            offArm.xRot += attackArmXRotDelta
        }

        // Always apply hip lean (even at theta = 0). [applyHipLean] writes
        // body.z and head.z via direct assignment; calling it every tick is
        // what keeps those fields from drifting (vanilla setupAnim doesn't
        // reset them). At theta = 0 the writes are 0 → no visual change but
        // any drift from previous frames is cleared.
        applyHipLean(leanFraction * LEAN_RADIANS)

        // PlayerModel.setupAnim copies the bare body / head / arms / legs
        // into the overlay parts as its last step. Re-run those copies so
        // our post-super pose adjustments propagate to the visible skin.
        this.leftSleeve.copyFrom(this.leftArm)
        this.rightSleeve.copyFrom(this.rightArm)
        this.jacket.copyFrom(this.body)
        this.hat.copyFrom(this.head)
    }

    /** Bend the upper body (head + body + arms) forward at the hips by
     *  `theta` radians. Each upper-body part is rotated by `theta` AND
     *  translated so the rotation appears to pivot at the hip line
     *  (HumanoidModel y = 12 in part-local coords) rather than each part's
     *  own pivot. Without the translation, `body.xRot` alone rotates the
     *  torso around the neck/shoulder line and the hips kick backward —
     *  not a lean.
     *
     *  Geometry: for a part with pivot at (x_p, y_p, 0), rotating around
     *  the X-axis through (any, 12, 0) by `theta` moves the pivot to
     *  `(x_p, 12 - (12-y_p)·cos θ, -(12-y_p)·sin θ)`. The translation delta
     *  applied here is the difference between that and the original pivot.
     *  Legs are at y_p = 12 (already on the hip line) so they stay put. */
    private fun applyHipLean(theta: Float) {
        val cosT = Mth.cos(theta)
        val sinT = Mth.sin(theta)

        // Head & body pivots both sit at y = 0 — 12 units above the hip.
        val torsoY = HIP_Y * (1f - cosT)
        val torsoZ = -HIP_Y * sinT

        // body.z / head.z use direct assignment (not +=) because vanilla
        // setupAnim does NOT reset these fields each tick — `+=` would
        // accumulate every frame and send the torso flying off into the
        // distance after a handful of attacks. The .y / .xRot fields ARE
        // reset by vanilla so `+=` is correct for those.
        this.body.xRot += theta
        this.body.y += torsoY
        this.body.z = torsoZ

        this.head.xRot += theta
        this.head.y += torsoY
        this.head.z = torsoZ

        // Shoulders sit at y = 2 — 10 units above the hip. Arm .z IS reset
        // each tick (vanilla setupAnim sets it to 0, or setupAttackAnimation
        // sets it to ±sin(body.yRot)*5 during attack), so `+=` is correct.
        val armY = (HIP_Y - SHOULDER_Y) * (1f - cosT)
        val armZ = -(HIP_Y - SHOULDER_Y) * sinT

        this.leftArm.xRot += theta
        this.leftArm.y += armY
        this.leftArm.z += armZ

        this.rightArm.xRot += theta
        this.rightArm.y += armY
        this.rightArm.z += armZ
    }

    /** Compute the in-progress swing fraction (0..1) from the entity's
     *  client-captured swing-start tick. `ageInTicks` is
     *  `entity.tickCount + partialTick`, so subtracting the start tick
     *  gives floating-point elapsed ticks with sub-frame precision and the
     *  animation interpolates smoothly between server ticks. Returns 0 when
     *  no swing has been observed yet or the swing window has expired. */
    private fun computeSwingFraction(entity: WikLakHostEntity, ageInTicks: Float): Float {
        val start = entity.clientSwingStartTick
        if (start < 0) return 0f
        val elapsed = ageInTicks - start.toFloat()
        if (elapsed < 0f) return 0f
        if (elapsed >= WikLakHostEntity.SWING_DURATION_TICKS.toFloat()) return 0f
        return elapsed / WikLakHostEntity.SWING_DURATION_TICKS.toFloat()
    }

    /** Lean envelope (0..1):
     *   - 0..[LEAN_PEAK_TICKS]: sin ease-in to 1 — matches the timing of the
     *     arm punch so the lunge lands in lock-step with the fists.
     *   - [LEAN_PEAK_TICKS]..[LEAN_SETTLE_TICKS]: quadratic ease-out from 1
     *     back to 0 — the body keeps the forward commitment and unwinds
     *     slowly, well after the arms have snapped back.
     *  Returns 0 outside `[0, LEAN_SETTLE_TICKS]`. */
    private fun computeLeanFraction(entity: WikLakHostEntity, ageInTicks: Float): Float {
        val start = entity.clientSwingStartTick
        if (start < 0) return 0f
        val elapsed = ageInTicks - start.toFloat()
        if (elapsed < 0f) return 0f
        if (elapsed < LEAN_PEAK_TICKS) {
            return Mth.sin((elapsed / LEAN_PEAK_TICKS) * (Math.PI.toFloat() / 2f))
        }
        if (elapsed < LEAN_SETTLE_TICKS) {
            val t = (elapsed - LEAN_PEAK_TICKS) / (LEAN_SETTLE_TICKS - LEAN_PEAK_TICKS)
            val inv = 1f - t
            return inv * inv
        }
        return 0f
    }

    companion object {
        /** Forward body-lean at the apex of an attack swing, radians.
         *  0.70 rad ≈ 40° — the host throws its full weight into every
         *  punch. */
        private const val LEAN_RADIANS: Float = 0.70f

        /** Tick at which the lean envelope reaches its peak. Matches the
         *  apex of the arm punch so the commitment reads as a single
         *  motion. */
        private const val LEAN_PEAK_TICKS: Float = 2f

        /** Tick at which the lean envelope has fully returned to upright.
         *  20 ticks ≈ 1 s — the body keeps leaning forward for nearly a
         *  full second after the arms have already recovered. */
        private const val LEAN_SETTLE_TICKS: Float = 20f

        /** HumanoidModel-coordinate Y at which the legs attach (the hip
         *  line). The lean rotates the upper body around this line. */
        private const val HIP_Y: Float = 12f

        /** HumanoidModel-coordinate Y of the arm part pivots (shoulders). */
        private const val SHOULDER_Y: Float = 2f
    }
}
