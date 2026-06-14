package org.shipwrights.enderkinesis.blockentity

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.TickTask
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level as MCLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.dimension.AlmanacData
import kotlin.math.sqrt
import org.shipwrights.enderkinesis.dimension.DimensionFilter
import org.shipwrights.enderkinesis.dimension.DimensionNames
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.teleport.SafeTeleporter
import org.shipwrights.enderkinesis.util.Sga
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import org.valkyrienskies.mod.common.vsCore

class EnderAstrolabeBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ENDER_ASTROLABE.get(), pos, state) {

    private var destination: ResourceLocation? = null

    /** Re-entrancy guard: a ship teleport relocates terrain, which fires neighbour updates that
     *  would call [triggerJump] again mid-teleport (→ StackOverflow). */
    private var jumping = false

    // --- Tune-animation state (server-authoritative, synced to client) ---
    // Each successful [cycleDestination] picks a new random yaw (full 360°) for the body group,
    // a random pitch in [-4°, +15°] for the pitch_rot group, and a random roll (full 360°) for
    // the center group. The renderer interpolates from `*AtStart` toward `*Target` over
    // [ANIMATION_DURATION_TICKS] using an ease-out curve. Keeping both endpoints + the start
    // tick is enough for the client to render at any partial-tick; the server captures
    // `*AtStart` from the *previous* animation's current displayed value at the moment a new
    // one begins, so chaining tune events flows smoothly instead of snapping.
    private var yawAtStart: Float = 0f
    private var pitchAtStart: Float = 0f
    private var rollAtStart: Float = 0f
    private var yawTarget: Float = 0f
    private var pitchTarget: Float = 0f
    private var rollTarget: Float = 0f
    private var animationStartTick: Long = Long.MIN_VALUE

    // --- Charge state (server-authoritative, synced to client) -----------------------------
    // A redstone-rising-edge [triggerJump] no longer teleports immediately. Instead it stamps
    // [chargeStartTick] and [chargeDurationTicks] and lets [serverTick] periodically scan for
    // an open landing slot in the destination — distributing the search across the charge
    // window. If any check finds one it's stored in [acceptedLandingPos]; the teleport at the
    // end of charge uses that pre-validated spot. If no check finds one by charge end, we
    // transition into a fail-out phase ([failOutStartTick] / [failOutDurationTicks]) that
    // eases the spin rate linearly back to 0 instead of teleporting.
    private var chargeStartTick: Long = -1L
    private var chargeDurationTicks: Long = 0L
    private var nextLandingCheckTick: Long = 0L
    private var acceptedLandingPos: Vec3? = null
    private var failOutStartTick: Long = -1L
    private var failOutDurationTicks: Long = 0L

    /** Crew/passengers are picked up within this radius of the astrolabe. */
    private val passengerBox: AABB get() = AABB(blockPos).inflate(8.0, 4.0, 8.0)

    /** Yaw to draw at game time [time] (game ticks + partialTick). Ease-out cubic from the
     *  pre-tune snapshot to the random target. Called from the BE renderer every frame. */
    fun displayYaw(time: Double): Float {
        if (animationStartTick == Long.MIN_VALUE) return yawTarget
        val elapsed = time - animationStartTick.toDouble()
        val t = (elapsed / ANIMATION_DURATION_TICKS).coerceIn(0.0, 1.0).toFloat()
        return lerpAngleRad(yawAtStart, yawTarget, easeOutCubic(t))
    }

    /** Pitch to draw at game time [time]. Same easing as [displayYaw]. */
    fun displayPitch(time: Double): Float {
        if (animationStartTick == Long.MIN_VALUE) return pitchTarget
        val elapsed = time - animationStartTick.toDouble()
        val t = (elapsed / ANIMATION_DURATION_TICKS).coerceIn(0.0, 1.0).toFloat()
        return lerpAngleRad(pitchAtStart, pitchTarget, easeOutCubic(t))
    }

    /** Roll for the centre group (spyglasses + globe) at game time [time]. Three overlays
     *  composed onto the underlying tune-animation roll:
     *  - **Charging** ([chargeStartTick] set): integrated spin from [chargeSpinRadians].
     *    Rate ramps `MIN → MAX` over the charge window.
     *  - **Failing out** ([failOutStartTick] set): the *completed* charge angle
     *    ([chargeAngleAtCompletion]) plus an integrated ease-down ([failOutEaseAngle]) that
     *    decays rate from MAX back to 0 across [failOutDurationTicks]. Position keeps
     *    integrating (the centre group keeps turning, just slower and slower) until the
     *    rate reaches 0.
     *  - **Idle**: just the base. */
    fun displayRoll(time: Double): Float {
        val base = if (animationStartTick == Long.MIN_VALUE) {
            rollTarget
        } else {
            val elapsed = time - animationStartTick.toDouble()
            val t = (elapsed / ANIMATION_DURATION_TICKS).coerceIn(0.0, 1.0).toFloat()
            rollAtStart + (rollTarget - rollAtStart) * easeOutCubic(t)
        }
        if (chargeStartTick != -1L) return base + chargeSpinRadians(time)
        if (failOutStartTick != -1L) return base + chargeAngleAtCompletion() + failOutEaseAngle(time)
        return base
    }

    /** Integrated spin angle (radians) accumulated since charge began. Instantaneous
     *  rate ramps **exponentially** from [SPIN_RATE_RAD_PER_TICK_MIN] at `elapsed = 0` to
     *  [SPIN_RATE_RAD_PER_TICK_MAX] at `elapsed = duration`:
     *      `rate(t) = MIN · e^(k·t)`,  with  `k = ln(MAX/MIN) / duration`.
     *  Integrating: `angle(t) = (MIN / k) · (e^(k·t) − 1)`. The slow start + sharp final
     *  acceleration is the "winding up" feel — the centre group barely moves for most of
     *  the charge then whips around right before teleport fires. */
    private fun chargeSpinRadians(time: Double): Float {
        if (chargeDurationTicks <= 0L) return 0f
        val elapsed = (time - chargeStartTick.toDouble()).coerceAtLeast(0.0).toFloat()
        // Clamp to duration so we don't overshoot past charge end (in case the BE ticker
        // takes one extra tick to fire the actual teleport).
        val clamped = elapsed.coerceAtMost(chargeDurationTicks.toFloat())
        val kPerTick = SPIN_LN_RATIO / chargeDurationTicks.toFloat()
        return SPIN_RATE_RAD_PER_TICK_MIN / kPerTick *
            (Math.exp((kPerTick * clamped).toDouble()).toFloat() - 1f)
    }

    /** Total spin angle the charge accumulated by the time it ran out, evaluated
     *  symbolically. Setting `t = duration` in the exponential integral gives
     *  `(MIN / k) · (MAX/MIN − 1) = (MAX − MIN) / k`, which simplifies to
     *  `(MAX − MIN) · duration / ln(MAX/MIN)`. Used during fail-out so the centre group
     *  picks up from where charge left it, not from the un-charged base. */
    private fun chargeAngleAtCompletion(): Float {
        if (chargeDurationTicks <= 0L) return 0f
        val durationF = chargeDurationTicks.toFloat()
        return (SPIN_RATE_RAD_PER_TICK_MAX - SPIN_RATE_RAD_PER_TICK_MIN) * durationF / SPIN_LN_RATIO
    }

