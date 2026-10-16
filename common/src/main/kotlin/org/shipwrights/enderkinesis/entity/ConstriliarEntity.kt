package org.shipwrights.enderkinesis.entity

import com.mojang.logging.LogUtils
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.BodyRotationControl
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.JukeboxBlockEntity
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.joml.Vector3i
import org.shipwrights.enderkinesis.blockentity.ConstriliarPlatformBlockEntity
import org.shipwrights.enderkinesis.physics.ConstriliarShipController
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKSounds
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.sounds.SoundEvent
import org.shipwrights.enderkinesis.util.ShipBlockMove
import org.shipwrights.enderkinesis.util.ShipMoveListener
import org.valkyrienskies.core.api.attachment.getOrPutAttachment
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.getShipObjectManagingPos
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.world.clipIncludeShips
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.util.settings
import org.valkyrienskies.mod.common.BlockStateInfo
import java.util.EnumSet
import java.util.UUID

/**
 * The Constriliar — a four-legged walking platform that follows its owner and carries a
 * 1-layer VS2 ship on its back. The shipyard "platform" block is the entity's back plate;
 * the entity kinematically drives the (static) ship's pose every tick, so the cargo tilts
 * and turns with the body.
 */
class ConstriliarEntity(type: EntityType<out ConstriliarEntity>, level: Level) :
    PathfinderMob(type, level), ShipMoveListener {

    var ownerUUID: UUID? = null

    private var commandedTarget: Vec3? = null
    private var commandedTicks: Int = 0

    /** Face-only STANDING order ([commandFaceToward]) — held by [FaceOrderGoal]
     *  (which owns the LOOK flag, locking out idle looks) until a move order or a
     *  fresh face order supersedes it. Unlike [commandedTarget], never drives nav. */
    private var faceTarget: Vec3? = null

    /** Surface normal snapshotted when the face order was issued — the aim's base
     *  frame. A LIVE surface sample under a pitched body compounds with the aim into
     *  a runaway, and world-up flattens wall-standers off their wall; the snapshot is
     *  stable AND surface-true (floor, wall, or ceiling). */
    private val faceBaseUp = Vector3d(0.0, 1.0, 0.0)

    /** Directly-constructed aim orientation (forward → target, up → closest to
     *  [faceBaseUp]) — authoritative while a face order stands. The yaw+fold pipeline
     *  assumes an upright body and can't aim from a wall. */
    private var faceAimRotation: Quaterniond? = null

    /** Ticks without felt support while aiming; past the grace the aim POSE yields
     *  (the order stands) so the body recovers instead of freezing mid-air in the
     *  aimed orientation. */
    private var faceGroundlessTicks = 0

    /** True while the aim pose is actually held this tick (grounded or in grace). */
    private var facePoseActive = false

    /** Ship anchor for [faceTarget]: when the staff's gaze landed on a SHIP block,
     *  the stare TRACKS the block's world position through the ship's motion. */
    private var faceAnchorShipId: Long = -1L
    private val faceAnchorLocal = Vector3d()

    fun commandFaceTowardShipPoint(shipId: Long, local: org.joml.Vector3dc, worldNow: Vec3) {
        commandFaceToward(worldNow)
        faceAnchorShipId = shipId
        faceAnchorLocal.set(local)
    }

    /** Re-derive a tracked face target from the live ship transform. */
    private fun refreshTrackedFaceTarget(server: ServerLevel) {
        if (faceAnchorShipId == -1L) return
        val ship = server.shipObjectWorld.loadedShips.getById(faceAnchorShipId) ?: return
        val v = ship.shipToWorld.transformPosition(Vector3d(faceAnchorLocal))
        faceTarget = Vec3(v.x, v.y, v.z)
    }

    /** Face-order elevation (deg) computed by the constriliar's OWN look-at — vanilla
     *  LookControl assumes an upright entity (eye on a vertical body, no roll) and
     *  can't aim a full-attitude plate. Consumed by the attitude fold. */
    private var faceAimPitchDeg: Float = 0f

    /** Aim-trim integrator (world-frame axis-angle): a PD-only mover settles where
     *  any constant torque balances it — SHORT of the commanded aim. While a face
     *  order stands, integrate the desired-vs-ACTUAL orientation error and overshoot
     *  the command by it, so the physical body converges on the true aim. */
    private val aimTrim = Vector3d()

    private fun aimTrimmedRotation(
        desired: Quaterniond,
        actual: org.joml.Quaterniondc,
    ): Quaterniond {
        if (faceTarget == null || !facePoseActive) {
            aimTrim.mul(AIM_TRIM_DECAY)
            return desired
        }
        val qErr = Quaterniond(desired).mul(Quaterniond(actual).invert()).normalize()
        var ex = qErr.x * 2.0
        var ey = qErr.y * 2.0
        var ez = qErr.z * 2.0
        if (qErr.w < 0.0) { ex = -ex; ey = -ey; ez = -ez }
        aimTrim.add(ex * AIM_TRIM_RATE, ey * AIM_TRIM_RATE, ez * AIM_TRIM_RATE)
        val len = aimTrim.length()
        if (len > AIM_TRIM_MAX) aimTrim.mul(AIM_TRIM_MAX / len)
        if (aimTrim.lengthSquared() < 1.0e-12) return desired
        val axis = Vector3d(aimTrim).normalize()
        return Quaterniond().rotationAxis(aimTrim.length(), axis.x, axis.y, axis.z).mul(desired)
    }

    /** The body BLOCK's centre — the aim origin ("eye"): the plate rides [smoothUp]
     *  above the legs, so the block on top is the body's true centre. */
    private fun bodyBlockCentre(): Vec3 = Vec3(
        x + smoothUp.x * AIM_ORIGIN_UP,
        y + smoothUp.y * AIM_ORIGIN_UP,
        z + smoothUp.z * AIM_ORIGIN_UP,
    )

    /** Constriliar look-at: point the body's FORWARD axis (from the body-block
     *  centre) at [t]. Yaw steps in the world frame; the full elevation lands in
     *  [faceAimPitchDeg] for the attitude fold, which rotates about body-right —
     *  forward onto the target, up staying as close to the surface-up as possible
     *  (the roll-free aim). No vanilla LookControl anywhere in the loop. */
    private fun tickFaceAim(t: Vec3) {
        val origin = bodyBlockCentre()
        val dx = t.x - origin.x
        val dy = t.y - origin.y
        val dz = t.z - origin.z
        stepBodyYaw(yawToward(dx, dz), SIT_TURN_SPEED)
        yHeadRot = yBodyRot
        val horizontal = Math.sqrt(dx * dx + dz * dz)
        faceAimPitchDeg = (-Math.toDegrees(Math.atan2(dy, horizontal))).toFloat()
    }

    /** Any-surface pathing for the ship body (server). Vanilla navigation remains only
     *  for the shipless fallback. */
    private val surfaceNav = ConstriliarSurfaceNavigator()

    /** Support normal of the current path node, fed into the attitude so wall/ceiling
     *  segments deliberately reorient the body instead of waiting on the probe. */
    private val pathUp = Vector3d(0.0, 1.0, 0.0)
    private var pathUpActive = false

    private var shipId: Long = NO_SHIP
    private var platformPos: BlockPos? = null
    private var shipUnloadedTicks: Int = 0
    private var lastMassOp = "world load"
    private var lastMassOpTick = 0
    private var inertiaRepairGaveUp = false
    private var shipMissingTicks: Int = 0
    private var stuckWakeTicks: Int = 0
    private val serverGait = ConstriliarServerGait()

    private var jumpState: ConstriliarJumpState = ConstriliarJumpState.Idle
    private var strutsWereActive = false

    // Hard-stall recovery anchor: survives replans (the navigator's own stall clock
    // resets on every replan, so a moving owner masks a physically pinned body).
    private var stallAnchor: Vec3? = null
    private var stallAnchorTicks = 0

    /** Refreshed by a once-a-second poll; vanilla's setRecordPlayingNearby is
     *  client-only, so the server must find playing jukeboxes itself. */
    private var dancing = false

    private var lastAcknowledgeAt = Long.MIN_VALUE / 2
    private var arrivalCheered = false
    private var panicked = false

    /** An aimed sentry holds its pose and does not dance. */
    private fun isDancing(): Boolean = dancing && faceTarget == null

    /** A playing jukebox within range — world grid AND ships (including our own back:
     *  a jukebox carried as cargo is a portable boombox). Distance is checked in each
     *  frame's own coordinates; rigid transforms preserve it. */
    private fun findPlayingJukebox(level: ServerLevel): Boolean {
        if (scanJukeboxChunks(level, position())) return true
        val box = AABB(position(), position()).inflate(DANCE_RANGE)
        for (ship in level.getShipsIntersecting(box)) {
            val local = ship.worldToShip.transformPosition(Vector3d(x, y, z))
            if (scanJukeboxChunks(level, Vec3(local.x, local.y, local.z))) return true
        }
        return false
    }

    private fun scanJukeboxChunks(level: ServerLevel, centre: Vec3): Boolean {
        val minCx = Mth.floor(centre.x - DANCE_RANGE) shr 4
        val maxCx = Mth.floor(centre.x + DANCE_RANGE) shr 4
        val minCz = Mth.floor(centre.z - DANCE_RANGE) shr 4
        val maxCz = Mth.floor(centre.z + DANCE_RANGE) shr 4
        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
                for ((pos, be) in chunk.blockEntities) {
                    if (be is JukeboxBlockEntity && be.isRecordPlaying &&
                        pos.distToCenterSqr(centre.x, centre.y, centre.z) < DANCE_RANGE_SQ
                    ) {
                        return true
                    }
                }
            }
        }
        return false
    }

    // Raw surface normal (unpitched) and the smoothed body up-vector derived from it
    // (server authoritative, synced for render + used for ship pose).
    private val surfaceUp = Vector3d(0.0, 1.0, 0.0)
    private val smoothUp = Vector3d(0.0, 1.0, 0.0)

    /** Gap and direction of the nearest attachable surface from the last six-side probe. */
    private var attachedGap = Double.MAX_VALUE
    private var attachedDir: Direction? = null

    // Client-side interpolation of the synced up-vector.
    private val clientUpPrev = Vector3f(0f, 1f, 0f)
    private val clientUpCur = Vector3f(0f, 1f, 0f)

    /** Client-only procedural leg state; never touched server-side. */
    val gait: ConstriliarGait by lazy { ConstriliarGait() }

    // Client-only blink timing.
    private var clientBlinkTicks = 0
    private var clientBlinkCooldown = 80

    /** Client-local intro clock: seeded once from the synced countdown, then advanced
     *  locally — rendering straight off the synced int stuttered with packet batching
     *  and fast-forwarded when the entity arrived late. -1 = not started. */
    private var clientIntroTicks = -1f

    init {
        setMaxUpStep(1.0f)
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_CLIMBING, false)
        entityData.define(DATA_SITTING, false)
        entityData.define(DATA_BODY_BLOCK, java.util.Optional.empty())
        entityData.define(DATA_SPAWN_ANIM, 0)
        entityData.define(DATA_AIRBORNE, false)
        entityData.define(DATA_STRAINED, false)
        entityData.define(DATA_HAS_SHIP, false)
        entityData.define(DATA_PLATFORM_POS, java.util.Optional.empty())
        entityData.define(DATA_UP_X, 0f)
        entityData.define(DATA_UP_Y, 1f)
        entityData.define(DATA_UP_Z, 0f)
    }

    /** True while the back ship exists — the platform BER renders the body then. */
    fun hasBackShip(): Boolean = entityData.get(DATA_HAS_SHIP)

    /** Client-side id of the back ship (via the synced platform shipyard pos), so foot
     *  rays can exclude it — feet must never plant on the walker's own cargo. */
    fun clientOwnShipId(): Long? {
        val pPos = entityData.get(DATA_PLATFORM_POS).orElse(null) ?: return null
        return level().getShipObjectManagingPos(pPos)?.id
    }

    /** Ship this entity must never collide with (its own back ship) — consumed by
     *  [org.shipwrights.enderkinesis.mixin.EntityShipCollisionOwnShipMixin] on both
     *  sides (client movement is predicted). */
    fun ownShipIdForCollision(): Long? =
        if (level().isClientSide) clientOwnShipId() else ownShipOrNull()

    /** Client: the pose the body is drawn at. The intro is entity-anchored and eases
     *  onto the ship's render transform over the settle, so the platform renderer
     *  takes over without a jump. Without a client ship, the entity pose. */
    fun bodyRenderPose(partialTick: Float): Pair<Vec3, Quaternionf> {
        val ship = shipRenderPose()
        if (!introActive() && ship != null) return ship
        val origin = bodyOrigin(partialTick)
        val q = bodyQuat(partialTick)
        val w = introShipBlend(partialTick)
        if (ship == null || w <= 0f) return Pair(origin, q)
        return Pair(origin.lerp(ship.first, w.toDouble()), q.slerp(ship.second, w))
    }

    // The brain rides at ground level, so the default eye probe sat inside the ground on
    // any tilt or step and drew the body black. Both renderers read this probe.
    override fun getLightProbePosition(partialTicks: Float): Vec3 {
        if (!level().isClientSide) return super.getLightProbePosition(partialTicks)
        val (origin, q) = bodyRenderPose(partialTicks)
        val up = q.transform(Vector3f(0f, 1f, 0f))
        return origin.add(up.x * LIGHT_PROBE_UP, up.y * LIGHT_PROBE_UP, up.z * LIGHT_PROBE_UP)
    }

    private fun introShipBlend(partialTick: Float): Float {
        val t = spawnAnimTime(partialTick)
        if (t < ANIM_JUMP_END) return 0f
        val p = ((t - ANIM_JUMP_END) / (SPAWN_ANIM_TOTAL - ANIM_JUMP_END)).toFloat().coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    /** The ship's render transform as a body pose: model ground plane = platform
     *  centre, rotation = ship ∘ 180°-yaw. */
    private fun shipRenderPose(): Pair<Vec3, Quaternionf>? {
        val pPos = entityData.get(DATA_PLATFORM_POS).orElse(null) ?: return null
        val ship = level().getShipObjectManagingPos(pPos)
            as? org.valkyrienskies.core.api.ships.ClientShip ?: return null
        val rt = ship.renderTransform
        val centre = Vector3d(pPos.x + 0.5, pPos.y + 0.5, pPos.z + 0.5)
        rt.shipToWorld.transformPosition(centre)
        val rot = rt.shipToWorldRotation
        val q = Quaternionf(rot.x().toFloat(), rot.y().toFloat(), rot.z().toFloat(), rot.w().toFloat())
            .rotateY(Math.PI.toFloat())
        return Pair(Vec3(centre.x, centre.y, centre.z), q)
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, CommandedMoveGoal())
        goalSelector.addGoal(2, FollowOwnerGoal())
        // No wander — it's a pet: it follows, sits, or goes where commanded. A sitting
        // constriliar holds perfectly still (no idle looking, which drags body yaw);
        // only a staff order turns it, via tickSittingTurn.
        // Standing face order: owns the LOOK flag for as long as the order exists,
        // so the idle looks below can never drag the gaze off an aimed target. Turn
        // pauses while pathing (walk yaw drives the body mid-route) and resumes.
        goalSelector.addGoal(5, FaceOrderGoal())
        goalSelector.addGoal(
            6,
            object : LookAtPlayerGoal(this, Player::class.java, 8f) {
                override fun canUse(): Boolean = !isSitting() && super.canUse()
                override fun canContinueToUse(): Boolean = !isSitting() && super.canContinueToUse()
            },
        )
        goalSelector.addGoal(
            7,
            object : RandomLookAroundGoal(this) {
                override fun canUse(): Boolean = !isSitting() && super.canUse()
                override fun canContinueToUse(): Boolean = !isSitting() && super.canContinueToUse()
            },
        )
    }

    override fun createNavigation(level: Level): PathNavigation = WallClimberNavigation(this, level)

    // Vanilla's "moving" test reads hover jitter as motion and snaps yBodyRot back to
    // yRot, flipping the ship's desired yaw at idle; with a back ship, gait code owns yaw.
    override fun createBodyControl(): BodyRotationControl = object : BodyRotationControl(this) {
        override fun clientTick() {
            if (!hasBackShip()) super.clientTick()
        }
    }

    override fun onClimbable(): Boolean = entityData.get(DATA_CLIMBING)

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false

    /** The physics bbox stays 0.45 tall to clear the cargo layer, but the rendered
     *  plate spans 0.5–0.75 — inflate the pick box so aiming at the visible body
     *  actually targets the entity (picking only; collision untouched). */
    override fun getPickRadius(): Float = 0.4f

    /** The block this body was animated from — its texture wraps the plate. */
    fun bodyBlockState(): net.minecraft.world.level.block.state.BlockState =
        entityData.get(DATA_BODY_BLOCK).orElse(Blocks.PURPUR_BLOCK.defaultBlockState())

    fun setBodyBlock(state: net.minecraft.world.level.block.state.BlockState) {
        entityData.set(DATA_BODY_BLOCK, java.util.Optional.of(state))
        cachedStrengthScale = 0.0
    }

    fun isSitting(): Boolean = entityData.get(DATA_SITTING)

    private fun setSitting(sitting: Boolean, sound: Boolean = true) {
        if (sound && sitting != isSitting()) voice(if (sitting) EKSounds.CONSTRILIAR_SIT else EKSounds.CONSTRILIAR_STAND, 0.6f)
        entityData.set(DATA_SITTING, sitting)
        if (sitting) stopNavigating()
    }

    /** Squat pose: keep the pick/interaction box hugging the lowered body. */
    override fun getDimensions(pose: net.minecraft.world.entity.Pose): net.minecraft.world.entity.EntityDimensions =
        if (isSitting()) SITTING_DIMENSIONS else super.getDimensions(pose)

    /** Low eye (0.6 × height, vs vanilla's 0.85): while sitting, the cargo hovers just
     *  0.25 above the origin, and the default eye sat close enough for routine spring
     *  settle to suffocate it against its own load. */
    override fun getStandingEyeHeight(
        pose: net.minecraft.world.entity.Pose,
        dimensions: net.minecraft.world.entity.EntityDimensions,
    ): Float = dimensions.height * 0.6f

    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        if (accessor == DATA_SITTING) refreshDimensions()
    }

    /** Surface → model ground plane. Sitting drops the plane so the plate bottom rests
     *  on the ground (the squat); the ship spring eases the cargo down with it. During
     *  the spawn animation the lift is choreographed: squashed on the ground, rising
     *  with the legs, then a brief overshoot whose spring settle is the happy bob. */
    fun currentLift(): Double {
        val remaining = entityData.get(DATA_SPAWN_ANIM)
        if (remaining > 0) return spawnAnimLift((SPAWN_ANIM_TOTAL - remaining).toDouble())
        // Jump crouch: the spring compresses — hover target drops, legs fold via IK.
        if (jumpState is ConstriliarJumpState.Crouch) return JUMP_CROUCH_LIFT
        val base = if (isSitting()) SIT_LIFT else BODY_LIFT
        // Aiming UP rears the body — ride height climbs with the aim steepness so the
        // nose pitches skyward instead of the tail grinding into the ground.
        val rear = if (faceTarget != null && faceAimPitchDeg < 0f) {
            AIM_REAR_LIFT * Math.min(1.0, (-faceAimPitchDeg / FACE_PITCH_FOLD_MAX_DEG).toDouble())
        } else {
            0.0
        }
        return base + rear
    }

    /** Continuous intro lift curve — one function for the server's ship target and the
     *  client's fractional-tick render, so neither quantizes: smoothstep rise, crouch,
     *  sine-arc hop, then the ship spring's settle provides the happy bob. */
    fun spawnAnimLift(t: Double): Double = when {
        t < ANIM_RISE_START -> SIT_LIFT
        t < ANIM_RISE_END -> {
            val p = (t - ANIM_RISE_START) / (ANIM_RISE_END - ANIM_RISE_START)
            SIT_LIFT + (BODY_LIFT - SIT_LIFT) * (p * p * (3.0 - 2.0 * p))
        }
        t < ANIM_JUMP_START -> BODY_LIFT - (BODY_LIFT - JUMP_CROUCH_LIFT) *
            Math.sin(Math.PI * (t - ANIM_RISE_END) / (ANIM_JUMP_START - ANIM_RISE_END))
        t < ANIM_JUMP_END -> BODY_LIFT +
            ANIM_JUMP_HEIGHT * Math.sin(Math.PI * (t - ANIM_JUMP_START) / (ANIM_JUMP_END - ANIM_JUMP_START))
        else -> BODY_LIFT
    }

    fun isSpawnAnimating(): Boolean = entityData.get(DATA_SPAWN_ANIM) > 0

    /** Once the local clock runs it alone decides: the synced countdown reaches 0 a
     *  sync later, and that gap left a frame where neither renderer drew the body. */
    fun introActive(): Boolean =
        if (clientIntroTicks >= 0f) clientIntroTicks < SPAWN_ANIM_TOTAL else entityData.get(DATA_SPAWN_ANIM) > 0

    /** Elapsed intro time from the CLIENT clock; negative when not playing. */
    fun spawnAnimTime(partialTick: Float): Float {
        if (clientIntroTicks < 0f || clientIntroTicks >= SPAWN_ANIM_TOTAL) return -1f
        return clientIntroTicks + partialTick
    }

    /** Kick off the block-awakens intro; called by the scroll and the spawn egg. */
    fun startSpawnAnimation() {
        entityData.set(DATA_SPAWN_ANIM, SPAWN_ANIM_TOTAL)
        voice(EKSounds.CONSTRILIAR_AWAKEN, 1f)
    }

    /** Random idle blinking, client-side only (purely cosmetic). */
    fun isBlinking(): Boolean = clientBlinkTicks > 0

    /** Airborne with no surface in hover reach — the gait flails instead of planting. */
    fun isFlailing(): Boolean = entityData.get(DATA_AIRBORNE)

    /** Carrying past the comfortable mass budget — the legs tremble. */
    fun isStrained(): Boolean = entityData.get(DATA_STRAINED)

    /** Staff of Command order: walk to [target]; a sitting constriliar only turns
     *  toward it. */
    fun commandMoveTo(target: Vec3) {
        acknowledge()
        arrivalCheered = false
        commandedTarget = target
        commandedTicks = 0
        faceTarget = null
        faceAnchorShipId = -1L
    }

    /** Staff "Aim To": turn in place toward [target] without walking. Cancels any
     *  in-flight go-to so the two orders don't fight over the body yaw. */
    fun commandFaceToward(target: Vec3) {
        acknowledge()
        commandedTarget = null
        faceTarget = target
        faceAnchorShipId = -1L
        faceBaseUp.set(surfaceUp)   // aim relative to the surface being stood on NOW
        navigation.stop()
        surfaceNav.stop()
    }

    private fun acknowledge() {
        val now = level().gameTime
        if (now - lastAcknowledgeAt < ACKNOWLEDGE_GAP_TICKS) return
        lastAcknowledgeAt = now
        playSound(EKSounds.CONSTRILIAR_ACKNOWLEDGE.get(), 0.5f, 0.9f + random.nextFloat() * 0.2f)
    }

    private fun voice(event: RegistrySupplier<SoundEvent>, volume: Float) {
        playSound(event.get(), volume, 1f)
    }

    override fun getAmbientSound(): SoundEvent? = if (isSitting()) null else EKSounds.CONSTRILIAR_AMBIENT.get()

    override fun playAmbientSound() {
        getAmbientSound()?.let { playSound(it, AMBIENT_VOLUME, voicePitch) }
    }

    // die() plays it at its own volume.
    override fun getDeathSound(): SoundEvent? = null

    override fun die(source: net.minecraft.world.damagesource.DamageSource) {
        if (!dead && !level().isClientSide) voice(EKSounds.CONSTRILIAR_DEATH, 0.8f)
        super.die(source)
    }

    // Navigation facade: surface A* when the ship body exists, vanilla otherwise.
    private fun orderMoveTo(dest: Vec3) {
        if (hasBackShip()) {
            updateNavClearance()
            // Other constriliars are obstacles along the WHOLE route (their ships are
            // colliding physics bodies — two walkers pressing into the same corridor
            // deadlock), and their standing spots are excluded from goal selection so
            // pets sent to the same place park beside each other.
            surfaceNav.moveTo(level(), blockPosition(), dest, ownShipOrNull(), gatherOccupied(dest), this, smoothUp)
        } else {
            navigation.moveTo(dest.x, dest.y, dest.z, FOLLOW_SPEED)
        }
    }

    /** Pathing must respect the carried ship's real extent: lateral cargo spread widens
     *  the footprint. Measured from the actual cargo-layer blocks (chebyshev, so it's
     *  yaw-independent) rather than the ship AABB, whose inclusive/exclusive max made a
     *  bare walker demand 3-wide corridors. */
    private fun updateNavClearance() {
        val level = level() as? ServerLevel ?: return
        val pPos = platformPos ?: return
        val aabb = level.shipObjectWorld.loadedShips.getById(shipId)?.shipAABB ?: return
        var radius = 0
        var budget = CARGO_SCAN_BUDGET
        val cursor = BlockPos.MutableBlockPos()
        val cargoY = pPos.y + 1
        for (xx in aabb.minX()..aabb.maxX()) {
            for (zz in aabb.minZ()..aabb.maxZ()) {
                if (budget-- <= 0) break
                cursor.set(xx, cargoY, zz)
                if (!level.getBlockState(cursor).isAir) {
                    radius = maxOf(radius, Math.abs(xx - pPos.x), Math.abs(zz - pPos.z))
                }
            }
        }
        surfaceNav.setClearance(radius, 2)
    }

    /** Boid separation: while holding/approaching a commanded spot, push gently away from
     *  nearby commanded siblings — in the surface tangent plane so it slides along the hull
     *  rather than lifting off. Layers on the navigator's per-slot destination spread so a
     *  group parks shoulder-to-shoulder instead of overlapping. */
    private fun tickCommandSeparation() {
        separationVel.zero()
        if (commandedTarget == null || isSitting()) return
        val neighbors = level().getEntitiesOfClass(
            ConstriliarEntity::class.java, boundingBox.inflate(SEPARATION_RADIUS),
        ) { it !== this && it.commandedTarget != null }
        if (neighbors.isEmpty()) return
        val me = position()
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (n in neighbors) {
            val dx = me.x - n.x; val dy = me.y - n.y; val dz = me.z - n.z
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > SEPARATION_RADIUS * SEPARATION_RADIUS) continue
            if (d2 < 1.0e-4) {
                // Exactly stacked — break the tie with a deterministic per-id direction.
                val a = id * GOLDEN_ANGLE
                sx += Math.cos(a); sz += Math.sin(a)
                continue
            }
            val d = Math.sqrt(d2)
            val w = (1.0 - d / SEPARATION_RADIUS) / d
            sx += dx * w; sy += dy * w; sz += dz * w
        }
        // Keep the push in the surface plane so it never nudges the walker off the hull.
        val dot = sx * surfaceUp.x + sy * surfaceUp.y + sz * surfaceUp.z
        sx -= surfaceUp.x * dot; sy -= surfaceUp.y * dot; sz -= surfaceUp.z * dot
        val len = Math.sqrt(sx * sx + sy * sy + sz * sz)
        if (len < 1.0e-4) return
        val push = Math.min(len, 1.0) * SEPARATION_STRENGTH
        if (hasBackShip()) {
            // The brain is a ghost on the ship body: only the drive velocity (m/s) moves it.
            separationVel.set(sx, sy, sz).mul(push * TICKS_PER_SECOND / len)
        } else {
            deltaMovement = deltaMovement.add(sx / len * push, sy / len * push, sz / len * push)
        }
    }

    private val separationVel = Vector3d()

    private fun gatherOccupied(dest: Vec3): List<Vec3> = level().getEntitiesOfClass(
        ConstriliarEntity::class.java,
        net.minecraft.world.phys.AABB(position(), dest).inflate(OCCUPIED_QUERY_RADIUS),
    ) { it !== this }.map { it.position() }

    private fun stopNavigating() {
        surfaceNav.stop()
        navigation.stop()
    }

    fun owner(): Player? = ownerUUID?.let { level().getPlayerByUUID(it) }

    /** Moves the brain to an owner in a different dimension; [tickShip] then brings the
     *  body. True when this entity is now the removed original. */
    private fun followOwnerAcrossDimensions(): Boolean {
        if (tickCount % DIMENSION_FOLLOW_INTERVAL != 0) return false
        if (isSitting() || isSpawnAnimating() || commandedTarget != null || faceTarget != null) return false
        val uuid = ownerUUID ?: return false
        val level = level() as? ServerLevel ?: return false
        val owner = level.server.playerList.getPlayer(uuid) ?: return false
        val target = owner.serverLevel()
        if (target === level || owner.isSpectator || !owner.isAlive) return false
        return teleportTo(target, owner.x, owner.y, owner.z, emptySet(), yRot, xRot) && isRemoved
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide) {
            if (shapeDestroyed) {
                kill()
                return
            }
            if (followOwnerAcrossDimensions()) return
            if (tickCount % 20 == 0) {
                val wasDancing = isDancing()
                dancing = hasBackShip() && findPlayingJukebox(level() as ServerLevel)
                if (isDancing() && !wasDancing) voice(EKSounds.CONSTRILIAR_HAPPY, 0.6f)
            }
            // The spawn intro is rendered on the ENTITY (clients have it immediately);
            // the ship rendering takes over when the countdown ends, by which time the
            // ship has had the whole intro to load in.
            val animRemaining = entityData.get(DATA_SPAWN_ANIM)
            if (animRemaining > 0) entityData.set(DATA_SPAWN_ANIM, animRemaining - 1)
            updateAttitude()
            // Climb only when the world-side probe confirms a wall. Raw
            // horizontalCollision also fires on contact with the walker's own cargo
            // ship, which turned the carried ship into an infinite ladder.
            entityData.set(
                DATA_CLIMBING,
                horizontalCollision && attachedDir?.axis?.isHorizontal == true && attachedGap < CLIMB_WALL_GAP,
            )
            tickSittingTurn()
            tickIntroTurn()
            tickCommandSeparation()
            tickShip()
            if (isStrained() && tickCount % STRAIN_SOUND_TICKS == 0) voice(EKSounds.CONSTRILIAR_STRAIN, 0.5f)
        } else {
            clientUpPrev.set(clientUpCur)
            clientUpCur.set(entityData.get(DATA_UP_X), entityData.get(DATA_UP_Y), entityData.get(DATA_UP_Z))
            val introRemaining = entityData.get(DATA_SPAWN_ANIM)
            if (introRemaining > 0 && clientIntroTicks < 0f) {
                clientIntroTicks = (SPAWN_ANIM_TOTAL - introRemaining).toFloat()
            } else if (clientIntroTicks in 0f..(SPAWN_ANIM_TOTAL - 1).toFloat()) {
                clientIntroTicks += 1f
            }
            if (clientIntroTicks in 0f..(SPAWN_ANIM_TOTAL - 1).toFloat()) {
                ConstriliarIntroFx.tick(this, clientIntroTicks)
            }
            gait.tick(this)
            if (clientBlinkTicks > 0) {
                clientBlinkTicks--
            } else if (--clientBlinkCooldown <= 0) {
                clientBlinkTicks = BLINK_LENGTH
                clientBlinkCooldown = BLINK_MIN_GAP + random.nextInt(BLINK_GAP_SPREAD)
            }
        }
    }

    /** Staff "Aim To" as a proper goal: keeps the body turned toward the standing
     *  [faceTarget] every tick — re-turning after knockback, ship drift, or a walk —
     *  instead of converging once and letting idle looks wander off. */
    private inner class FaceOrderGoal : Goal() {
        init { flags = java.util.EnumSet.of(Flag.LOOK) }
        override fun canUse(): Boolean = faceTarget != null
        override fun canContinueToUse(): Boolean = canUse()
        override fun requiresUpdateEveryTick(): Boolean = true
        override fun tick() {
            (level() as? ServerLevel)?.let { refreshTrackedFaceTarget(it) }
            faceTarget?.let { tickFaceAim(it) }
        }
    }

    /** A sitting constriliar honours a staff order by turning to face the spot. */
    private fun tickSittingTurn() {
        val target = commandedTarget ?: return
        if (!isSitting()) return
        if (turnBodyToward(target)) commandedTarget = null
    }

    /** Awakening: turn to the owner while rising, so the eyes open facing them on the hop. */
    private fun tickIntroTurn() {
        val remaining = entityData.get(DATA_SPAWN_ANIM)
        if (remaining <= 0) return
        val t = SPAWN_ANIM_TOTAL - remaining
        if (t < ANIM_RISE_START || t >= ANIM_JUMP_START) return
        val target = owner() ?: return
        stepBodyYaw(yawToward(target.x - x, target.z - z), INTRO_TURN_SPEED)
        yHeadRot = yBodyRot
    }

    /** One tick of clamped body-yaw turn toward [target]; true once aligned. */
    private fun turnBodyToward(target: Vec3): Boolean {
        val delta = stepBodyYaw(yawToward(target.x - x, target.z - z), SIT_TURN_SPEED)
        yHeadRot = yBodyRot
        lookControl.setLookAt(target.x, target.y, target.z)
        return Math.abs(delta) < 4f
    }

    /** MC yaw (degrees) of the horizontal direction (dx, dz). */
    private fun yawToward(dx: Double, dz: Double): Float = Math.toDegrees(Math.atan2(dz, dx)).toFloat() - 90f

    /** One clamped body-yaw step toward [wantYaw]; returns the error before the step. */
    private fun stepBodyYaw(wantYaw: Float, maxStep: Float): Float {
        val delta = Mth.wrapDegrees(wantYaw - yBodyRot)
        yBodyRot += Mth.clamp(delta, -maxStep, maxStep)
        yRot = yBodyRot
        return delta
    }

    private fun ownShipOrNull(): Long? = shipId.takeIf { it != NO_SHIP }

    /** Interpolated body up-vector for rendering. */
    fun upVector(partialTick: Float): Vector3f =
        Vector3f(clientUpPrev).lerp(clientUpCur, partialTick).normalize()

    /** Client-side lift: during the intro it reads the LOCAL clock at fractional time —
     *  the synced-int countdown quantizes per tick and made the legs stutter against
     *  the smoothly rising body. */
    fun clientLift(partialTick: Float): Double {
        val t = spawnAnimTime(partialTick)
        if (t >= 0f) return spawnAnimLift(t.toDouble())
        return if (isSitting()) SIT_LIFT else BODY_LIFT
    }

    /** Where the model's ground plane sits: the body rides [BODY_LIFT] up the surface
     *  normal so the belly always clears the tracked surface by about half a block. */
    fun bodyOrigin(partialTick: Float): Vec3 {
        val up = upVector(partialTick)
        val pos = getPosition(partialTick)
        val lift = clientLift(partialTick)
        return pos.add(up.x * lift, up.y * lift, up.z * lift)
    }

    /** Body rotation (tilt ∘ vanilla 180°-yaw) matching the renderer's model transform. */
    fun bodyQuat(partialTick: Float): Quaternionf {
        val up = upVector(partialTick)
        val yaw = Mth.rotLerp(partialTick, yBodyRotO, yBodyRot)
        return Quaternionf().rotationTo(0f, 1f, 0f, up.x, up.y, up.z)
            .rotateY(Math.toRadians(180.0 - yaw).toFloat())
    }

    // ------------------------------------------------------------------ attitude

    /** Smoothly steer [smoothUp] toward the local surface normal. Face orders build the
     *  aim basis directly; only an explicit move order folds the look pitch in. */
    private fun updateAttitude() {
        // The intro plays grid-square like the block it was: no surface alignment (and
        // no look-tilt) until the hop-up, which doubles as the "coming alive" beat.
        val animRemaining = entityData.get(DATA_SPAWN_ANIM)
        if (animRemaining > 0 && SPAWN_ANIM_TOTAL - animRemaining < ANIM_JUMP_START) {
            surfaceUp.set(WORLD_UP)
            smoothUp.set(WORLD_UP)
            syncUp()
            return
        }
        // Deep underwater there is no surface to stand on — level out and let buoyancy
        // (driveShip's submerged hover) carry it up.
        val serverLevel = level() as? ServerLevel
        if (serverLevel != null && isPlateSubmerged(serverLevel)) {
            surfaceUp.set(WORLD_UP)
            smoothUp.lerp(WORLD_UP, ATTITUDE_LERP).normalize()
            syncUp()
            return
        }
        updateSurfaceUp(sampleContactUp())
        val target = Vector3d(surfaceUp)
        faceAimRotation = buildFaceAim(target)
        foldMovePitch(target)
        smoothUp.lerp(target, ATTITUDE_LERP).normalize()
        syncUp()
    }

    private fun syncUp() {
        entityData.set(DATA_UP_X, smoothUp.x.toFloat())
        entityData.set(DATA_UP_Y, smoothUp.y.toFloat())
        entityData.set(DATA_UP_Z, smoothUp.z.toFloat())
    }

    /** The SDF field's gradient IS the surface normal — continuous around every edge
     *  and corner, real collision geometry (stairs, slabs, other ships' hulls). Also
     *  feeds the attachment data (climb gate, stick fallback). Null when detached. */
    private fun sampleContactUp(): Vector3d? {
        val sample = ConstriliarSurfaceField.sample(
            level(), this, boundingBox.center, SDF_RANGE, ownShipOrNull(), mergeBlocks = true,
        )
        attachedGap = sample?.distance ?: Double.MAX_VALUE
        attachedDir = sample?.let { Direction.getNearest(-it.up.x, -it.up.y, -it.up.z) }
        return sample?.takeIf { it.distance < SDF_ATTACH_DIST }?.up
    }

    private fun updateSurfaceUp(contactUp: Vector3d?) {
        if (pathUpActive) {
            // Deliberate wall/ceiling traversal: average the path node's support normal
            // with the felt contact — commitment to the target face, but the
            // orientation wraps gradually as actual contact shifts over the corner.
            surfaceUp.set(pathUp)
            if (contactUp != null) {
                surfaceUp.add(contactUp)
                // Opposed normals (ceiling path, floor contact) cancel out — the path
                // face wins rather than normalizing a near-zero vector.
                if (surfaceUp.lengthSquared() > 1.0e-4) surfaceUp.normalize() else surfaceUp.set(pathUp)
            }
        } else if (faceTarget != null && !isFlailing()) {
            // Aiming: live SDF contact under a pitched body compounds with the aim
            // into a runaway, so the pose is based on a SNAPSHOT normal — but the
            // snapshot must track reality: standing on a DIFFERENT face re-bases the
            // aim there, and losing support entirely yields the pose (the order
            // stands; it resumes on landing) instead of freezing the faller in the
            // aimed orientation.
            if (contactUp != null) {
                if (contactUp.dot(faceBaseUp) < FACE_REBASE_DOT) faceBaseUp.set(contactUp)
                faceGroundlessTicks = 0
                facePoseActive = true
                surfaceUp.set(faceBaseUp)
            } else if (++faceGroundlessTicks < FACE_GROUNDLESS_GRACE) {
                facePoseActive = true
                surfaceUp.set(faceBaseUp)
            } else {
                facePoseActive = false
                surfaceUp.lerp(WORLD_UP, 0.2).normalize()
            }
        } else if (contactUp != null) {
            surfaceUp.set(contactUp)
        } else {
            // Airborne: relax back to world-up.
            surfaceUp.lerp(WORLD_UP, 0.2).normalize()
        }
    }

    /** Face orders: the aim orientation, built directly — forward at the target, up as
     *  close to the order-time surface normal as the aim permits (elevation clamped to
     *  the fold cap). The yaw+fold pipeline assumes an upright body and can't aim from
     *  walls or ceilings. Writes the aim's up into [target]; null when no aim holds. */
    private fun buildFaceAim(target: Vector3d): Quaterniond? {
        val ft = faceTarget ?: return null
        if (!facePoseActive || isFlailing()) return null
        val origin = bodyBlockCentre()
        val f = Vector3d(ft.x - origin.x, ft.y - origin.y, ft.z - origin.z)
        if (f.lengthSquared() <= 1.0e-6) return null
        f.normalize()
        val capSin = Math.sin(Math.toRadians(FACE_PITCH_FOLD_MAX_DEG.toDouble()))
        var e = f.dot(faceBaseUp)
        if (Math.abs(e) > capSin) {
            // Pull the aim direction back toward the base plane to the cap.
            val fPara = Vector3d(f).sub(Vector3d(faceBaseUp).mul(e))
            val paraLen = fPara.length()
            if (paraLen > 1.0e-6) {
                fPara.mul(Math.sqrt(1.0 - capSin * capSin) / paraLen)
                f.set(fPara).add(Vector3d(faceBaseUp).mul(capSin * Math.signum(e))).normalize()
                e = f.dot(faceBaseUp)
            }
        }
        faceAimPitchDeg = (-Math.toDegrees(Math.asin(e.coerceIn(-1.0, 1.0)))).toFloat()
        val upAim = Vector3d(faceBaseUp).sub(Vector3d(f).mul(e))
        if (upAim.lengthSquared() <= 1.0e-6) return null
        upAim.normalize()
        target.set(upAim)
        val xAxis = Vector3d(upAim).cross(f)
        if (xAxis.lengthSquared() <= 1.0e-6) return null
        xAxis.normalize()
        return Quaterniond().setFromNormalized(
            Matrix3d(
                xAxis.x, xAxis.y, xAxis.z,
                upAim.x, upAim.y, upAim.z,
                f.x, f.y, f.z,
            ),
        )
    }

    /** The look-pitch fold noses the body toward what it faces — but ONLY during the
     *  sitting turn, where [turnBodyToward] aims the look at the commanded spot. A
     *  standing hold keeps [commandedTarget] while idle look goals drive xRot (any
     *  player, head frame), which tilted the body in arbitrary directions. The hard cap
     *  stops an at-your-feet target from folding into a face-plant. Face orders carry
     *  their pitch in the aim basis. */
    private fun foldMovePitch(target: Vector3d) {
        if (faceTarget != null || commandedTarget == null || !isSitting()) return
        val pitch = Math.toRadians(Mth.clamp(xRot, -PITCH_FOLD_MAX_DEG, PITCH_FOLD_MAX_DEG).toDouble())
        if (Math.abs(pitch) <= 1.0e-3) return
        val yawRad = Math.toRadians(yBodyRot.toDouble())
        // Rotate about the body-right axis; negated so positive xRot (looking down)
        // leans the up-vector forward = nose dips.
        target.rotateAxis(-pitch, -Math.cos(yawRad), 0.0, -Math.sin(yawRad))
    }

    /** Deep-submerged: the plate itself sits in fluid with more fluid above it (wading
     *  feet don't count). Drives buoyancy and upright attitude. */
    private fun isPlateSubmerged(level: ServerLevel): Boolean {
        val origin = Vector3d(x, y, z).fma(currentLift(), smoothUp)
        val plateBlock = BlockPos.containing(origin.x, origin.y, origin.z)
        return !level.getFluidState(plateBlock).isEmpty && !level.getFluidState(plateBlock.above()).isEmpty
    }

    /** With a back ship, the ship IS the body — the ghost brain never self-moves
     *  (driveShip snaps it onto the plate). Shipless fallback walks vanilla, with the
     *  Spiders-2.0 stick press toward an attached wall. */
    override fun travel(travelVector: Vec3) {
        if (hasBackShip()) return
        if (!level().isClientSide) {
            // Requires the *closest* surface to be a wall — a tilted blend alone also
            // occurs standing beside a wall on flat ground (floor+wall weights mix to
            // ~45°), where a stick press would grind the walker into the corner.
            val wallAttached = attachedDir?.axis?.isHorizontal == true &&
                attachedGap < STICK_ATTACH_GAP && surfaceUp.y < STICK_UPNESS_MAX
            isNoGravity = wallAttached
            if (wallAttached) {
                deltaMovement = deltaMovement.add(
                    -surfaceUp.x * STICK_ACCEL,
                    -surfaceUp.y * STICK_ACCEL,
                    -surfaceUp.z * STICK_ACCEL,
                )
            }
        }
        super.travel(travelVector)
    }

    /** The ghost brain must never be shoved off its body by other entities. */
    override fun isPushable(): Boolean = !hasBackShip()

    /** Invulnerable to conventional damage — the construct dies only when the platform
     *  block composing its shape is destroyed (or via kill-command/void, which bypass
     *  invulnerability). */
    override fun isInvulnerableTo(source: net.minecraft.world.damagesource.DamageSource): Boolean =
        super.isInvulnerableTo(source) ||
            !source.`is`(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)

    /** Fire-immune (type-level) AND never draws the burning overlay — a construct
     *  wading lava shouldn't flicker. */
    override fun displayFireAnimation(): Boolean = false

    /** Set by the platform block's onRemove; the next tick kills the entity OUTSIDE the
     *  block-removal call stack (releaseShip deletes the ship — not reentrancy-safe
     *  mid-removal). */
    @JvmField
    var shapeDestroyed: Boolean = false

    // ------------------------------------------------------------------ back ship

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (stack.item is BlockItem) {
            if (level().isClientSide) return InteractionResult.SUCCESS
            if (!placeCargo(level() as ServerLevel, player, hand, stack)) {
                return super.mobInteract(player, hand)
            }
            return InteractionResult.CONSUME
        }
        // The Staff of Command commands, never sits: vanilla runs mobInteract BEFORE
        // ItemStack.interactLivingEntity, so pass here to let the staff's handler run.
        if (stack.item is org.shipwrights.enderkinesis.item.StaffOfCommandItem) {
            return InteractionResult.PASS
        }
        // Owner right-click (non-block hand) toggles sit; an ownerless constriliar is
        // claimed by the first such click. The owner check runs server-side only —
        // ownerUUID is never synced, so the client optimistically succeeds.
        if (level().isClientSide) return InteractionResult.SUCCESS
        val claimed = ownerUUID == null
        if (claimed) {
            ownerUUID = player.uuid
            voice(EKSounds.CONSTRILIAR_HAPPY, 0.6f)
        }
        if (player.uuid == ownerUUID) {
            setSitting(!isSitting(), sound = !claimed)
            commandedTarget = null
            return InteractionResult.CONSUME
        }
        return super.mobInteract(player, hand)
    }

    /** Before building a new body, reconnect to an orphaned one: a nearby ship carrying
     *  a platform block that no living constriliar claims. Worlds saved mid-bug (wiped
     *  linkage) left cargo-laden bodies orphaned at the pet's spot — creating a fresh
     *  ship there teleports it INTO the debris pile, pinning both (invisible/unmoving
     *  reload pets). Adoption reclaims the body, cargo and all. */
    private fun adoptOrphanShip(level: ServerLevel): Boolean {
        val search = boundingBox.inflate(ADOPT_RANGE)
        for (ship in level.getShipsIntersecting(search)) {
            val serverShip = level.shipObjectWorld.allShips.getById(ship.id) ?: continue
            var found: BlockPos? = null
            ConstriliarCargo.forEachShipBlock(level, serverShip, CARGO_SCAN_BUDGET) { pos, state ->
                if (found == null && state.`is`(EKBlocks.CONSTRILIAR_PLATFORM.get())) found = pos
            }
            val pPos = found ?: continue
            val claimed = level.getEntitiesOfClass(
                ConstriliarEntity::class.java,
                boundingBox.inflate(CLAIM_CHECK_RANGE),
            ) { it !== this && it.isAlive }.any { it.shipId == ship.id }
            if (claimed) continue
            shipId = ship.id
            platformPos = pPos
            (level.getBlockEntity(pPos) as? ConstriliarPlatformBlockEntity)?.setOwner(this)
            return true
        }
        return false
    }

    private var cachedStrengthScale: Double = 0.0

    /** Strength relative to the body block's mass, on a power curve through two
     *  anchors: a PURPUR body has today's baseline strength, and a NETHERITE body can
     *  carry 1.85 netherite blocks of cargo. Masses come from VS2's live registry;
     *  when a pack leaves the anchors degenerate (equal default masses), a fixed
     *  exponent stands in. Scales the force/torque/jump budgets and the strain line. */
    private fun strengthScale(): Double {
        if (cachedStrengthScale > 0.0) return cachedStrengthScale
        val info = org.valkyrienskies.mod.common.BlockStateInfo
        val body = info.get(bodyBlockState())?.first ?: return 1.0
        val purpur = info.get(Blocks.PURPUR_BLOCK.defaultBlockState())?.first ?: return 1.0
        val netherite = info.get(Blocks.NETHERITE_BLOCK.defaultBlockState())?.first ?: purpur
        val exponent = if (netherite > purpur * ANCHOR_MIN_RATIO) {
            // budget(m) = base·(m/purpur)^k, solved so budget(netherite) holds body
            // plus 1.85 blocks of cargo against gravity.
            val netheriteBudget = ConstriliarShipController.GRAVITY *
                (1.0 + NETHERITE_CARRY_BLOCKS) * netherite
            Math.log(netheriteBudget / ConstriliarShipController.LEG_STRENGTH) /
                Math.log(netherite / purpur)
        } else {
            STRENGTH_EXPONENT_FALLBACK
        }
        val scale = Math.pow(body / purpur, exponent)
            .coerceIn(STRENGTH_SCALE_MIN, STRENGTH_SCALE_MAX)
        cachedStrengthScale = scale
        return scale
    }

    /** Platform state with properties inherited from the animated source block —
     *  currently its light emission. */
    private fun platformBlockState(): net.minecraft.world.level.block.state.BlockState =
        EKBlocks.CONSTRILIAR_PLATFORM.get().defaultBlockState().setValue(
            org.shipwrights.enderkinesis.block.ConstriliarPlatformBlock.LIGHT,
            bodyBlockState().lightEmission,
        )

    /** The platform's mass after inheritance: the source block's mass, or the
     *  platform's own datapack mass when the source has none. */
    private fun inheritedPlatformMass(): Double? {
        val platformMass = BlockStateInfo.get(platformBlockState())?.first ?: return null
        return (BlockStateInfo.get(bodyBlockState())?.first ?: platformMass).coerceAtLeast(1.0)
    }

    private fun createBackShip(level: ServerLevel) {
        if (adoptOrphanShip(level)) return
        val ship = try {
            level.shipObjectWorld.createNewShipAtBlock(
                Vector3i(blockPosition().x, blockPosition().y, blockPosition().z),
                false, 1.0, level.dimensionId,
            )
        } catch (t: Throwable) {
            LOG.warn("[EK constriliar] failed to create back ship for {}", this, t)
            return
        }
        // Born dynamic — the PD controller owns it from the first loaded tick, and a
        // static→dynamic flip risks a stale phys-side static flag muting the spring.
        ship.isStatic = false
        val posInShip = ship.transform.positionInShip
        val pPos = BlockPos(
            Mth.floor(posInShip.x()),
            Mth.floor(posInShip.y()),
            Mth.floor(posInShip.z()),
        )
        level.setBlock(pPos, platformBlockState(), 3)
        (level.getBlockEntity(pPos) as? ConstriliarPlatformBlockEntity)?.also {
            it.setOwner(this)
            it.setSource(bodyBlockState())
        }
        // Full mass inheritance: rewrite the platform's datapack mass (vs_mass) to the
        // animated source block's own mass. Pure inertia adjustment via the core hook the
        // block pipeline itself uses — same type on both sides touches mass only, no voxel
        // rebuild. Applied exactly once per ship: maintenance re-places are net-zero, and
        // every destruction path deletes the ship. Internal VS2 API — may shift.
        val platformMass = BlockStateInfo.get(platformBlockState())?.first
        val sourceMass = inheritedPlatformMass()
        if (platformMass != null && sourceMass != null && sourceMass != platformMass) {
            level.shipObjectWorld.onSetBlock(
                pPos.x, pPos.y, pPos.z, level.dimensionId,
                vsCore.blockTypes.solid, vsCore.blockTypes.solid,
                platformMass, sourceMass,
            )
        }
        noteMassOp("create")
        platformPos = pPos
        shipId = ship.id
        teleportShipToBack(level, ship)
    }

    /** Cargo layer empty (fresh spawn or mined off) — fill it by clicking with a block.
     *  Placement runs through a real [BlockPlaceContext] against the platform's top
     *  face, so all vanilla rules apply: context-sensitive state (stairs, axes),
     *  canSurvive, place sound, and stack consumption. */
    private fun placeCargo(level: ServerLevel, player: Player, hand: InteractionHand, stack: ItemStack): Boolean {
        val pPos = platformPos ?: return false
        if (!level.getBlockState(pPos.above()).canBeReplaced()) return false
        val hit = net.minecraft.world.phys.BlockHitResult(
            Vec3(pPos.x + 0.5, pPos.y + 1.0, pPos.z + 0.5),
            Direction.UP,
            pPos,
            false,
        )
        val context = net.minecraft.world.item.context.BlockPlaceContext(player, hand, stack, hit)
        return (stack.item as BlockItem).place(context).consumesAction()
    }

    /** A VS2 split or weld can move the platform to another ship; the body follows it. */
    @OptIn(VsBeta::class)
    override fun onShipBlocksMoved(level: ServerLevel, move: ShipBlockMove) {
        if (move.followPoint(faceAnchorShipId, faceAnchorLocal)) faceAnchorShipId = move.toShipId
        val platform = platformPos ?: return
        if (shipId != move.fromShipId || !move.contains(platform)) return
        level.shipObjectWorld.loadedShips.getById(shipId)
            ?.getAttachment(ConstriliarShipController::class.java)?.active = false
        shipId = move.toShipId
        platformPos = move.mapBlock(platform)
    }

    private fun tickShip() {
        val level = level() as ServerLevel
        val ship = if (shipId == NO_SHIP) null else level.shipObjectWorld.allShips.getById(shipId)
        if (ship == null) {
            // A known ship id missing from allShips is NOT proof of destruction: on
            // world load the entity can tick before VS2 has its saved ships queryable.
            // Wiping instantly orphaned the real (cargo-laden) body and built a bare
            // replacement — grace it out before declaring the ship lost.
            if (shipId != NO_SHIP && shipMissingTicks < SHIP_MISSING_GRACE_TICKS) {
                shipMissingTicks++
                entityData.set(DATA_HAS_SHIP, false)
                noPhysics = false
                return
            }
            shipId = NO_SHIP
            platformPos = null
            entityData.set(DATA_HAS_SHIP, false)
            entityData.set(DATA_PLATFORM_POS, java.util.Optional.empty())
            // Body gone: back to a normal physical mob until a new ship exists.
            noPhysics = false
            if (tickCount % SHIP_RETRY_INTERVAL == 0) createBackShip(level)
            return
        }
        shipMissingTicks = 0
        if (ship.chunkClaimDimension != level.dimensionId) {
            // The brain changed dimension: the body is still in the old one. A block or
            // drive write here goes to the wrong level at shipyard coordinates.
            entityData.set(DATA_HAS_SHIP, false)
            noPhysics = false
            if (tickCount % DIMENSION_FOLLOW_INTERVAL == 0) teleportShipToBack(level, ship)
            return
        }
        entityData.set(DATA_HAS_SHIP, true)
        entityData.set(DATA_PLATFORM_POS, java.util.Optional.ofNullable(platformPos))
        unstickIfNeverLoading(level, ship)
        if (tickCount % CARGO_SCAN_INTERVAL == 0) validateCargo(level, ship)
        checkInertia(level, ship)
        driveShip(level)
    }

    /** Ship-as-body: the ship IS the constriliar's physical body; this brain entity is a
     *  noPhysics ghost snapped onto it every tick. The controller gets a hover point
     *  (surface sample + ride height along the surface normal), a desired walk velocity
     *  from the navigation path, and the tilt+yaw rotation — everything else is real
     *  physics on the physics thread. */
    private fun driveShip(level: ServerLevel) {
        val pPos = platformPos ?: return
        val loaded = level.shipObjectWorld.loadedShips.getById(shipId) ?: return
        // Created static (frozen while unloaded); the body must be a dynamic rigid body.
        if (loaded.isStatic) loaded.isStatic = false
        // VS2 moves a ship that touches a portal, but the brain is a ghost and stays behind.
        if (loaded.settings.changeDimensionOnTouchPortals) loaded.settings.changeDimensionOnTouchPortals = false
        val platformCentre = Vector3d(pPos.x + 0.5, pPos.y + 0.5, pPos.z + 0.5)
        val plate = loaded.transform.shipToWorld.transformPosition(Vector3d(platformCentre))
        rideBody(loaded, plate)
        stitchPath(level)

        val moveVel = Vector3d()
        val node = walkIntent(level, moveVel)
        moveVel.add(separationVel)

        val controller = loaded.getOrPutAttachment(ConstriliarShipController::class.java) {
            ConstriliarShipController()
        }
        controller.setPlatform(platformCentre)
        val submerged = isPlateSubmerged(level)
        if (submerged) {
            driveSubmerged(level, controller, loaded, plate, moveVel)
        } else {
            tickJump(level, controller, moveVel)
            driveOnSurface(level, controller, loaded, plate, moveVel, node)
        }
        controller.strengthScale = strengthScale()
        controller.publish()
        entityData.set(DATA_STRAINED, loaded.inertiaData.mass > STRAIN_MASS * strengthScale())
        if (submerged) return

        val wantsMotion = moveVel.lengthSquared() > 0.25
        tickInertRescue(level, loaded, wantsMotion)
        tickHardStall(level, controller, node, wantsMotion)
    }

    /** The brain rides the body: snap onto the plate, carry the ship's velocity (the
     *  gait's step-lead reads deltaMovement), never self-collide or take fall damage.
     *  EXCEPT during the spawn intro: the intro renders anchored to the ENTITY, which
     *  must hold rock-still while the (possibly still-loading, spring-lagged) ship
     *  converges to it in the background. */
    private fun rideBody(loaded: ServerShip, plate: Vector3d) {
        noPhysics = true
        if (!isSpawnAnimating()) {
            val lift = currentLift()
            setPos(plate.x - smoothUp.x * lift, plate.y - smoothUp.y * lift, plate.z - smoothUp.z * lift)
            setDeltaMovement(loaded.velocity.x() / 20.0, loaded.velocity.y() / 20.0, loaded.velocity.z() / 20.0)
        } else {
            setDeltaMovement(Vec3.ZERO)
        }
        fallDistance = 0f
        // A ghost never runs move(), so vanilla would never flag it grounded — and
        // GroundPathNavigation only follows/updates paths while onGround.
        setOnGround(true)
    }

    /** Leg stitching: plan the NEXT leg from the current path's end before the walker
     *  runs out — distant targets become one continuous stride instead of
     *  stop-plan-go at every 32-block seam. */
    private fun stitchPath(level: ServerLevel) {
        if (tickCount % STITCH_INTERVAL != 0 || isSitting() || !surfaceNav.needsExtension()) return
        val dest = surfaceNav.currentDestWorld(level) ?: return
        surfaceNav.extendPath(level, ownShipOrNull(), gatherOccupied(dest), this)
    }

    /** Fills [moveVel] with the in-plane walk toward the current path node, with
     *  approach braking so the spring never overshoots waypoints. Returns the node. */
    private fun walkIntent(level: ServerLevel, moveVel: Vector3d): ConstriliarSurfaceNavigator.Node? {
        // Mid face-transition (attitude still rotating toward the path's face) progress
        // is rotational, not positional — the stall detector must not fire on it.
        val transitioning = pathUpActive && smoothUp.dot(pathUp) < TRANSITION_AGREE
        val node = if (isSitting() || isSpawnAnimating()) {
            null
        } else {
            surfaceNav.tickFollow(level, position(), transitioning)
        }
        if (node == null) {
            pathUpActive = false
            return null
        }
        surfaceNav.nodeWorldUp(level, node, pathUp)
        pathUpActive = true
        val cell = surfaceNav.nodeWorldCenter(level, node)
        val to = Vector3d(cell.x - x, cell.y - y, cell.z - z)
        // Full in-plane projection strips exactly the component needed to press
        // into a wall corner or nose over a ledge lip; while the node's face
        // disagrees with the current attitude, keep a share of it so the drive
        // commits into the transition as the attitude rotates.
        val agree = Mth.clamp(smoothUp.dot(pathUp), 0.0, 1.0)
        val keep = TRANSITION_DRIVE_KEEP * (1.0 - agree)
        to.sub(Vector3d(smoothUp).mul(to.dot(smoothUp) * (1.0 - keep)))
        val dist = to.length()
        if (dist <= 1.0e-3) return node
        // Tight squeeze: crawl and square the body to the grid axis — the
        // 1-wide plate only fits a 1-wide gap when it isn't at an angle.
        val tight = surfaceNav.isTight(level, node)
        var speed = Math.min(WALK_SPEED, dist * BRAKE_GAIN + 0.4)
        if (tight) speed = Math.min(speed, TIGHT_SPEED)
        moveVel.set(to).mul(speed / dist)
        // Vanilla body-yaw comes from MoveControl, which the ghost bypasses.
        if (moveVel.x * moveVel.x + moveVel.z * moveVel.z > 0.04) {
            var wantYaw = yawToward(moveVel.x, moveVel.z)
            if (tight) wantYaw = surfaceNav.snapYawToGrid(level, wantYaw)
            stepBodyYaw(wantYaw, WALK_TURN_SPEED)
        }
        return node
    }

    /** Deep-submerged: buoyancy overrides the surface hover — target the fluid surface
     *  straight above and rise to it (walk intent still applies, so it swims). */
    private fun driveSubmerged(
        level: ServerLevel,
        controller: ConstriliarShipController,
        loaded: ServerShip,
        plate: Vector3d,
        moveVel: Vector3d,
    ) {
        var top = BlockPos.containing(plate.x, plate.y, plate.z)
        var budget = FLUID_SURFACE_SCAN_BUDGET
        while (budget-- > 0 && !level.getFluidState(top.above()).isEmpty) top = top.above()
        controller.setHover(plate.x, top.y + 0.8 + currentLift(), plate.z)
        controller.setUp(0.0, 1.0, 0.0)
        controller.setMoveVel(moveVel)
        controller.idealRotation = aimTrimmedRotation(desiredShipRotation(), loaded.transform.shipToWorldRotation)
        controller.strutsActive = false
        strutsWereActive = false
        entityData.set(DATA_AIRBORNE, false)
    }

    /** Gap jump: approach the take-off node instead of steering at the landing (which
     *  is across the gap), compress the spring, launch ballistically, and free-fly. */
    private fun tickJump(level: ServerLevel, controller: ConstriliarShipController, moveVel: Vector3d) {
        when (val jump = jumpState) {
            is ConstriliarJumpState.Idle -> approachJump(level, moveVel)
            is ConstriliarJumpState.Crouch -> {
                moveVel.zero()
                jumpState = when {
                    // A sit, a stop, or a replan during the crouch cancels the jump.
                    isSitting() || surfaceNav.activeJumpSegment()?.second != jump.landing ->
                        ConstriliarJumpState.Idle
                    jump.ticks + 1 < JUMP_CROUCH_TICKS -> jump.copy(ticks = jump.ticks + 1)
                    else -> ConstriliarJumpState.Flight(launchToward(controller, jump.target))
                }
            }
            is ConstriliarJumpState.Flight -> {
                moveVel.zero()
                val ticks = jump.ticks + 1
                jumpState = if (ticks > JUMP_FLIGHT_MAX_TICKS) ConstriliarJumpState.Idle else jump.copy(ticks = ticks)
            }
        }
    }

    private fun approachJump(level: ServerLevel, moveVel: Vector3d) {
        if (isSitting()) return
        val segment = surfaceNav.activeJumpSegment() ?: return
        val takeoff = surfaceNav.nodeWorldCenter(level, segment.first)
        if (position().distanceToSqr(takeoff) < JUMP_TAKEOFF_ARRIVE_SQ) {
            voice(EKSounds.CONSTRILIAR_JUMP, 0.6f)
            jumpState = ConstriliarJumpState.Crouch(segment.second, surfaceNav.nodeWorldCenter(level, segment.second))
            return
        }
        // Re-aim the walk at the take-off, not across the gap.
        val to = Vector3d(takeoff.x - x, takeoff.y - y, takeoff.z - z)
        to.sub(Vector3d(smoothUp).mul(to.dot(smoothUp)))
        val dist = to.length()
        if (dist > 1.0e-3) {
            moveVel.set(to).mul(Math.min(WALK_SPEED, dist * BRAKE_GAIN + 0.4) / dist)
        }
    }

    /** Queues a ballistic launch that lands on [target]; returns the hover-suppress ticks. */
    private fun launchToward(controller: ConstriliarShipController, target: Vec3): Int {
        val dx = target.x - x
        val dz = target.z - z
        val flightTime = JUMP_TIME_BASE + JUMP_TIME_PER_BLOCK * Math.sqrt(dx * dx + dz * dz)
        controller.queueLaunch(
            dx / flightTime,
            (target.y - y) / flightTime + 0.5 * ConstriliarShipController.GRAVITY * flightTime,
            dz / flightTime,
        )
        return Math.max(4, (flightTime * 20.0 * 0.5).toInt())
    }

    private fun driveOnSurface(
        level: ServerLevel,
        controller: ConstriliarShipController,
        loaded: ServerShip,
        plate: Vector3d,
        moveVel: Vector3d,
        node: ConstriliarSurfaceNavigator.Node?,
    ) {
        val lift = currentLift()
        var surface = sampleHoverSurface(level, plate, moveVel, lift)
        // Mid-jump: the hover must not fight the ballistic arc — suppress it until the
        // arc is past its apex, then let it reacquire and cushion the landing.
        val jump = jumpState
        if (jump is ConstriliarJumpState.Flight) {
            if (jump.ticks < jump.suppressTicks) {
                surface = null
            } else if (surface != null) {
                voice(EKSounds.CONSTRILIAR_LAND, 0.6f)
                jumpState = ConstriliarJumpState.Idle
            }
        }
        if (surface != null) {
            // Transition clearance: while the body's orientation still disagrees with
            // the local surface (reorienting between floor/wall/ceiling), ride higher so
            // the tilted plate's corners swing clear instead of digging into blocks.
            val mismatch = 1.0 - Math.max(0.0, smoothUp.dot(surfaceUp))
            val clearance = lift + mismatch * TILT_CLEARANCE
            // Aiming rises along the (re-based) surface snapshot: the pitched live
            // smoothUp displaces the hover target sideways, and world-up would pull a
            // wall-stander off its wall.
            val rise = if (faceTarget != null && facePoseActive) faceBaseUp else smoothUp
            controller.setHover(
                surface.x + rise.x * clearance,
                surface.y + rise.y * clearance,
                surface.z + rise.z * clearance,
            )
        } else {
            controller.hoverActive = false
            // Fell clear (e.g. off a ceiling mid-path): drop the path's attitude claim
            // so the body rights itself toward world-up on the way down, and the legs
            // flail (client reads the flag).
            pathUpActive = false
        }
        val flailing = surface == null
        // The hover is off for a whole jump flight, which is not a fall.
        if (!flailing) {
            panicked = false
        } else if (!panicked && jumpState is ConstriliarJumpState.Idle) {
            panicked = true
            voice(EKSounds.CONSTRILIAR_PANIC, 0.6f)
        }
        entityData.set(DATA_AIRBORNE, flailing)
        controller.setUp(smoothUp.x, smoothUp.y, smoothUp.z)
        controller.setMoveVel(moveVel)
        controller.idealRotation = aimTrimmedRotation(desiredShipRotation(), loaded.transform.shipToWorldRotation)
        if (isDancing() && node == null && !isSitting() && jumpState is ConstriliarJumpState.Idle &&
            controller.hoverActive
        ) {
            applyDance(controller)
        }
        tickStruts(level, controller)
    }

    /** Adaptive ride height: sample the surface under the plate AND ahead along the
     *  travel direction, and return the HIGHEST hit — the body rises before it reaches
     *  a step and planes over single-block pits. Rays skip the body's own blocks; null
     *  means free-fall. */
    private fun sampleHoverSurface(level: ServerLevel, plate: Vector3d, moveVel: Vector3d, lift: Double): Vec3? {
        val upV = Vec3(smoothUp.x, smoothUp.y, smoothUp.z)
        val plateV = Vec3(plate.x, plate.y, plate.z)
        var bestSurface: Vec3? = null
        var bestAlongUp = Double.NEGATIVE_INFINITY
        val moveLen = moveVel.length()
        val samples = if (moveLen > 0.5) HOVER_SAMPLE_COUNT else 1
        for (i in 0 until samples) {
            val ahead = i * HOVER_SAMPLE_SPACING / moveLen.coerceAtLeast(1.0e-6)
            val from = plateV
                .add(moveVel.x * ahead, moveVel.y * ahead, moveVel.z * ahead)
                .add(upV.scale(HOVER_RAY_START_UP))
            val hit = level.clipIncludeShips(
                ClipContext(
                    from,
                    from.add(upV.scale(-(HOVER_RAY_REACH + lift + HOVER_RAY_START_UP))),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY,
                    this,
                ),
                true,
                shipId,
            )
            if (hit.type == HitResult.Type.MISS) continue
            val loc = fluidAdjustedSurface(level, hit.location)
            val along = loc.x * smoothUp.x + loc.y * smoothUp.y + loc.z * smoothUp.z
            if (along > bestAlongUp) {
                bestAlongUp = along
                bestSurface = loc
            }
        }
        return bestSurface
    }

    /** Jukebox dance (idle only): bob left, bounce, bob right, bounce — bobs are a
     *  roll+sway of the hover/rotation targets, bounces are hover-spring pops. The legs
     *  dance for free: the gait re-plants feet under the physically moving body. */
    private fun applyDance(controller: ConstriliarShipController) {
        val t = tickCount % (DANCE_BEAT * 4)
        val phase = t / DANCE_BEAT
        val beat = Math.sin(Math.PI * (t % DANCE_BEAT) / DANCE_BEAT)
        if (phase == 1 || phase == 3) {
            controller.hoverX += smoothUp.x * beat * DANCE_BOUNCE
            controller.hoverY += smoothUp.y * beat * DANCE_BOUNCE
            controller.hoverZ += smoothUp.z * beat * DANCE_BOUNCE
            return
        }
        val dir = if (phase == 0) 1.0 else -1.0
        val rot = controller.idealRotation
        val side = rot.transform(Vector3d(1.0, 0.0, 0.0))
        // Roll about the WORLD-space forward axis — a ship-local axis rolls
        // whatever the flipped model frame points at (it read as a slight nod).
        val forward = rot.transform(Vector3d(0.0, 0.0, 1.0)).normalize()
        controller.hoverX += side.x * dir * beat * DANCE_SWAY
        controller.hoverY += side.y * dir * beat * DANCE_SWAY
        controller.hoverZ += side.z * dir * beat * DANCE_SWAY
        controller.idealRotation = Quaterniond()
            .rotationAxis(dir * beat * DANCE_ROLL, forward.x, forward.y, forward.z)
            .mul(rot, Quaterniond())
    }

    /** Supplemental leg springs: a muscle layer over the hover — off in the special
     *  modes that choreograph the hover target (sit, dance, jump, intro) and when
     *  airborne (stale anchors must not glide the fall). An aiming body is also
     *  hover-carried: the struts' ground-anchored springs apply a righting torque, and
     *  the rotation PD settles where they balance, short of the commanded fold. */
    private fun tickStruts(level: ServerLevel, controller: ConstriliarShipController) {
        val strutsOk = controller.hoverActive && jumpState is ConstriliarJumpState.Idle && !isSitting() &&
            !isDancing() && !isSpawnAnimating() && !isFlailing() && faceTarget == null
        controller.strutsActive = strutsOk
        if (strutsOk && !strutsWereActive) serverGait.reset()
        strutsWereActive = strutsOk
        if (!strutsOk) return
        serverGait.tick(this, level)
        for (i in 0..3) {
            controller.strutAnchorX[i] = serverGait.anchors[i].x
            controller.strutAnchorY[i] = serverGait.anchors[i].y
            controller.strutAnchorZ[i] = serverGait.anchors[i].z
            controller.strutHipX[i] = controller.platformX + HIP_LOCAL_X[i]
            controller.strutHipY[i] = controller.platformY
            controller.strutHipZ[i] = controller.platformZ + HIP_LOCAL_Z[i]
            controller.strutStance[i] = serverGait.isStance(i)
        }
    }

    /** Inert-body rescue: VS2 puts settled bodies to sleep and a sleeping body ignores
     *  listener forces. Wanting motion while the ship sits dead-still for ~0.75 s earns
     *  a snap teleport to the (near-identical) desired pose, which wakes it. */
    private fun tickInertRescue(level: ServerLevel, loaded: ServerShip, wantsMotion: Boolean) {
        if (!wantsMotion || loaded.velocity.lengthSquared() >= 1.0e-4) {
            stuckWakeTicks = 0
            return
        }
        if (++stuckWakeTicks >= STUCK_WAKE_TICKS) {
            stuckWakeTicks = 0
            teleportShipToBack(level, loaded)
        }
    }

    /** Hard-stall recovery: the navigator's stall clock resets on every replan, so a
     *  body pinned at a ledge/corner while chasing a MOVING target never triggers it.
     *  No displacement for ~3 s while wanting motion pops a hop toward the waypoint. */
    private fun tickHardStall(
        level: ServerLevel,
        controller: ConstriliarShipController,
        node: ConstriliarSurfaceNavigator.Node?,
        wantsMotion: Boolean,
    ) {
        if (!wantsMotion || jumpState !is ConstriliarJumpState.Idle) {
            stallAnchor = null
            stallAnchorTicks = 0
            return
        }
        val anchor = stallAnchor
        if (anchor == null || position().distanceToSqr(anchor) > HARD_STALL_DIST_SQ) {
            stallAnchor = position()
            stallAnchorTicks = 0
            return
        }
        if (++stallAnchorTicks < HARD_STALL_TICKS) return
        stallAnchorTicks = 0
        stallAnchor = null
        val waypoint = node?.let { surfaceNav.nodeWorldCenter(level, it) } ?: return
        // In the surface plane plus off the surface: a world-up hop pushed wall and
        // ceiling walkers along or into their own surface.
        val hop = Vector3d(waypoint.x - x, waypoint.y - y, waypoint.z - z)
        hop.sub(Vector3d(smoothUp).mul(hop.dot(smoothUp)))
        hop.mul(HOP_SPEED / hop.length().coerceAtLeast(1.0e-3)).fma(HOP_UP_SPEED, smoothUp)
        controller.queueLaunch(hop.x, hop.y, hop.z)
        voice(EKSounds.CONSTRILIAR_JUMP, 0.6f)
        jumpState = ConstriliarJumpState.Flight(HOP_SUPPRESS_TICKS)
    }

    /** Server-gait access to attitude internals (same package, supplemental legs). */
    internal fun serverUp(): Vector3d = smoothUp
    internal fun serverShipRotation(): Quaterniond = desiredShipRotation()

    /** Server twin of [shipRenderPose] from the physics transform: platform centre and
     *  the ACTUAL ship rotation (no 180° flip). Null when the ship isn't loaded. */
    internal fun serverShipPose(level: ServerLevel): Pair<Vec3, Quaterniond>? {
        val pPos = platformPos ?: return null
        val transform = level.shipObjectWorld.loadedShips.getById(shipId)?.transform ?: return null
        val centre = transform.shipToWorld.transformPosition(Vector3d(pPos.x + 0.5, pPos.y + 0.5, pPos.z + 0.5))
        return Pair(Vec3(centre.x, centre.y, centre.z), Quaterniond(transform.shipToWorldRotation))
    }

    private fun desiredShipRotation(): Quaterniond {
        // A standing face order's directly-constructed basis IS the desired
        // orientation — the up+yaw reconstruction below can't express wall aims.
        faceAimRotation?.let { return Quaterniond(it) }
        return Quaterniond()
            .rotationTo(Vector3d(0.0, 1.0, 0.0), Vector3d(smoothUp))
            .mul(Quaterniond().rotationY(-Math.toRadians(yBodyRot.toDouble())))
    }

    /** VS2 won't load a mass-0 ship ("waiting for block updates") and only recomputes
     *  mass on a block update — a ship saved before the platform had a vs_mass entry
     *  stays stuck forever. The entity ticking means a player is near, so a long
     *  unloaded streak is that deadlock: re-place the platform to force the recompute. */
    private fun unstickIfNeverLoading(level: ServerLevel, ship: ServerShip) {
        if (level.shipObjectWorld.loadedShips.getById(shipId) != null) {
            shipUnloadedTicks = 0
            return
        }
        shipUnloadedTicks++
        if (shipUnloadedTicks < SHIP_UNSTICK_TICKS) return
        shipUnloadedTicks = 0
        platformPos?.let { pPos ->
            servicingPlatform = true
            try {
                level.setBlock(pPos, Blocks.AIR.defaultBlockState(), 3)
                level.setBlock(pPos, platformBlockState(), 3)
            } finally {
                servicingPlatform = false
            }
            // The air step removes the datapack mass, not the inherited one, and can
            // trip VS2's zero-mass reset; rebuild from the blocks.
            noteMassOp("unstick re-place")
            rebuildInertia(level, ship, pPos)
        }
    }

    private fun noteMassOp(op: String) {
        lastMassOp = op
        lastMassOpTick = tickCount
    }

    /** A non-positive-definite tensor makes the solver emit NaN every step, and one NaN
     *  force from the drive kills the VS2 physics pipeline. */
    private fun checkInertia(level: ServerLevel, ship: ServerShip) {
        if (inertiaRepairGaveUp || ConstriliarInertia.isValid(ship)) return
        val pPos = platformPos ?: return
        // A missing plate is validateCargo's job; it rebuilds after the restore.
        if (!level.getBlockState(pPos).`is`(EKBlocks.CONSTRILIAR_PLATFORM.get())) return
        LOG.warn(
            "[EK constriliar] ship {} inertia invalid (mass={}, tensor={}); last mass op '{}' {} ticks ago; rebuilding",
            ship.id, ship.inertiaData.mass, ship.inertiaData.momentOfInertiaTensor,
            lastMassOp, tickCount - lastMassOpTick,
        )
        rebuildInertia(level, ship, pPos)
    }

    private fun rebuildInertia(level: ServerLevel, ship: ServerShip, pPos: BlockPos) {
        val platformMass = inheritedPlatformMass()
        val rebuilt = platformMass != null && ConstriliarInertia.rebuild(level, ship, pPos, platformMass)
        inertiaRepairGaveUp = !rebuilt || !ConstriliarInertia.isValid(ship)
        if (inertiaRepairGaveUp) {
            LOG.warn("[EK constriliar] ship {} inertia rebuild failed (mass={})", ship.id, ship.inertiaData.mass)
        }
    }

    /** Restore the platform plate if something aired it. No cargo-shape policing — the
     *  leg-strength mass budget is the real limit on what it can carry. */
    private fun validateCargo(level: ServerLevel, ship: ServerShip) {
        val pPos = platformPos ?: return
        if (!level.getBlockState(pPos).`is`(EKBlocks.CONSTRILIAR_PLATFORM.get())) {
            level.setBlock(pPos, platformBlockState(), 3)
            noteMassOp("platform restore")
            rebuildInertia(level, ship, pPos)
        }
        // Entity network ids change every session, so the BER's back-reference is
        // re-stamped here rather than persisted; the source skin rides along.
        (level.getBlockEntity(pPos) as? ConstriliarPlatformBlockEntity)?.also {
            it.setOwner(this)
            it.setSource(bodyBlockState())
        }
    }

    /** Hard snap (creation + spring-lost-the-ship emergencies): platform block centre
     *  lands [BODY_LIFT] up the surface normal from the entity position, rotated by the
     *  body's tilt+yaw. */
    private fun teleportShipToBack(level: ServerLevel, ship: ServerShip) {
        val pPos = platformPos ?: return
        val desiredPos = Vector3d(x, y, z).fma(currentLift(), smoothUp)
        val desiredRot = desiredShipRotation()
        val platformCentre = Vector3d(pPos.x + 0.5, pPos.y + 0.5, pPos.z + 0.5)
        // VS2 teleport semantics (ShipTeleportDataImpl.createNewShipTransform): newPos is
        // where the transform's *current anchor* (COM) lands, NOT where newPosInShip
        // lands. Aim the anchor so the platform centre ends up at desiredPos.
        val anchor = ship.transform.positionInShip
        val anchorOffset = Vector3d(anchor).sub(platformCentre).rotate(desiredRot)
        val data = vsCore.newShipTeleportData(
            newPos = anchorOffset.add(desiredPos),
            newRot = desiredRot,
            newDimension = level.dimensionId,
        )
        vsCore.teleportShip(level.shipObjectWorld as ServerShipWorld, ship, data)
    }

    /** True while releaseShip dismantles the platform, so the block's onRemove
     *  self-restore stands down. */
    var releasingShip: Boolean = false
        private set

    /** True while the entity itself air-swaps its platform (mass refresh, unstick) —
     *  without this the block's onRemove read the swap as "shape destroyed" and the
     *  supposedly-invulnerable construct killed itself mid-intro. */
    var servicingPlatform: Boolean = false
        private set

    /** External destruction of the platform block (explosion, /setblock): the ship must
     *  be deleted SYNCHRONOUSLY — kill() alone defers removal past the death animation,
     *  and even one game tick is ~3 physics frames in which a bare mass-0 dynamic body
     *  crashes the physics thread with "invMass is not finite". */
    fun destroyByShapeLoss() {
        if (releasingShip) return
        shapeDestroyed = true
        releaseShip(dropCargo = true)
        kill()
    }

    /** Pop the cargo off, then delete the ship outright — `deleteShip` removes the body
     *  atomically, so there is never a dynamic mass-0 state for the physics thread's
     *  "invMass is not finite" crash to find. */
    private fun releaseShip(dropCargo: Boolean) {
        val level = level() as? ServerLevel ?: return
        releasingShip = true
        level.shipObjectWorld.loadedShips.getById(shipId)
            ?.getAttachment(ConstriliarShipController::class.java)?.active = false
        val ship = level.shipObjectWorld.allShips.getById(shipId)
        val pPos = platformPos
        if (ship != null && pPos != null) {
            if (dropCargo) ConstriliarCargo.dropShipCargo(level, ship, pPos)
            ShipAssembler.deleteShip(level, ship, false, false)
        }
        shipId = NO_SHIP
        platformPos = null
    }

    override fun remove(reason: RemovalReason) {
        if (!level().isClientSide && reason.shouldDestroy()) releaseShip(dropCargo = true)
        super.remove(reason)
    }

    // ------------------------------------------------------------------ persistence

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        faceTarget?.let {
            tag.putDouble("EkFaceX", it.x); tag.putDouble("EkFaceY", it.y); tag.putDouble("EkFaceZ", it.z)
            tag.putDouble("EkFaceBX", faceBaseUp.x)
            tag.putDouble("EkFaceBY", faceBaseUp.y)
            tag.putDouble("EkFaceBZ", faceBaseUp.z)
            if (faceAnchorShipId != -1L) {
                tag.putLong("EkFaceShip", faceAnchorShipId)
                tag.putDouble("EkFaceSX", faceAnchorLocal.x)
                tag.putDouble("EkFaceSY", faceAnchorLocal.y)
                tag.putDouble("EkFaceSZ", faceAnchorLocal.z)
            }
        }
        ownerUUID?.let { tag.putUUID(NBT_OWNER, it) }
        tag.putLong(NBT_SHIP_ID, shipId)
        tag.putBoolean(NBT_SITTING, isSitting())
        tag.put(NBT_BODY_BLOCK, NbtUtils.writeBlockState(bodyBlockState()))
        platformPos?.let { tag.put(NBT_PLATFORM_POS, NbtUtils.writeBlockPos(it)) }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        if (tag.contains("EkFaceX")) {
            faceTarget = Vec3(tag.getDouble("EkFaceX"), tag.getDouble("EkFaceY"), tag.getDouble("EkFaceZ"))
            if (tag.contains("EkFaceBX")) {
                faceBaseUp.set(tag.getDouble("EkFaceBX"), tag.getDouble("EkFaceBY"), tag.getDouble("EkFaceBZ"))
            }
            if (tag.contains("EkFaceShip")) {
                faceAnchorShipId = tag.getLong("EkFaceShip")
                faceAnchorLocal.set(tag.getDouble("EkFaceSX"), tag.getDouble("EkFaceSY"), tag.getDouble("EkFaceSZ"))
            }
        }
        ownerUUID = if (tag.hasUUID(NBT_OWNER)) tag.getUUID(NBT_OWNER) else null
        shipId = if (tag.contains(NBT_SHIP_ID)) tag.getLong(NBT_SHIP_ID) else NO_SHIP
        entityData.set(DATA_SITTING, tag.getBoolean(NBT_SITTING))
        if (tag.contains(NBT_BODY_BLOCK)) {
            setBodyBlock(
                NbtUtils.readBlockState(
                    level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    tag.getCompound(NBT_BODY_BLOCK),
                ),
            )
        }
        platformPos = if (tag.contains(NBT_PLATFORM_POS)) {
            NbtUtils.readBlockPos(tag.getCompound(NBT_PLATFORM_POS))
        } else {
            null
        }
    }

    // ------------------------------------------------------------------ goals

    /** Staff of Command "go there" order. The order PERSISTS: the walker holds the spot
     *  (re-approaching if displaced) and following stays suppressed until the order is
     *  cleared (sit toggle) or replaced. A sitting constriliar handles orders in
     *  [tickSittingTurn] instead (turn only, no movement). */
    private inner class CommandedMoveGoal : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            val t = commandedTarget ?: return false
            return !isSitting() && !isSpawnAnimating() && commandedTicks < COMMAND_TIMEOUT_TICKS &&
                distanceToSqr(t.x, t.y, t.z) > COMMAND_ARRIVE_SQ
        }

        override fun canContinueToUse(): Boolean = canUse()

        override fun start() {
            commandedTarget?.let { orderMoveTo(it) }
        }

        override fun tick() {
            val t = commandedTarget ?: return
            commandedTicks++
            if (commandedTicks % 20 == 0) orderMoveTo(t)
        }

        override fun stop() {
            stopNavigating()
            val t = commandedTarget
            if (t != null && distanceToSqr(t.x, t.y, t.z) <= COMMAND_ARRIVE_SQ) {
                // Arrived: keep holding the spot with a fresh budget for re-approaches.
                commandedTicks = 0
                if (!arrivalCheered) {
                    arrivalCheered = true
                    voice(EKSounds.CONSTRILIAR_HAPPY, 0.6f)
                }
            } else if (commandedTicks >= COMMAND_TIMEOUT_TICKS) {
                // Unreachable: give the order up rather than pathing forever.
                commandedTarget = null
            }
        }
    }

    private inner class FollowOwnerGoal : Goal() {
        init {
            flags = EnumSet.of(Flag.MOVE)
        }

        override fun canUse(): Boolean {
            // faceTarget: an aimed sentry holds its post and its stare — only a NEW
            // order (move / fresh aim / sit) releases it, never the owner wandering.
            if (isSitting() || isSpawnAnimating() || commandedTarget != null || faceTarget != null) return false
            val o = owner() ?: return false
            return !o.isSpectator && distanceToSqr(o) > FOLLOW_START_SQ
        }

        override fun canContinueToUse(): Boolean {
            if (isSitting() || commandedTarget != null || faceTarget != null) return false
            val o = owner() ?: return false
            return distanceToSqr(o) > FOLLOW_STOP_SQ
        }

        // A goal ticks on alternate game ticks, at a parity that is fixed for the session.
        // A gate on the entity's `tickCount % 10` never passed at the other parity.
        private var reorderIn = 0

        override fun start() {
            reorderIn = 0
        }

        override fun tick() {
            val o = owner() ?: return
            if (--reorderIn > 0) return
            reorderIn = adjustedTickDelay(10)
            orderMoveTo(o.position())
        }

        override fun stop() {
            stopNavigating()
        }
    }

    companion object {
        const val ID_PATH = "constriliar"
        private val LOG = LogUtils.getLogger()

        private const val NO_SHIP = -1L
        private const val NBT_OWNER = "EkOwner"
        private const val NBT_SHIP_ID = "EkShipId"
        private const val NBT_PLATFORM_POS = "EkPlatformPos"

        /** Surface → model ground plane; the belly (plate bottom, 0.25 above the model
         *  ground plane) then clears the tracked surface by ~0.5 blocks. The hover
         *  target adapts to terrain on top of this (see driveShip's look-ahead). */
        const val BODY_LIFT = 0.25

        /** Sitting: plane sunk so the plate bottom rests directly on the ground. */
        const val SIT_LIFT = -0.25
        private val SITTING_DIMENSIONS =
            net.minecraft.world.entity.EntityDimensions.scalable(1.0f, 0.15f)
        private const val SIT_TURN_SPEED = 8f
        /** Covers a 180° turn inside the rise + crouch window (~10 ticks). */
        private const val INTRO_TURN_SPEED = 20f
        private const val COMMAND_TIMEOUT_TICKS = 400
        private const val COMMAND_ARRIVE_SQ = 4.0
        /** Boid separation among commanded walkers: neighbour radius (blocks), per-tick
         *  velocity nudge, and the golden angle for breaking exact-overlap ties. */
        private const val SEPARATION_RADIUS = 1.8
        private const val SEPARATION_STRENGTH = 0.05
        private const val GOLDEN_ANGLE = 2.399963229728653

        private const val NBT_SITTING = "EkSitting"
        private const val NBT_BODY_BLOCK = "EkBodyBlock"

        private const val ATTITUDE_LERP = 0.12
        /** Aim elevation cap off the base (floor/wall) plane. */
        private const val FACE_PITCH_FOLD_MAX_DEG = 22.5f
        /** Body-block centre height along the attitude up (the aim origin). */
        private const val AIM_ORIGIN_UP = 0.9
        /** Extra ride height at a full skyward aim — the rear-up. */
        private const val AIM_REAR_LIFT = 0.8
        /** Aim re-base threshold: a felt contact normal this far off the snapshot
         *  (cos 30°) means the pet stands on a different face — aim from there. */
        private const val FACE_REBASE_DOT = 0.866
        /** Grace (ticks) without support before the aim pose yields to recovery. */
        private const val FACE_GROUNDLESS_GRACE = 10

        /** Aim-trim integrator: per-tick gain on the orientation error (rad),
         *  saturation, and off-aim decay. */
        private const val AIM_TRIM_RATE = 0.02
        private const val AIM_TRIM_MAX = 0.4
        private const val AIM_TRIM_DECAY = 0.9
        /** Max look pitch folded into body tilt — attention, never a face-plant. */
        private const val PITCH_FOLD_MAX_DEG = 25f

        /** Mass-relative strength curve anchors (see [strengthScale]). */
        private const val NETHERITE_CARRY_BLOCKS = 1.85
        private const val ANCHOR_MIN_RATIO = 1.5
        private const val STRENGTH_EXPONENT_FALLBACK = 0.9
        private const val STRENGTH_SCALE_MIN = 0.15
        private const val STRENGTH_SCALE_MAX = 20.0

        /** Hip mounts in shipyard-local blocks from the plate centre (7px roots).
         *  Signs NEGATED relative to the gait's model-frame arrays: shipyard = model ∘
         *  rotY(180°), and strut hip[i] must sit over anchor[i] or torques cross. */
        private val HIP_LOCAL_X = doubleArrayOf(-0.4375, 0.4375, 0.4375, -0.4375)
        private val HIP_LOCAL_Z = doubleArrayOf(0.4375, 0.4375, -0.4375, -0.4375)

        /** SDF field search radius and the smoothed-distance threshold past which the
         *  body counts as detached (airborne relax). */
        private const val SDF_RANGE = 2.0
        private const val SDF_ATTACH_DIST = 0.9
        private const val STICK_ATTACH_GAP = 0.25
        private const val STICK_UPNESS_MAX = 0.75
        private const val STICK_ACCEL = 0.08
        private val WORLD_UP = Vector3d(0.0, 1.0, 0.0)

        private const val CARGO_SCAN_INTERVAL = 10
        private const val CARGO_SCAN_BUDGET = 512
        private const val TICKS_PER_SECOND = 20.0
        private const val DIMENSION_FOLLOW_INTERVAL = 20

        private const val SHIP_RETRY_INTERVAL = 20
        private const val SHIP_UNSTICK_TICKS = 100
        private const val CLIMB_WALL_GAP = 0.15
        private const val OCCUPIED_QUERY_RADIUS = 6.0
        private const val FLUID_SURFACE_SCAN_BUDGET = 24
        private const val STUCK_WAKE_TICKS = 15
        private const val STITCH_INTERVAL = 10
        /** World-load race: ticks a known ship id may be unqueryable before the ship is
         *  declared lost and replaced. */
        private const val SHIP_MISSING_GRACE_TICKS = 200

        /** Orphan-body adoption reach, and how far to look for a rival claimant. */
        private const val ADOPT_RANGE = 4.0
        private const val CLAIM_CHECK_RANGE = 48.0

        // Gap jumping: crouch depth/length (the visible spring compression), ballistic
        // flight-time curve, and flight bounds.
        private const val JUMP_CROUCH_LIFT = -0.05
        private const val JUMP_CROUCH_TICKS = 7
        private const val JUMP_TAKEOFF_ARRIVE_SQ = 0.9
        private const val JUMP_TIME_BASE = 0.35
        private const val JUMP_TIME_PER_BLOCK = 0.07
        private const val JUMP_FLIGHT_MAX_TICKS = 60

        /** Face-transition steering: below this smoothUp·pathUp agreement the walker is
         *  mid-corner (stall detection pauses), and up to TRANSITION_DRIVE_KEEP of the
         *  out-of-plane drive is retained to commit into the turn. */
        private const val TRANSITION_AGREE = 0.9
        private const val TRANSITION_DRIVE_KEEP = 0.6

        /** Hard-stall recovery: displacement below 0.3 blocks for 3 s while wanting
         *  motion → hop toward the waypoint. */
        private const val HARD_STALL_DIST_SQ = 0.09
        private const val HARD_STALL_TICKS = 60
        private const val HOP_SPEED = 3.0
        private const val HOP_UP_SPEED = 4.5
        private const val HOP_SUPPRESS_TICKS = 6

        // Dance: 4 phases of DANCE_BEAT ticks — bob left, bounce, bob right, bounce.
        private const val DANCE_BEAT = 10
        private const val DANCE_ROLL = 0.45
        private const val DANCE_SWAY = 0.35
        private const val DANCE_BOUNCE = 0.75
        private const val DANCE_RANGE = 8.0
        private const val DANCE_RANGE_SQ = DANCE_RANGE * DANCE_RANGE
        /** Walk velocity fed to the ship's locomotion controller (m/s). */
        private const val WALK_SPEED = 3.2
        private const val WALK_TURN_SPEED = 10f
        /** Crawl speed through tight (1-wide) squeezes, giving the yaw time to square. */
        private const val TIGHT_SPEED = 1.2
        /** Approach braking: desired speed ≈ distance × gain near a waypoint, so the
         *  spring settles onto nodes instead of overshooting them. */
        private const val BRAKE_GAIN = 2.5
        /** Surface-sample reach below the plate; nothing in reach = free-fall. */
        private const val HOVER_RAY_REACH = 2.5
        /** Adaptive suspension: rays under the plate plus this many-1 look-ahead samples
         *  along travel; ride height keys off the highest hit. */
        private const val HOVER_SAMPLE_COUNT = 3
        private const val HOVER_SAMPLE_SPACING = 0.9
        /** Rays start this far up the normal so a 1-block step ahead is still caught. */
        private const val HOVER_RAY_START_UP = 1.25
        /** Extra ride height per unit of orientation-vs-surface mismatch, so plate
         *  corners clear the terrain mid-reorientation. */
        private const val TILT_CLEARANCE = 0.6

        private const val FOLLOW_START_SQ = 36.0
        private const val FOLLOW_STOP_SQ = 9.0
        private const val FOLLOW_SPEED = 1.2

        private val DATA_CLIMBING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_SITTING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_BODY_BLOCK: EntityDataAccessor<java.util.Optional<net.minecraft.world.level.block.state.BlockState>> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.OPTIONAL_BLOCK_STATE)
        private val DATA_SPAWN_ANIM: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.INT)
        private val DATA_AIRBORNE: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_STRAINED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.BOOLEAN)

        /** Total ship mass past which the legs visibly struggle (body 400 + an iron
         *  block's ~7000 carried "without issue"). */
        private const val STRAIN_MASS = 7500.0

        private const val AMBIENT_VOLUME = 0.4f
        private const val ACKNOWLEDGE_GAP_TICKS = 10L
        private const val STRAIN_SOUND_TICKS = 30

        // Spawn-animation timeline (ticks), keyed to constriliar_awaken.ogg: crack + groan
        // squash 3.5-13, leg clicks 14-22.5, swell rise 25-33, silent crouch 33-35.5,
        // hop 35.5-45.5 (airborne silence), landing chirp = the spring-settle bob.
        const val SPAWN_ANIM_TOTAL = 52
        const val ANIM_SQUASH_START = 3.5
        const val ANIM_SQUASH_END = 13.0
        const val ANIM_EYES_START = 8.0
        const val ANIM_EYES_END = 18.0
        const val ANIM_RISE_START = 25.0
        const val ANIM_RISE_END = 33.0
        const val ANIM_JUMP_START = 35.5
        const val ANIM_JUMP_END = 45.5
        private const val ANIM_JUMP_HEIGHT = 0.5
        val ANIM_LEG_POP = floatArrayOf(14f, 19.5f, 16.5f, 22.5f)
        const val ANIM_LEG_POP_LENGTH = 8f
        /** The foot reads as landed here: the drop is done, only ~1 px of arc lift remains. */
        const val ANIM_LEG_PLANT = 5f

        /** 0 = the full original block, 1 = the settled plate. */
        fun spawnSquash(t: Float): Float {
            val p = ((t - ANIM_SQUASH_START) / (ANIM_SQUASH_END - ANIM_SQUASH_START)).toFloat().coerceIn(0f, 1f)
            return p * p * (3f - 2f * p)
        }

        private const val BLINK_LENGTH = 3
        private const val BLINK_MIN_GAP = 60
        private const val BLINK_GAP_SPREAD = 140
        private val DATA_HAS_SHIP: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.BOOLEAN)
        private val DATA_PLATFORM_POS: EntityDataAccessor<java.util.Optional<BlockPos>> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.OPTIONAL_BLOCK_POS)
        private val DATA_UP_X: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.FLOAT)
        private val DATA_UP_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.FLOAT)
        private val DATA_UP_Z: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(ConstriliarEntity::class.java, EntityDataSerializers.FLOAT)
        /** Light probe height above the model ground plane: mid-plate. */
        private const val LIGHT_PROBE_UP = 0.5

        /** Liquids read as floor at 0.8 of the block (a source's clip top is ~0.89), so
         *  the walker wades slightly into the surface instead of skating its skin.
         *  Min-clamped so shallow flowing edges aren't raised. */
        fun fluidAdjustedSurface(level: Level, hit: Vec3): Vec3 {
            val pos = BlockPos.containing(hit.x, hit.y - 0.05, hit.z)
            if (level.getFluidState(pos).isEmpty) return hit
            return Vec3(hit.x, Math.min(hit.y, pos.y + FLUID_FLOOR), hit.z)
        }

        private const val FLUID_FLOOR = 0.8

        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 30.0)
            .add(Attributes.MOVEMENT_SPEED, 0.3)
            .add(Attributes.FOLLOW_RANGE, 48.0)
    }
}