    /** Integrated ease-out angle accumulated during the fail-out window. Rate decays
     *  linearly from [SPIN_RATE_RAD_PER_TICK_MAX] (where charge ended) to 0 across
     *  [failOutDurationTicks]: `rate(t) = MAX · (1 − t / fd)`. Integrating:
     *  `angle(t) = MAX · t · (1 − t / (2 · fd))`. At `t = fd` (fail-out complete) the
     *  rate is 0 and the angle has stopped growing. */
    private fun failOutEaseAngle(time: Double): Float {
        if (failOutDurationTicks <= 0L) return 0f
        val elapsed = (time - failOutStartTick.toDouble()).coerceAtLeast(0.0).toFloat()
        val clamped = elapsed.coerceAtMost(failOutDurationTicks.toFloat())
        val fd = failOutDurationTicks.toFloat()
        return SPIN_RATE_RAD_PER_TICK_MAX * clamped * (1f - clamped / (2f * fd))
    }

    fun cycleDestination(player: Player, almanac: ItemStack) {
        val next = AlmanacData.cycle(almanac)
        if (next == null) {
            player.displayClientMessage(
                Component.translatable("message.enderkinesis.astrolabe.no_destinations"), true
            )
            return
        }
        destination = next
        beginTuneAnimation()
        setChanged()
        // Friendly dimension name (fancy translation, else bare path), galactic-alphabet font.
        player.displayClientMessage(
            Component.translatable("message.enderkinesis.astrolabe.destination")
                .append(" ").append(Sga.obfuscate(DimensionNames.display(next))),
            true
        )
    }

    /** Snap the current animation state to where it's displayed right now (the new "atStart"),
     *  pick a fresh random trio of targets, and stamp the animation start tick. Broadcasts the
     *  change to the client via [setChanged] + a block update so the renderer picks it up.
     *
     *  Yaw/pitch use short-arc lerping (the displayed angle is the orientation we want to
     *  end at, regardless of how we got there). Roll is treated differently — it's a *spin
     *  amount*, not an orientation — so the new target is `rollAtStart + delta` where the
     *  delta is uniform on `[0, 2π)`. The renderer sweeps through that full delta linearly,
     *  giving up to 360° of visible spin per tune. We normalise `rollAtStart` modulo 2π
     *  before adding the delta so the absolute stored values stay bounded across many tunes
     *  (otherwise float precision degrades after enough cycles). */
    private fun beginTuneAnimation() {
        val lvl = level ?: return
        val now = lvl.gameTime.toDouble()
        yawAtStart = displayYaw(now)
        pitchAtStart = displayPitch(now)
        rollAtStart = normalizeAngleRad(displayRoll(now))
        val rng: RandomSource = lvl.random
        yawTarget = (rng.nextDouble() * 2.0 * Math.PI).toFloat()
        // [-4°, +15°] uniform — keeps the rings nearly level with a slight upward bias.
        val pitchDeg = -4.0 + rng.nextDouble() * 19.0
        pitchTarget = Math.toRadians(pitchDeg).toFloat()
        // Cumulative 360° spin: target is current + a random delta on [0, 2π). The render
        // lerp is linear (NOT short-arc) so the spyglass / globe physically sweeps the full
        // delta on-screen instead of taking the short way home.
        val rollDelta = (rng.nextDouble() * 2.0 * Math.PI).toFloat()
        rollTarget = rollAtStart + rollDelta
        animationStartTick = lvl.gameTime
        // Force client sync. BE updates are sent on the next chunk tick.
        lvl.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    /**
     * Redstone entry point. Starts a *charge window* whose length scales with the ship's
     * AABB radius (1 s per block of bounding-sphere radius). The real teleport fires
     * later, from [serverTick], once `level.gameTime − chargeStartTick ≥ chargeDurationTicks`.
     *
     *  We can't use `server.tell(TickTask(targetTick, …))` for the delay: vanilla's
     *  `MinecraftServer.shouldRun(TickTask)` returns true whenever `haveTime()` is true
     *  (which is most of every tick), so the task fires on the *same* tick it was queued
     *  on — `TickTask.tick` is only used for priority/throttling, not actual delay. The
     *  geode-assembly code "works" with TickTask because it retries until the chunk is
     *  loaded; we need a deterministic time-based delay, hence the BE ticker.
     *
     *  Re-entrancy: rising edges during charge or during the teleport itself are ignored.
     */
    fun triggerJump(level: MCLevel, pos: BlockPos) {
        if (level !is ServerLevel || jumping ||
            chargeStartTick != -1L || failOutStartTick != -1L
        ) return
        val ship = level.getLoadedShipManagingPos(pos) as? LoadedServerShip
        val radius = if (ship != null) computeShipRadius(ship) else 0.0
        // 1 s per block of radius, with a floor so even tiny ships visibly charge.
        val durationTicks = (radius * 20.0).toLong().coerceAtLeast(MIN_CHARGE_TICKS)

        chargeStartTick = level.gameTime
        chargeDurationTicks = durationTicks
        acceptedLandingPos = null
        nextLandingCheckTick = 0L          // first check fires on the very first serverTick
        setChanged()
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)

        // Amethyst-block place chime — broadcast to anyone in earshot the moment the
        // astrolabe powers up, before the long charge wind-up. Same sound family
        // amethyst clusters play when activated; reads as "the instrument has armed".
        level.playSound(
            null, blockPos,
            SoundEvents.AMETHYST_BLOCK_PLACE, SoundSource.BLOCKS,
            1.0f, 1.0f,
        )

        LOG.info(
            "[EK astrolabe] trigger pos={} dim={} dest={} ship={} radius={} duration={}t " +
                "shipPosInWorld={} shipAABB={}",
            blockPos, level.dimension().location(), destination,
            ship?.id, "%.2f".format(radius), durationTicks,
            ship?.transform?.positionInWorld, ship?.shipAABB,
        )
    }

    /** Per-tick check from the block's `BlockEntityTicker`. Drives the charge → teleport
     *  pipeline and the fail-out ease-down. Branches:
     *   - **Charging**: every `chargeDurationTicks / LANDING_CHECKS_PER_CHARGE` ticks try
     *     a landing attempt; lock in the first success. At charge end, teleport if locked
     *     in, otherwise start fail-out.
     *   - **Failing out**: wait for the ease-down to finish, then bake the final centre-
     *     group angle into the persistent roll state and return to idle. */
    fun serverTick(level: ServerLevel, pos: BlockPos) {
        if (jumping) return
        if (chargeStartTick != -1L) chargingTick(level, pos)
        else if (failOutStartTick != -1L) failOutTick(level)
    }

    private fun chargingTick(level: ServerLevel, pos: BlockPos) {
        val now = level.gameTime
        val elapsed = now - chargeStartTick

        // Power must hold for the entire charge window. Dropping the signal at any point
        // cancels the in-flight jump — same fail-out animation as a no-landing-found
        // outcome, no chat message (silent cancel; the spin-down is the cue).
        if (!level.hasNeighborSignal(pos)) {
            startFailOut(level, null, "lost redstone power during charge")
            return
        }

        // Periodic open-space check, distributed across the charge window. Stops trying
        // once a landing is locked in.
        if (acceptedLandingPos == null && elapsed >= nextLandingCheckTick) {
            attemptLandingCheck(level, pos)
            val interval = (chargeDurationTicks / LANDING_CHECKS_PER_CHARGE).coerceAtLeast(1L)
            nextLandingCheckTick = elapsed + interval
        }

        if (elapsed >= chargeDurationTicks) {
            if (acceptedLandingPos != null || level.getLoadedShipManagingPos(pos) == null) {
                // Either we have a confirmed ship-landing slot, or this is a no-ship
                // (player) teleport whose landing logic is handled inside doJump. Proceed.
                executeJumpAfterCharge(level, pos)
            } else {
                startFailOut(level, "message.enderkinesis.astrolabe.no_safe_landing", "no landing found across all attempts")
            }
        }
    }

    private fun failOutTick(level: ServerLevel) {
        val now = level.gameTime
        if (now - failOutStartTick < failOutDurationTicks) return
        // Ease-out finished. Bake the final displayed roll into the persistent animation
        // state so [displayRoll] returns the same value once we clear the fail-out flag.
        val finalDisplayed = displayRoll(now.toDouble())
        rollAtStart = finalDisplayed
        rollTarget = finalDisplayed
        animationStartTick = Long.MIN_VALUE
        failOutStartTick = -1L
        failOutDurationTicks = 0L
        chargeDurationTicks = 0L
        setChanged()
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    /** Try a single open-space scan against the (ship-mounted) destination landing. On
     *  success, stores the result in [acceptedLandingPos] for the eventual teleport.
     *  No-op for world-frame astrolabes — those resolve their landing inside `doJump`. */
    private fun attemptLandingCheck(level: ServerLevel, pos: BlockPos) {
        val dest = destination ?: return
        if (!DimensionFilter.isAllowed(dest)) return
        val targetKey = ResourceKey.create(Registries.DIMENSION, dest)
        val target = level.server.getLevel(targetKey) ?: return
        if (target.dimension() == level.dimension()) return
        val ship = level.getLoadedShipManagingPos(pos) as? LoadedServerShip ?: return

        val sb = ship.shipAABB ?: return
        val s2w = ship.transform.shipToWorld
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        val corner = Vector3d()
        for (cxI in intArrayOf(sb.minX(), sb.maxX() + 1)) {
            for (cyI in intArrayOf(sb.minY(), sb.maxY() + 1)) {
                for (czI in intArrayOf(sb.minZ(), sb.maxZ() + 1)) {
                    corner.set(cxI.toDouble(), cyI.toDouble(), czI.toDouble())
                    s2w.transformPosition(corner)
                    if (corner.x < minX) minX = corner.x; if (corner.x > maxX) maxX = corner.x
                    if (corner.y < minY) minY = corner.y; if (corner.y > maxY) maxY = corner.y
                    if (corner.z < minZ) minZ = corner.z; if (corner.z > maxZ) maxZ = corner.z
                }
            }
        }
        val scale = level.dimensionType().coordinateScale() / target.dimensionType().coordinateScale()
        val approxX = Math.floor((minX + maxX) * 0.5 * scale).toInt()
        val approxZ = Math.floor((minZ + maxZ) * 0.5 * scale).toInt()
        val sizeX = maxX - minX
        val sizeY = maxY - minY
        val sizeZ = maxZ - minZ
        val landing = SafeTeleporter.findShipLanding(target, approxX, approxZ, sizeX, sizeY, sizeZ)
        LOG.info(
            "[EK astrolabe] check srcDim={} srcAABBcentre=({},{},{}) scale={} " +
                "destDim={} approx=({},{}) shipSize=({}x{}x{}) → landing={}",
            level.dimension().location(),
            "%.2f".format((minX + maxX) * 0.5), "%.2f".format((minY + maxY) * 0.5),
            "%.2f".format((minZ + maxZ) * 0.5),
            "%.4f".format(scale),
            target.dimension().location(), approxX, approxZ,
            "%.2f".format(sizeX), "%.2f".format(sizeY), "%.2f".format(sizeZ),
            landing,
        )
        if (landing != null) acceptedLandingPos = landing
    }

    /** Transition into the ease-out phase. The centre-group spin keeps integrating
     *  downward from the peak rate ([displayRoll]'s fail-out branch) until it reaches 0,
     *  at which point [failOutTick] bakes the position and returns to idle. Pass null for
     *  [messageKey] to fail out silently (no chat announcement). */
    private fun startFailOut(level: ServerLevel, messageKey: String?, logReason: String) {
        failOutStartTick = level.gameTime
        failOutDurationTicks = FAIL_OUT_DURATION_TICKS
        // Important: KEEP chargeDurationTicks so chargeAngleAtCompletion() still has a
        // valid duration to compute the spin angle accumulated by the end of charge. We
        // just stop being "charging" by clearing chargeStartTick.
        chargeStartTick = -1L
        nextLandingCheckTick = 0L
        setChanged()
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)
        if (messageKey != null) announce(level, Component.translatable(messageKey))
        LOG.info("[EK astrolabe] fail-out at pos={} dim={} — {}",
            blockPos, level.dimension().location(), logReason)
    }

    /** Charge window has finished — bake the accumulated spin into the persistent roll
     *  state (so the displayed roll doesn't snap back when we clear charge fields), clear
     *  the charge fields, run the real teleport, and broadcast the new state.
     *
     *  Note: [doJump] picks up [acceptedLandingPos] if set, so a confirmed landing from
     *  one of the distributed checks is what actually gets used for the ship teleport. */
    private fun executeJumpAfterCharge(level: ServerLevel, pos: BlockPos) {
        if (chargeStartTick == -1L) return                  // canceled / never armed
        LOG.info("[EK astrolabe] charge complete at pos={} acceptedLanding={}", pos, acceptedLandingPos)
        // Freeze the displayed roll where it currently is. Setting `animationStartTick =
        // MIN_VALUE` makes [displayRoll]'s base path return `rollTarget`; we set both
        // start and target to the displayed value so there's no further animation step.
        val now = level.gameTime.toDouble()
        val finalDisplayed = displayRoll(now)
        rollAtStart = finalDisplayed
        rollTarget = finalDisplayed
        animationStartTick = Long.MIN_VALUE
        chargeStartTick = -1L
        chargeDurationTicks = 0L
        nextLandingCheckTick = 0L
        setChanged()
        level.sendBlockUpdated(blockPos, blockState, blockState, 3)

        if (jumping) return
        jumping = true
        try {
            doJump(level, pos)
        } finally {
            jumping = false
            acceptedLandingPos = null               // clear regardless of teleport outcome
        }
    }

    /** Bounding-sphere radius of the ship's *local* AABB in blocks — used to size the
     *  charge window. Half the diagonal of `ship.shipAABB`, which lives in the ship's
     *  body frame and is therefore invariant under rotation. (The earlier version used
     *  the world AABB of the rotated OBB, which made the charge time wobble with the
     *  ship's yaw — a 10×3×10 ship rotated 45° around Y grows its world AABB to about
     *  14×3×14 and would have charged ~40 % longer just because of orientation.) */
    private fun computeShipRadius(ship: LoadedServerShip): Double {
        val sb = ship.shipAABB ?: return 0.0
        val dx = (sb.maxX() + 1 - sb.minX()).toDouble()
        val dy = (sb.maxY() + 1 - sb.minY()).toDouble()
        val dz = (sb.maxZ() + 1 - sb.minZ()).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz) / 2.0
    }

    private fun doJump(level: ServerLevel, pos: BlockPos) {
        val dest = destination
        if (dest == null || !DimensionFilter.isAllowed(dest)) {
            announce(level, Component.translatable("message.enderkinesis.astrolabe.no_destinations"))
            return
        }
        val targetKey = ResourceKey.create(Registries.DIMENSION, dest)
        val target = level.server.getLevel(targetKey)
        if (target == null) {
            announce(level, Component.translatable("message.enderkinesis.astrolabe.unknown_dimension"))
            return
        }
        if (target.dimension() == level.dimension()) {
            // No point teleporting into the dimension we're already in.
            announce(level, Component.translatable("message.enderkinesis.astrolabe.same_dimension"))
            return
        }

        // Cross-dimension scale ratio (e.g. Nether is 1/8 of the Overworld in X/Z).
        val scale = level.dimensionType().coordinateScale() / target.dimensionType().coordinateScale()

        // `pos` is used only to identify which ship the astrolabe sits on — never for the
        // destination. The jump is ship world centre → scaled ship world centre.
        val ship = level.getLoadedShipManagingPos(pos) as? LoadedServerShip
        if (ship == null) {
            // Not on a ship — `pos` is a real world position; fall back to player teleport.
            val approxX = Math.floor(pos.x * scale).toInt()
            val approxZ = Math.floor(pos.z * scale).toInt()
            if (Math.abs(approxX) > MAX_WORLD_COORD || Math.abs(approxZ) > MAX_WORLD_COORD) {
                announce(level, Component.translatable("message.enderkinesis.astrolabe.out_of_bounds"))
                return
            }
            val spot = SafeTeleporter.findSafeSpot(target, approxX, approxZ)
            if (spot == null) {
                announce(level, Component.translatable("message.enderkinesis.astrolabe.no_safe_landing"))
                return
            }
            val passengers = level.getEntitiesOfClass(Player::class.java, passengerBox)
            for (p in passengers) SafeTeleporter.teleport(p, target, spot)
            return
        }

        // Ship's current *world* AABB (8 transformed corners of its ship-local AABB). NOTE: a
        // ship-mounted astrolabe's `pos` is a shipyard coordinate, not a world one — scaling that
        // would aim the jump into the shipyard coordinate band (→ VS2 ship-lookup recursion). We
        // scale the ship's real world position instead.
        val sb = ship.shipAABB ?: return
        val s2w = ship.transform.shipToWorld
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        val corner = Vector3d()
        for (cxI in intArrayOf(sb.minX(), sb.maxX() + 1)) {
            for (cyI in intArrayOf(sb.minY(), sb.maxY() + 1)) {
                for (czI in intArrayOf(sb.minZ(), sb.maxZ() + 1)) {
                    corner.set(cxI.toDouble(), cyI.toDouble(), czI.toDouble())
                    s2w.transformPosition(corner)
                    if (corner.x < minX) minX = corner.x; if (corner.x > maxX) maxX = corner.x
                    if (corner.y < minY) minY = corner.y; if (corner.y > maxY) maxY = corner.y
                    if (corner.z < minZ) minZ = corner.z; if (corner.z > maxZ) maxZ = corner.z
                }
            }
        }

        // World-bound safety. Vanilla MC chokes on chunk generation past ±MAX_WORLD_COORD;
        // `WorldGenRegion.getChunk` throws `RuntimeException("We are asking a region for a
        // chunk out of bound")` and the server dies. The cross-dim scale multiplier is the
        // amplifier — Nether→Overworld is ×8, so a ship at Nether x = 4 M lands at OW
        // x = 32 M and from there every subsequent teleport stays past the limit. Refuse
        // the jump if either the source ship's centre or the scaled destination centre is
        // past the safe bound; the player gets an in-bounds message and the ship stays put.
        val srcCenterX = (minX + maxX) * 0.5
        val srcCenterZ = (minZ + maxZ) * 0.5
        val dstCenterX = srcCenterX * scale
        val dstCenterZ = srcCenterZ * scale
        if (Math.abs(srcCenterX) > MAX_WORLD_COORD || Math.abs(srcCenterZ) > MAX_WORLD_COORD ||
            Math.abs(dstCenterX) > MAX_WORLD_COORD || Math.abs(dstCenterZ) > MAX_WORLD_COORD) {
            announce(level, Component.translatable("message.enderkinesis.astrolabe.out_of_bounds"))
            return
        }

        // Snapshot every entity VS2 currently considers dragged by this ship, **before** any
        // teleport-prep that could move them. We combine TWO passes:
        //
        //   1. Dragged-by-ship (VS2's canonical check). [EntityDraggingInformation.
        //      isEntityBeingDraggedByAShip] is `lastShipStoodOn != null AND
        //      ticksSinceStoodOnShip < TICKS_TO_DRAG_ENTITIES AND !mountedToEntity`; we also
        //      require `lastShipStoodOn == shipId` so we only catch entities tied to *this*
        //      ship. Catches free-standing entities on the deck etc.
        //
        //   2. Geometrically inside the ship's OBB. The dragged pass deliberately excludes
        //      `mountedToEntity` entities (VS2 treats them as dragged-through-their-vehicle
        //      instead) — which means players riding Create-style seats, hostile mobs
        //      mounted on horses parked in cabins, etc., all fall through the dragged
        //      filter. This second pass takes any entity whose world position, transformed
        //      back into the ship's local frame, falls inside `shipAABB`.
        //
        //  Search region: the ship's world AABB inflated by [RIDER_QUERY_INFLATE_BLOCKS] —
        //  generous enough to catch the 25-tick dragging tail even for a fast-moving entity
        //  that's drifted away from the hull.
        val shipId = ship.id
        val riderRegion = AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(RIDER_QUERY_INFLATE_BLOCKS)
        val draggedRiders = level.getEntitiesOfClass(Entity::class.java, riderRegion) { e ->
            val info = (e as? IEntityDraggingInformationProvider)?.draggingInformation
            info != null && info.lastShipStoodOn == shipId && info.isEntityBeingDraggedByAShip()
        }
        val worldToShip = ship.transform.worldToShip
        val sbMinX = sb.minX().toDouble(); val sbMaxX = (sb.maxX() + 1).toDouble()
        val sbMinY = sb.minY().toDouble(); val sbMaxY = (sb.maxY() + 1).toDouble()
        val sbMinZ = sb.minZ().toDouble(); val sbMaxZ = (sb.maxZ() + 1).toDouble()
        val draggedSet = draggedRiders.toSet()
        val obbRiders = level.getEntitiesOfClass(Entity::class.java, riderRegion) { e ->
            if (e in draggedSet) return@getEntitiesOfClass false
            val pos = e.position()
            val localPos = Vector3d(pos.x, pos.y, pos.z)
            worldToShip.transformPosition(localPos)
            localPos.x in sbMinX..sbMaxX &&
                localPos.y in sbMinY..sbMaxY &&
                localPos.z in sbMinZ..sbMaxZ
        }
        val riders = (draggedRiders + obbRiders).map { it to it.position() }

        // Riding-tree snapshot — for every captured rider that's currently a passenger of
        // another entity, record the (passenger-UUID, vehicle-UUID) link. UUIDs survive
        // [Entity.changeDimension] (vanilla's [Entity.restoreFrom] saves & re-loads the
        // entity NBT, which includes the UUID), so after all teleports complete we can
        // resolve both endpoints via [ServerLevel.getEntity] in the destination dim and
        // re-establish the ride. Without this, cross-dim teleport drops every riding
        // relationship — vanilla's [Entity.changeDimension] unmounts on the way out.
        val ridingLinks: List<Pair<java.util.UUID, java.util.UUID>> =
            (draggedRiders + obbRiders).mapNotNull { e ->
                e.vehicle?.let { v -> e.uuid to v.uuid }
            }

        // FREEZE — pin every captured rider in place between the pre-teleport snapshot
        // and the post-teleport unfreeze (after the destination ship is fully loaded).
        // We zero their server-side `deltaMovement` and flip on `noGravity`; the listener
        // below re-applies both each tick so any in-flight physics impulse gets clobbered
        // before it can move the entity. Per-entity original `noGravity` state is saved
        // so we can restore exactly what was there (some entities — armor stands, item
        // frames — legitimately spawn with gravity off and shouldn't be re-gravitied on
        // our way out).
        // Original-noGravity capture goes through the shared refcounted map so two
        // jumps that overlap on the same entity don't double-record `original = true`
        // (the second jump's read would see the first jump's already-applied
        // `noGravity = true`). Refcount goes up here; unfreezeAll decrements and only
        // restores when it hits 0.
        for ((entity, capturedPos) in riders) {
            val originalForLog = recordOriginalAndIncrement(entity)
            entity.isNoGravity = true
            entity.deltaMovement = Vec3.ZERO
            LOG.info(
                "[EK astrolabe] freeze rider uuid={} type={} dim={} capturedPos=({},{},{}) " +
                    "onGround={} originalNoGravity={} vehicle={} bbY=[{},{}]",
                entity.uuid, entity.type.toShortString(), entity.level().dimension().location(),
                "%.3f".format(capturedPos.x), "%.3f".format(capturedPos.y), "%.3f".format(capturedPos.z),
                entity.onGround(), originalForLog, entity.vehicle?.uuid,
                "%.3f".format(entity.boundingBox.minY), "%.3f".format(entity.boundingBox.maxY),
            )
        }
        LOG.info("[EK astrolabe] freeze applied to {} entities (dragged={} obb={})",
            riders.size, draggedRiders.size, obbRiders.size)

        // Scale the ship's current world centre into the target dimension.
        val approxX = Math.floor((minX + maxX) * 0.5 * scale).toInt()
        val approxZ = Math.floor((minZ + maxZ) * 0.5 * scale).toInt()

        // Prefer the landing we already validated during the charge phase via
        // [attemptLandingCheck]. Fall back to a fresh OBB scan if for whatever reason
        // executeJumpAfterCharge ran without one (defensive — the chargingTick guard
        // shouldn't let that happen for ship-mounted astrolabes).
        val landing = acceptedLandingPos ?: SafeTeleporter.findShipLanding(
            target, approxX, approxZ, maxX - minX, maxY - minY, maxZ - minZ
        )
        if (landing == null) {
            announce(level, Component.translatable("message.enderkinesis.astrolabe.no_safe_landing"))
            return
        }

        // Move the ship so its world AABB centre lands on the OBB-scanned spot. `newPos` is the
        // ship's new positionInWorld (VS2's createNewShipTransform sets position = newPos, keeping
        // positionInShip); under unchanged rotation that pivot and the AABB centre differ by a
        // constant, so translating positionInWorld by this delta lands the AABB centre on target.
        val curCenter = Vec3((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5)
        val delta = landing.subtract(curCenter)

        LOG.info(
            "[EK astrolabe] pre-teleport ship={} srcDim={} positionInWorld={} curCenter={} " +
                "destDim={} landing={} delta={} computedNewPos={}",
            shipId, level.dimension().location(),
            ship.transform.positionInWorld, curCenter,
            target.dimension().location(), landing, delta,
            Vec3(ship.transform.positionInWorld.x() + delta.x,
                 ship.transform.positionInWorld.y() + delta.y,
                 ship.transform.positionInWorld.z() + delta.z),
        )

        // Per-tick wait-and-unfreeze loop. We can't use `vsCore.afterShipTeleportEvent` +
        // `TickTask(now+1)` like before, because that fires before the ship's chunks have
        // re-loaded in the destination — and we need the destination ship fully loaded
        // before we move entities into it (otherwise they spawn into not-yet-loaded chunks
        // and get into limbo). Instead, register a [TickEvent.SERVER_POST] listener that:
        //
        //   1. Each tick, re-applies the freeze (`deltaMovement = 0`, `noGravity = true`)
        //      to every captured rider — physics impulses that fire between ticks get
        //      clobbered before they can move anything.
        //   2. Each tick, checks `target.shipObjectWorld.loadedShips.getById(shipId)`.
        //      VS2 removes the ship from `loadedShips` on cross-dim teleport and re-adds
        //      it once the destination chunks have loaded; treating non-null as "ready"
        //      gives a deterministic "ship is fully loaded" gate without polling chunk
        //      state directly.
        //   3. Once ready (or after [POST_TELEPORT_TIMEOUT_TICKS] as a safety net), executes
        //      the rider teleport + remount + particle burst, restores each entity's
        //      original `noGravity` state, and unregisters the listener.
        //
        //  The listener captures everything it needs via closure (riders, ridingLinks,
        //  frozenStates, delta, shipId, dimension keys, createdAtTick); no BE-side state
        //  is required. The unregister-self trick uses an [AtomicReference] holder so the
        //  lambda can refer to itself at unregister-time.
        val capturedRiders = riders.map { (e, p) -> e.uuid to p }
        val sourceDimKey = level.dimension()
        val targetDimKey = target.dimension()
        val createdAtTick = level.gameTime
        val listenerRef = java.util.concurrent.atomic.AtomicReference<
            dev.architectury.event.events.common.TickEvent.Server?>(null)
        // Idempotent unfreeze. Decrements the shared refcount for every captured rider
        // exactly once across this jump's lifetime; the [unfrozenOnce] flag guards against
        // the catch block firing after the normal flow already unfroze (which would
        // otherwise double-decrement and prematurely restore an entity that another jump
        // still has frozen). Looks up entities in both dims because a rider can be in
        // either (mid-teleport, partially-completed teleport, etc.).
        val unfrozenOnce = java.util.concurrent.atomic.AtomicBoolean(false)
        val unfreezeAll: (ServerLevel?, ServerLevel?) -> Unit = { src, dst ->
            if (unfrozenOnce.compareAndSet(false, true)) {
                for ((uuid, _) in capturedRiders) {
                    val entity = dst?.getEntity(uuid) ?: src?.getEntity(uuid)
                    val restored = decrementAndMaybeRestore(uuid)
                    if (entity != null) {
                        entity.deltaMovement = Vec3.ZERO
                        if (restored != null) entity.isNoGravity = restored
                    }
                }
            }
        }
        val unregisterSelf: () -> Unit = {
            listenerRef.get()?.let {
                dev.architectury.event.events.common.TickEvent.SERVER_POST.unregister(it)
            }
        }

        val listener = dev.architectury.event.events.common.TickEvent.Server { srv ->
            val sourceLvl = srv.getLevel(sourceDimKey)
            val targetLvl = srv.getLevel(targetDimKey)
            if (sourceLvl == null || targetLvl == null) {
                // Either dim went away while we were waiting (shouldn't normally happen, but
                // a defensive listener that just unregisters here would *leak* every frozen
                // rider's noGravity = true). Unfreeze on whichever level is still live before
                // dropping out — better to over-restore than to leave the player floating.
                LOG.warn("[EK astrolabe] post-teleport listener saw a null level (src={}, dst={}); " +
                    "force-unfreezing {} riders before unregistering",
                    sourceLvl?.dimension()?.location(), targetLvl?.dimension()?.location(),
                    riders.size)
                unfreezeAll(sourceLvl, targetLvl)
                unregisterSelf()
                return@Server
            }
            try {

            // Re-apply the freeze each tick. The captured rider might be in either dim
            // depending on whether the ship has finished teleporting yet — look in both.
            for ((uuid, _) in capturedRiders) {
                val entity = sourceLvl.getEntity(uuid) ?: targetLvl.getEntity(uuid) ?: continue
                entity.deltaMovement = Vec3.ZERO
                if (!entity.isNoGravity) entity.isNoGravity = true
            }

            // [createdAtTick] is [Level.gameTime] (the world's persisted clock, a Long that
            // can run into the millions on long-played worlds); [srv.tickCount] is the
            // server's *uptime* counter, reset to 0 each launch. Subtracting one from the
            // other gives a wildly-negative bogus elapsed (~-561 717t on the observed
            // teleport) that breaks both the diagnostic log *and* the timeout safety net —
            // `negative < POST_TELEPORT_TIMEOUT_TICKS` is always true, so a wait that
            // genuinely needed to time out would have spun forever. Read both endpoints
            // from `gameTime` so they're on the same clock.
            val elapsed = (sourceLvl.gameTime - createdAtTick).toInt()
            val targetShip = targetLvl.shipObjectWorld.loadedShips.getById(shipId)
            if (targetShip == null && elapsed < POST_TELEPORT_TIMEOUT_TICKS) {
                // Ship hasn't finished loading in destination yet — keep waiting.
                return@Server
            }
            if (targetShip == null) {
                LOG.warn("[EK astrolabe] post-teleport timed out after {} ticks waiting for ship {} " +
                    "to load in {}, executing rider teleport anyway", elapsed, shipId, targetDimKey.location())
            }

            // Locate the (now-loaded) ship for logging + the particle burst.
            val landedShipForFx = targetShip as? LoadedServerShip
            landedShipForFx?.let { l ->
                LOG.info(
                    "[EK astrolabe] post-teleport ship={} dim={} positionInWorld={} " +
                        "shipAABB={} riders={} (dragged={} obb={}) elapsed={}t",
                    shipId, targetDimKey.location(),
                    l.transform.positionInWorld, l.shipAABB,
                    riders.size, draggedRiders.size, obbRiders.size, elapsed,
                )
            }

            // Step 1: teleport each rider to (oldPos + delta) in the target dim.
            for ((uuid, oldPos) in capturedRiders) {
                val entity = sourceLvl.getEntity(uuid) ?: targetLvl.getEntity(uuid) ?: continue
                val newX = oldPos.x + delta.x
                val newY = oldPos.y + delta.y
                val newZ = oldPos.z + delta.z
                val preDim = entity.level().dimension().location()
                val prePos = entity.position()
                LOG.info(
                    "[EK astrolabe] teleport rider uuid={} preTpDim={} preTpPos=({},{},{}) " +
                        "oldPos=({},{},{}) delta=({},{},{}) → newPos=({},{},{})",
                    uuid, preDim,
                    "%.3f".format(prePos.x), "%.3f".format(prePos.y), "%.3f".format(prePos.z),
                    "%.3f".format(oldPos.x), "%.3f".format(oldPos.y), "%.3f".format(oldPos.z),
                    "%.3f".format(delta.x), "%.3f".format(delta.y), "%.3f".format(delta.z),
                    "%.3f".format(newX), "%.3f".format(newY), "%.3f".format(newZ),
                )
                SafeTeleporter.teleportTo(entity, targetLvl, newX, newY, newZ)
                val landed = targetLvl.getEntity(uuid)
                LOG.info(
                    "[EK astrolabe] teleport rider uuid={} postTpDim={} actualPos={} bbY=[{},{}]",
                    uuid,
                    landed?.level()?.dimension()?.location(),
                    landed?.position(),
                    landed?.let { "%.3f".format(it.boundingBox.minY) } ?: "?",
                    landed?.let { "%.3f".format(it.boundingBox.maxY) } ?: "?",
                )
            }

            // Step 2: re-establish riding via UUID lookup. UUIDs are preserved across
            // [Entity.changeDimension], so this works for both same-dim and cross-dim.
            var remountedCount = 0
            for ((passengerUUID, vehicleUUID) in ridingLinks) {
                val passenger = targetLvl.getEntity(passengerUUID) ?: continue
                val vehicle = targetLvl.getEntity(vehicleUUID) ?: continue
                if (passenger.startRiding(vehicle, true)) remountedCount++
            }

            // Step 3: unfreeze — restore each entity's original `noGravity` state. Look in
            // BOTH dims (not just target), because Step 1's teleportTo can silently fail or
            // partially complete and leave the entity in source — if we only checked target
            // those entities would stay floating forever, which is exactly the
            // "I can't go down" bug.
            unfreezeAll(sourceLvl, targetLvl)
            for ((uuid, _) in capturedRiders) {
                val entity = targetLvl.getEntity(uuid) ?: sourceLvl.getEntity(uuid) ?: continue
                LOG.info(
                    "[EK astrolabe] unfreeze rider uuid={} finalPos={} restoredNoGravity={} onGround={}",
                    uuid, entity.position(), entity.isNoGravity, entity.onGround(),
                )
            }

            // Step 4: destination-side particle burst (now that the ship is in the dest
            // dim's loadedShips and we have a current OBB to sample from).
            if (landedShipForFx != null) emitOBBTeleportParticles(targetLvl, landedShipForFx)

            LOG.info("[EK astrolabe] post-teleport unfreeze: remount={}/{} pairs, elapsed={}t",
                remountedCount, ridingLinks.size, elapsed)

            unregisterSelf()
            } catch (t: Throwable) {
                // Anything between the ship-loaded gate and unfreeze (logging, particles,
                // VS2 lookups) that throws would otherwise leave riders stuck at
                // noGravity = true. Catch, force-unfreeze, log, and unregister.
                LOG.error("[EK astrolabe] post-teleport listener threw — force-unfreezing " +
                    "{} riders to avoid stranding them", riders.size, t)
                unfreezeAll(sourceLvl, targetLvl)
                unregisterSelf()
            }
        }
        listenerRef.set(listener)
        dev.architectury.event.events.common.TickEvent.SERVER_POST.register(listener)

        // Source-side puff before the ship actually leaves: scatter portal particles across
        // the ship's OBB and play the enderman-teleport sound at its centre, so observers
        // on the departing side see *where* it left from. The destination half fires from
        // the afterShipTeleport listener above.
        emitOBBTeleportParticles(level, ship)

        // Teleport the ship itself (keeping its orientation).
        val newPos = Vector3d(ship.transform.positionInWorld)
            .add(delta.x, delta.y, delta.z)
        val data = vsCore.newShipTeleportData(
            newPos = newPos,
            newRot = ship.transform.shipToWorldRotation,
            newDimension = target.dimensionId,
        )
        vsCore.teleportShip(level.shipObjectWorld as ServerShipWorld, ship, data)
    }

    /** Sprinkle portal particles across the ship's oriented bounding box and play the
     *  enderman-teleport sound at its centre. Particle count scales with OBB volume so big
     *  ships get a proportionally meatier puff, clamped to a sane range. Particles are
     *  sampled uniformly in ship-local AABB coords then transformed through `shipToWorld`
     *  so they actually land on the *oriented* box rather than its world-axis-aligned
     *  bounding rectangle — gets you the diagonal/tilted spread for rotated ships. */
    private fun emitOBBTeleportParticles(level: ServerLevel, ship: LoadedServerShip) {
        val sb = ship.shipAABB ?: return
        val s2w = ship.transform.shipToWorld
        val rng = level.random
        val sizeX = (sb.maxX() + 1 - sb.minX()).coerceAtLeast(1)
        val sizeY = (sb.maxY() + 1 - sb.minY()).coerceAtLeast(1)
        val sizeZ = (sb.maxZ() + 1 - sb.minZ()).coerceAtLeast(1)
        val volume = sizeX.toLong() * sizeY * sizeZ
        val particleCount = (volume / OBB_VOLUME_PER_PARTICLE).toInt()
            .coerceIn(OBB_PARTICLES_MIN, OBB_PARTICLES_MAX)
        val p = Vector3d()
        for (i in 0 until particleCount) {
            val lx = sb.minX() + rng.nextDouble() * sizeX
            val ly = sb.minY() + rng.nextDouble() * sizeY
            val lz = sb.minZ() + rng.nextDouble() * sizeZ
            p.set(lx, ly, lz)
            s2w.transformPosition(p)
            // Single-particle emit with a small velocity spread so they drift a bit like
            // vanilla enderman teleport particles. Uses the mod's ender-green portal
            // recolour ([EKParticles.planarSpiral]) instead of vanilla
            // `ParticleTypes.PORTAL` for a recognisable end-themed effect.
            level.sendParticles(
                EKParticles.planarSpiral(),
                p.x, p.y, p.z,
                1, 0.3, 0.3, 0.3, 0.5,
            )
        }
        val centre = ship.transform.positionInWorld
        level.playSound(
            null,
            centre.x(), centre.y(), centre.z(),
            SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS,
            1.0f, 1.0f,
        )
    }

    private companion object {
        private val LOG = LogUtils.getLogger()

        /** Ticks to keep the post-teleport listener before giving up on it. */
        const val LISTENER_TIMEOUT = 100

        /** Buffer (blocks) added to the ship's world AABB when scanning for entities that
         *  VS2 currently considers dragged. VS2's dragging window is
         *  [EntityDraggingInformation.TICKS_TO_DRAG_ENTITIES] = 25 ticks after the entity last
         *  stood on the ship; a fast entity (creative-flight player, etc.) can be roughly
         *  this many blocks past the hull before it loses dragged status, so 32 covers any
         *  realistic case without iterating the entire dimension. */
        const val RIDER_QUERY_INFLATE_BLOCKS = 32.0

        /** Charge: minimum window even for a degenerate / tiny ship, so the spin-up always
         *  reads as deliberate rather than instant. Bumped to 60 (3 s) so a no-ship /
         *  ground-mounted astrolabe still gets a visibly accelerating spin-up — at 20
         *  ticks the integrated spin tops out around 4.5° total and is essentially
         *  imperceptible against the centre group's resting position. */
        const val MIN_CHARGE_TICKS = 60L

        /** Charge: peak spin rate at the end of charge — 480°/s, ship-size-invariant. The
         *  rate ramps **exponentially** from [SPIN_RATE_RAD_PER_TICK_MIN] at charge start
         *  to this value at charge end (see [chargeSpinRadians]); the wind-up feels almost
         *  flat for most of the charge then accelerates sharply into the teleport, which
         *  reads as "the instrument is straining". Longer charges (bigger ships) just take
         *  longer to get there — the peak rate stays the same. */
        val SPIN_RATE_RAD_PER_TICK_MAX: Float =
            (Math.toRadians(480.0) / 20.0).toFloat()

        /** Charge: initial spin rate at the start of charge — 20°/s. Non-zero so the
         *  centre group is *visibly* moving from frame one of the charge window. With the
         *  exponential ramp the rate stays near MIN for most of the wind-up, so this
         *  baseline needs to be brisk enough that the early phase isn't visually still. */
        val SPIN_RATE_RAD_PER_TICK_MIN: Float =
            (Math.toRadians(20.0) / 20.0).toFloat()

        /** Exponent for the charge spin: `k = ln(MAX/MIN) / duration` makes
         *  `rate(t) = MIN · e^(k·t)` hit exactly MIN at t=0 and MAX at t=duration. Cached
         *  in scope of each [chargeSpinRadians] / [chargeAngleAtCompletion] call as
         *  `kPerTick = ln(ratio) / duration` so we don't recompute the ratio every frame. */
        val SPIN_RATIO: Float =
            SPIN_RATE_RAD_PER_TICK_MAX / SPIN_RATE_RAD_PER_TICK_MIN
        val SPIN_LN_RATIO: Float = Math.log(SPIN_RATIO.toDouble()).toFloat()

        /** OBB teleport-particle tuning. One particle per [OBB_VOLUME_PER_PARTICLE] block³
         *  of the ship's OBB, clamped to `[MIN, MAX]`. A 10×10×10 ship gets ~250 particles,
         *  a tiny 3×3×3 ship hits the 50 floor, a huge 30³ ship caps at 400. */
        const val OBB_VOLUME_PER_PARTICLE: Long = 4L
        const val OBB_PARTICLES_MIN: Int = 50
        const val OBB_PARTICLES_MAX: Int = 400

        /** How many open-space check attempts to spread across the charge window. The
         *  charge duration is divided into this many equal intervals; one
         *  [SafeTeleporter.findShipLanding] is fired at the start of each interval until a
         *  landing locks in. */
        const val LANDING_CHECKS_PER_CHARGE: Long = 4L

        /** Length of the fail-out ease-down phase in ticks. 30 ticks (1.5 s) is fast
         *  enough to read as a deliberate stop rather than the astrolabe coasting forever,
         *  slow enough that the spyglass + dome visibly decelerate rather than snap. */
        const val FAIL_OUT_DURATION_TICKS: Long = 30L

        /** Safety bound on how long the post-teleport freeze loop will wait for VS2 to
         *  re-load the ship in the destination dimension. If the listener hits this and
         *  the ship still isn't `loadedShips`-resident — e.g. the destination has no
         *  chunk tickets nearby — we run the rider teleport anyway and unfreeze, so
         *  entities don't get stranded in the source dim indefinitely. */
        const val POST_TELEPORT_TIMEOUT_TICKS: Int = 200      // 10 s at 20 TPS

        /** Vanilla MC's safe coordinate ceiling. Past `±30 000 000` the chunk-generation
         *  pipeline starts throwing `RuntimeException("We are asking a region for a chunk
         *  out of bound")` from `WorldGenRegion.getChunk`, which crashes the server.
         *  [doJump] refuses any teleport whose source or destination centre falls past
         *  this bound — without the check, a single cross-dim jump with the ×8 Nether/OW
         *  scale can knock a ship past the limit, and every subsequent jump amplifies the
         *  error until the server dies trying to tick a BE at billions-coords. */
        const val MAX_WORLD_COORD: Double = 29_999_984.0

        /** Shared across every in-flight astrolabe jump. Tracks the *original*
         *  `noGravity` state for each entity currently frozen by ANY jump, plus a refcount
         *  so overlapping jumps don't stomp on each other's saved state. Without this,
         *  jump A captures `original = false`, sets noGravity = true; jump B (started
         *  before A's listener completes) reads the now-true field and captures
         *  `original = true`; A correctly restores to false, then B incorrectly restores
         *  to true — leaving the entity stuck noGravity = true. The refcount makes the
         *  restore wait until the *last* in-flight jump on that entity finishes. */
        private val pendingFreezeOriginals: java.util.concurrent.ConcurrentHashMap<java.util.UUID, FreezeRecord> =
            java.util.concurrent.ConcurrentHashMap()

        /** [pendingFreezeOriginals] entry: the value of `entity.isNoGravity` at the
         *  moment the first overlapping jump froze this entity, and how many jumps
         *  currently have it frozen. The restore happens when [refcount] hits 0. */
        private data class FreezeRecord(val original: Boolean, val refcount: Int)

        /** Record an entity as freshly frozen by some jump: if it's the first
         *  freeze in flight, capture its current `isNoGravity`; otherwise just bump the
         *  refcount and keep the previously-captured original. Returns the value that
         *  *will* be used to restore the entity (i.e. the genuine original, regardless of
         *  which jump runs first / last). */
        private fun recordOriginalAndIncrement(entity: net.minecraft.world.entity.Entity): Boolean {
            var original = false
            pendingFreezeOriginals.compute(entity.uuid) { _, prev ->
                if (prev != null) {
                    original = prev.original
                    FreezeRecord(prev.original, prev.refcount + 1)
                } else {
                    original = entity.isNoGravity
                    FreezeRecord(original, 1)
                }
            }
            return original
        }

        /** Decrement the refcount for an entity; if it hits 0, drop the entry and return
         *  the original `isNoGravity` value to apply. Returns null when there's still at
         *  least one other in-flight jump freezing this entity — the caller should leave
         *  `isNoGravity` alone in that case (the other jump's `unfreezeAll` will restore
         *  when it completes). */
        private fun decrementAndMaybeRestore(uuid: java.util.UUID): Boolean? {
            var restored: Boolean? = null
            pendingFreezeOriginals.compute(uuid) { _, prev ->
                when {
                    prev == null -> null
                    prev.refcount <= 1 -> {
                        restored = prev.original
                        null
                    }
                    else -> FreezeRecord(prev.original, prev.refcount - 1)
                }
            }
            return restored
        }

        /** Length of the tune-spin animation in game ticks. ~1.5 s at 20 TPS — quick enough to
         *  feel responsive, slow enough to read as a deliberate spin rather than a snap. */
        const val ANIMATION_DURATION_TICKS = 30L

        /** Ease-out cubic: fast start, slow settle. Matches the "instrument tuning itself"
         *  feel — the rings whip into place, then ease into rest. */
        fun easeOutCubic(t: Float): Float {
            val inv = 1f - t
            return 1f - inv * inv * inv
        }

        /** Lerp between two radian angles along the shortest arc — handles ±π wraparound so a
         *  yaw spin from 350° → 10° crosses 20°, not 340°. */
        fun lerpAngleRad(a: Float, b: Float, t: Float): Float {
            val twoPi = (2.0 * Math.PI).toFloat()
            var d = b - a
            while (d > Math.PI.toFloat()) d -= twoPi
            while (d < -Math.PI.toFloat()) d += twoPi
            return a + d * t
        }

        /** Constrain an angle (radians) to `[0, 2π)` via a positive modulo. Used to keep
         *  the cumulative roll angle bounded across repeated tunes so float precision
         *  doesn't degrade after a few minutes of cycling. */
        fun normalizeAngleRad(a: Float): Float {
            val twoPi = (2.0 * Math.PI).toFloat()
            var r = a % twoPi
            if (r < 0f) r += twoPi
            return r
        }
    }

    private fun announce(level: ServerLevel, msg: Component) {
        level.getEntitiesOfClass(Player::class.java, passengerBox)
            .forEach { it.displayClientMessage(msg, true) }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        destination?.let { tag.putString("Destination", it.toString()) }
        tag.putFloat("YawAtStart", yawAtStart)
        tag.putFloat("PitchAtStart", pitchAtStart)
        tag.putFloat("RollAtStart", rollAtStart)
        tag.putFloat("YawTarget", yawTarget)
        tag.putFloat("PitchTarget", pitchTarget)
        tag.putFloat("RollTarget", rollTarget)
        tag.putLong("AnimStartTick", animationStartTick)
        tag.putLong("ChargeStartTick", chargeStartTick)
        tag.putLong("ChargeDurationTicks", chargeDurationTicks)
        tag.putLong("NextLandingCheckTick", nextLandingCheckTick)
        acceptedLandingPos?.let {
            tag.putDouble("AcceptedLandingX", it.x)
            tag.putDouble("AcceptedLandingY", it.y)
            tag.putDouble("AcceptedLandingZ", it.z)
        }
        tag.putLong("FailOutStartTick", failOutStartTick)
        tag.putLong("FailOutDurationTicks", failOutDurationTicks)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        destination = if (tag.contains("Destination"))
            ResourceLocation.tryParse(tag.getString("Destination")) else null
        yawAtStart = tag.getFloat("YawAtStart")
        pitchAtStart = tag.getFloat("PitchAtStart")
        rollAtStart = tag.getFloat("RollAtStart")
        yawTarget = tag.getFloat("YawTarget")
        pitchTarget = tag.getFloat("PitchTarget")
        rollTarget = tag.getFloat("RollTarget")
        animationStartTick = if (tag.contains("AnimStartTick"))
            tag.getLong("AnimStartTick") else Long.MIN_VALUE
        chargeStartTick = if (tag.contains("ChargeStartTick"))
            tag.getLong("ChargeStartTick") else -1L
        chargeDurationTicks = if (tag.contains("ChargeDurationTicks"))
            tag.getLong("ChargeDurationTicks") else 0L
        nextLandingCheckTick = if (tag.contains("NextLandingCheckTick"))
            tag.getLong("NextLandingCheckTick") else 0L
        acceptedLandingPos = if (tag.contains("AcceptedLandingX")) Vec3(
            tag.getDouble("AcceptedLandingX"),
            tag.getDouble("AcceptedLandingY"),
            tag.getDouble("AcceptedLandingZ"),
        ) else null
        failOutStartTick = if (tag.contains("FailOutStartTick"))
            tag.getLong("FailOutStartTick") else -1L
        failOutDurationTicks = if (tag.contains("FailOutDurationTicks"))
            tag.getLong("FailOutDurationTicks") else 0L
    }

    /** Sync packet — sends our full NBT (including the tune-animation fields) so the client
     *  BE renderer sees fresh targets/start-tick the moment a `cycleDestination` triggers
     *  `sendBlockUpdated`. Without this override the BE has no client sync and the renderer
     *  would only ever see the initial-load state. */
    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(): CompoundTag {
        val tag = CompoundTag()
        saveAdditional(tag)
        return tag
    }
}
