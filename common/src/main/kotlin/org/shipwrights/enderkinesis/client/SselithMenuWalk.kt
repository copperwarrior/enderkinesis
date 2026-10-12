package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.util.LandRandomPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.SselithMadness
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.entity.WanderToTaggedBlockGoal
import org.shipwrights.enderkinesis.registry.EKEntities
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.toWorldCoordinates
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * Phase 2 of Sselith Madness — the menu-walk.
 *
 * While [SselithControlLoss] reports that the madness holds the local player, the
 * player's body walks like a Cataloger — pathing to `#enderkinesis:cataloger_targets` blocks and
 * staring at them. Movement is synthesised client-side (the client owns player
 * movement, so the server accepts it with no rubber-banding); with a screen open
 * the world still ticks in multiplayer, the only place the pause-menu case shows.
 *
 * ## Parity with the real Cataloger
 *
 * Movement is the Cataloger's own code: a **ghost Cataloger** ([ghostFor]) — a real
 * Cataloger created client-side, never added to the world or rendered — is snapped
 * onto the player each tick, and its [net.minecraft.world.entity.ai.navigation.PathNavigation.tick]
 * + `MoveControl.tick` produce the heading we copy onto the player. So path-following
 * (and the wall-avoiding [org.shipwrights.enderkinesis.entity.CatalogerNodeEvaluator])
 * is identical, not approximated.
 *
 * Target *selection* matches [org.shipwrights.enderkinesis.entity.WanderToTaggedBlockGoal]
 * one-for-one:
 *  - **48-block search** ([SEARCH_RADIUS]/[SAME_FLOOR_Y]) — the same extent the goal
 *    pulls from the server `PoiManager`. The client has no `PoiManager`, so we keep a
 *    cache filled by a rolling block scan ([tickScan]) that sweeps the 48-radius box a
 *    slice ([SCAN_BUDGET_PER_TICK]) at a time and republishes every ~2 s — same data,
 *    amortised instead of a per-pick sweep.
 *  - **exclude-last-target** (with the empty-pool fallback),
 *  - **random batch of [BATCH_SIZE]** → one expansion picks the cheapest reachable,
 *  - **[retryTimes] cooldown** marking a failed batch's members unreachable for
 *    [RETRY_COOLDOWN_TICKS], so we don't re-probe them — the goal's `JitteredLinearRetry`
 *    stand-in.
 *
 * With nothing reachable the body stands still — Catalogers path to targets, they
 * never wander. Driven from `LocalPlayerCatalogerWalkMixin`.
 */
object SselithMenuWalk {

    private const val WALK_IMPULSE = 0.5f
    /** Player-only aim/turn easing — fraction of the remaining angle closed each
     *  tick (ease-out). The ghost Cataloger keeps its own snappy `MoveControl`
     *  turning; we just smooth the camera/body the heading is copied onto, so the
     *  possessed view glides instead of snapping. */
    private const val EASE_FACTOR = 0.2f
    private const val MOVE_SPEED = 1.0

    /** How often (ticks) the client pings the server to emit a dust particle while
     *  walking — mirrors the Cataloger's `spawnDustTrail` cadence. The server does
     *  the actual spawn via `sendParticles`, so every nearby player sees the trail,
     *  not just the possessed client. */
    private const val DUST_INTERVAL_TICKS = 4

    /** Matches WanderToTaggedBlockGoal: 48-block XZ search, ±48 vertical. */
    private const val SEARCH_RADIUS = 48
    private const val SAME_FLOOR_Y = 48
    private const val MAX_CANDIDATES = 256
    private const val BATCH_SIZE = 5

    /** Blocks scanned per tick by the rolling POI cache. ~20k ≈ <1 ms; the full
     *  97³ box republishes about every 2 s. */
    private const val SCAN_BUDGET_PER_TICK = 20000

    private const val ARRIVE_DIST_SQ = 2.25
    private const val NEAR_POI_RADIUS = 5
    private const val STARE_TICKS = 20 * 12
    private const val SCAN_COOLDOWN = 20 * 2
    private const val RECOMPUTE_INTERVAL = 10
    private const val MAX_RECOMPUTE_ATTEMPTS = 4
    private const val NAVIGATION_REGION_OFFSET = 1
    private const val RETRY_COOLDOWN_TICKS = 60L * 20L
    private const val RETRY_CLEANUP_TICKS = 30L * 20L

    private enum class Phase { SEEKING, MOVING, STARING, WANDERING }

    private var phase = Phase.SEEKING
    private var target: BlockPos? = null
    private var lastTarget: BlockPos? = null
    private var lookAt: Vec3 = Vec3.ZERO
    private var staringTicks = 0
    private var scanCooldown = 0
    private var ticksUntilRecompute = 0
    private var recomputeAttempts = 0
    private var ghost: Cataloger? = null
    private var active = false

    // Rolling POI cache (client-side stand-in for the server PoiManager query).
    private var poiCache: List<BlockPos> = emptyList()
    private var scanBuffer = ArrayList<BlockPos>()
    private var scanIndex = 0
    private var scanOrigin: BlockPos = BlockPos.ZERO

    // Per-POI cooldown after a failed (unreachable) batch pathfind.
    private val retryTimes = Long2LongOpenHashMap().apply { defaultReturnValue(0L) }
    private var nextRetryCleanup = 0L

    fun onAiStep(player: LocalPlayer) {
        // Yield to an active Sselith Madness tome summon — both
        // systems try to drive the camera each tick and the
        // simultaneous look-at packets + rotation writes cause a
        // visible shake. The summon takes priority for its
        // ~26 s window; the menu walk resumes immediately after.
        if (PlayerTomeSummonClient.get(player) != null) {
            reset()
            return
        }
        if (!isActive(player)) {
            reset()
            return
        }
        if (!active) {
            active = true
            startAtNearPoi(player)
        }
        tickScan(player)
        when (phase) {
            Phase.SEEKING -> seek(player)
            Phase.MOVING -> move(player)
            Phase.STARING -> stare(player)
            Phase.WANDERING -> wander(player)
        }
    }

    // ---- phases -------------------------------------------------------------

    /** [poiCache] is empty on the first ticks, so [seek] would walk the player away
     *  from the POI block they opened. [move] goes to the stare when the player is at the block. */
    private fun startAtNearPoi(player: LocalPlayer) {
        val t = interactedPoi(player) ?: closestPoi(player) ?: return
        target = t
        lastTarget = t
        phase = Phase.MOVING
        ticksUntilRecompute = 0
        recomputeAttempts = 0
        val g = ghostFor(player) ?: return
        syncGhost(g, player)
        frontPath(player, g, t)?.let { g.navigation.moveTo(it, MOVE_SPEED) }
    }

    private fun interactedPoi(player: LocalPlayer): BlockPos? {
        val mc = Minecraft.getInstance()
        if (mc.screen == null) return null
        val pos = (mc.hitResult as? BlockHitResult)?.blockPos ?: return null
        if (poiDistSq(player, pos) > NEAR_POI_RADIUS * NEAR_POI_RADIUS) return null
        return pos.takeIf { player.level().getBlockState(it).`is`(CATALOGER_TARGETS) }
    }

    private fun closestPoi(player: LocalPlayer): BlockPos? {
        val level = player.level()
        var closest = closestPoiAround(player, player.blockPosition(), null)
        // Ship blocks are in shipyard chunks, so a scan in the world frame does not find them.
        for (ship in level.getShipsIntersecting(player.boundingBox.inflate(NEAR_POI_RADIUS.toDouble()))) {
            val local = ship.worldToShip.transformPosition(Vector3d(player.x, player.y, player.z))
            closest = closestPoiAround(player, BlockPos.containing(local.x, local.y, local.z), closest)
        }
        return closest
    }

    private fun closestPoiAround(player: LocalPlayer, origin: BlockPos, best: BlockPos?): BlockPos? {
        val level = player.level()
        val cursor = BlockPos.MutableBlockPos()
        var closest = best
        var closestDistSq = best?.let { poiDistSq(player, it) } ?: (NEAR_POI_RADIUS * NEAR_POI_RADIUS).toDouble()
        for (dy in -NEAR_POI_RADIUS..NEAR_POI_RADIUS) {
            for (dx in -NEAR_POI_RADIUS..NEAR_POI_RADIUS) {
                for (dz in -NEAR_POI_RADIUS..NEAR_POI_RADIUS) {
                    cursor.set(origin.x + dx, origin.y + dy, origin.z + dz)
                    if (!level.getBlockState(cursor).`is`(CATALOGER_TARGETS)) continue
                    val distSq = poiDistSq(player, cursor)
                    if (distSq > closestDistSq) continue
                    closest = cursor.immutable()
                    closestDistSq = distSq
                }
            }
        }
        return closest
    }

    // VS2 makes Entity.distanceToSqr change a shipyard point to the world frame.
    private fun poiDistSq(player: LocalPlayer, pos: BlockPos): Double =
        player.distanceToSqr(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)

    /** Path to the tile in front of a faced target (lectern, sign…), the same rule the
     *  Cataloger uses. Null when the target has no facing or the tile is unreachable. */
    private fun frontPath(player: LocalPlayer, g: Cataloger, tgt: BlockPos): Path? {
        val front = WanderToTaggedBlockGoal.frontTileOf(player.level().getBlockState(tgt), tgt) ?: return null
        return g.navigation.createPath(front, 0)?.takeIf { it.canReach() }
    }

    private fun seek(player: LocalPlayer) {
        val g = ghostFor(player) ?: run { standStill(player); return }
        if (scanCooldown <= 0) {
            val picked = pickTarget(player, g)
            if (picked != null) {
                target = picked
                phase = Phase.MOVING
                ticksUntilRecompute = 0
                recomputeAttempts = 0
                return
            }
            scanCooldown = SCAN_COOLDOWN
        } else {
            scanCooldown--
        }
        // No POI found — drift to a random point. This is the *only* path into the
        // wander, mirroring the Cataloger's lower-priority fallback goal.
        startWander(player, g)
    }

    private fun move(player: LocalPlayer) {
        val t = target ?: run { phase = Phase.SEEKING; return }
        val g = ghostFor(player) ?: run { clearTarget(); return }

        if (poiDistSq(player, t) <= ARRIVE_DIST_SQ) {
            lookAt = voxelShapeCenter(player, t)
            phase = Phase.STARING
            staringTicks = 0
            standStill(player)
            return
        }

        syncGhost(g, player)

        if (g.navigation.isDone) {
            // Path ran out short of the target. Stand (Catalogers wait, they don't
            // wander) while we recompute; give up after a few tries.
            if (ticksUntilRecompute > 0) {
                ticksUntilRecompute--
            } else {
                val fresh = g.navigation.createPath(t, NAVIGATION_REGION_OFFSET)
                if (fresh != null && fresh.canReach()) {
                    g.navigation.moveTo(fresh, MOVE_SPEED)
                    recomputeAttempts = 0
                } else if (++recomputeAttempts >= MAX_RECOMPUTE_ATTEMPTS) {
                    clearTarget()
                    return
                }
                ticksUntilRecompute = RECOMPUTE_INTERVAL
            }
            standStill(player)
            return
        }

        driveAlongPath(player, g)
    }

    /** Picks a random point within [SEARCH_RADIUS] and starts walking there. Reached only from
     *  [seek] when no POI is available, so the wander never pre-empts cataloguing. */
    private fun startWander(player: LocalPlayer, g: Cataloger) {
        syncGhost(g, player)
        val pos = LandRandomPos.getPos(g, SEARCH_RADIUS, SEARCH_RADIUS / 2)
        if (pos == null) {
            standStill(player)
            return
        }
        // Same budget and partial-path rule as CatalogerWanderGoal. A full-budget pathfind that
        // must reach the point fails most ticks in the maze, and each failure stalls a frame.
        g.navigation.setMaxVisitedNodesMultiplier(Cataloger.WANDER_NODE_MULTIPLIER)
        val started = g.navigation.moveTo(pos.x, pos.y, pos.z, MOVE_SPEED)
        g.navigation.setMaxVisitedNodesMultiplier(Cataloger.PATHFIND_NODE_MULTIPLIER)
        if (started) phase = Phase.WANDERING else standStill(player)
    }

    private fun wander(player: LocalPlayer) {
        val g = ghostFor(player) ?: run { phase = Phase.SEEKING; return }
        // Keep checking for a real POI on the scan cadence; switch to it the moment
        // one appears — parity with the Cataloger's goal-priority interruption.
        if (scanCooldown > 0) {
            scanCooldown--
        } else {
            val picked = pickTarget(player, g)
            if (picked != null) {
                target = picked
                phase = Phase.MOVING
                ticksUntilRecompute = 0
                recomputeAttempts = 0
                return
            }
            scanCooldown = SCAN_COOLDOWN
        }
        syncGhost(g, player)
        if (g.navigation.isDone) {
            phase = Phase.SEEKING
            standStill(player)
            return
        }
        driveAlongPath(player, g)
    }

    /** Advance the ghost's navigation, ease the player toward the heading it
     *  produces (look forward, level pitch), and ping the server for dust. Shared
     *  by [move] and [wander]. */
    private fun driveAlongPath(player: LocalPlayer, g: Cataloger) {
        g.navigation.tick()
        g.moveControl.tick()
        applyYaw(player, ease(player.yRot, g.yRot, EASE_FACTOR))
        applyPitch(player, ease(player.xRot, 0f, EASE_FACTOR))
        walkForward(player)
        // Server-side spawn so every nearby player sees the dust (a local
        // addParticle would only show on this client).
        if (player.tickCount % DUST_INTERVAL_TICKS == 0) {
            NetworkManager.sendToServer(SselithMadness.MENU_WALK_DUST, FriendlyByteBuf(Unpooled.buffer()))
        }
    }

    private fun stare(player: LocalPlayer) {
        standStill(player)
        // lookAt is in the frame of the target block; a ship target moves each tick.
        lookAtPoint(player, player.level().toWorldCoordinates(lookAt))
        if (++staringTicks >= STARE_TICKS) clearTarget()
    }

    // ---- rolling POI cache --------------------------------------------------

    /** Sweep a slice of the 48-radius box into [scanBuffer]; publish to [poiCache]
     *  when a full pass completes. The box is re-anchored on the player at the start
     *  of each pass, so it tracks the player without re-scanning every pick. */
    private fun tickScan(player: LocalPlayer) {
        val level = player.level()
        val side = 2 * SEARCH_RADIUS + 1
        val sideY = 2 * SAME_FLOOR_Y + 1
        val total = side * side * sideY
        if (scanIndex == 0) {
            scanOrigin = player.blockPosition()
            scanBuffer = ArrayList()
        }
        val cursor = BlockPos.MutableBlockPos()
        var budget = SCAN_BUDGET_PER_TICK
        while (budget-- > 0 && scanIndex < total) {
            val i = scanIndex
            val dz = i % side - SEARCH_RADIUS
            val dx = (i / side) % side - SEARCH_RADIUS
            val dy = i / (side * side) - SAME_FLOOR_Y
            cursor.set(scanOrigin.x + dx, scanOrigin.y + dy, scanOrigin.z + dz)
            if (level.getBlockState(cursor).`is`(CATALOGER_TARGETS) && scanBuffer.size < MAX_CANDIDATES) {
                scanBuffer.add(cursor.immutable())
            }
            scanIndex++
        }
        if (scanIndex >= total) {
            poiCache = scanBuffer
            scanIndex = 0
        }
    }

    // ---- target selection (mirror of WanderToTaggedBlockGoal.pickTarget) -----

    private fun pickTarget(player: LocalPlayer, g: Cataloger): BlockPos? {
        val now = player.level().gameTime
        if (now >= nextRetryCleanup) {
            retryTimes.long2LongEntrySet().removeIf { it.longValue <= now }
            nextRetryCleanup = now + RETRY_CLEANUP_TICKS
        }

        val originY = player.blockY
        val ox = player.x
        val oz = player.z
        val candidates = ArrayList<BlockPos>()
        for (pos in poiCache) {
            if (Math.abs(pos.y - originY) > SAME_FLOOR_Y) continue
            if (Math.abs(pos.x + 0.5 - ox) > SEARCH_RADIUS || Math.abs(pos.z + 0.5 - oz) > SEARCH_RADIUS) continue
            if (retryTimes.get(pos.asLong()) > now) continue
            candidates.add(pos)
            if (candidates.size >= MAX_CANDIDATES) break
        }
        if (candidates.isEmpty()) return null

        val prev = lastTarget
        val pool = if (candidates.size > 1 && prev != null) {
            val filtered = candidates.filter { it != prev }
            (if (filtered.isEmpty()) candidates else filtered).toMutableList()
        } else {
            candidates.toMutableList()
        }

        val batch = HashSet<BlockPos>(BATCH_SIZE)
        while (batch.size < BATCH_SIZE && pool.isNotEmpty()) {
            val idx = player.random.nextInt(pool.size)
            batch.add(pool[idx])
            pool[idx] = pool[pool.size - 1]
            pool.removeAt(pool.size - 1)
        }

        syncGhost(g, player)
        val p: Path? = g.navigation.createPath(batch, NAVIGATION_REGION_OFFSET)
        if (p != null && p.canReach()) {
            val tgt = p.target?.immutable()
            if (tgt != null) {
                lastTarget = tgt
                g.navigation.moveTo(frontPath(player, g, tgt) ?: p, MOVE_SPEED)
                return tgt
            }
        }

        // Failed batch — mark every member as recently-tried, like the goal.
        val expiry = now + RETRY_COOLDOWN_TICKS
        for (pos in batch) retryTimes.put(pos.asLong(), expiry)
        return null
    }

    /** Snap the ghost oracle onto the player and mark it grounded — required for
     *  client-side `PathNavigation` to do anything. */
    private fun syncGhost(g: Cataloger, player: LocalPlayer) {
        g.moveTo(player.x, player.y, player.z, player.yRot, 0f)
        g.setOnGround(true)
        g.setDeltaMovement(Vec3.ZERO)
        // VS2 selects the ship frame for a pathfind from this state. The ghost never
        // ticks in the world, so it must get the state from the player.
        val from = (player as? IEntityDraggingInformationProvider)?.draggingInformation ?: return
        val to = (g as? IEntityDraggingInformationProvider)?.draggingInformation ?: return
        to.lastShipStoodOn = if (from.isEntityBeingDraggedByAShip()) from.lastShipStoodOn else null
        to.ticksSinceStoodOnShip = from.ticksSinceStoodOnShip
    }

    private fun ghostFor(player: LocalPlayer): Cataloger? {
        val level = player.level()
        var g = ghost
        if (g == null || g.level() !== level) {
            g = EKEntities.CATALOGER.get().create(level)
            ghost = g
        }
        return g
    }

    // ---- low-level steering / input -----------------------------------------

    private fun walkForward(player: LocalPlayer) {
        val input = player.input
        input.forwardImpulse = WALK_IMPULSE
        input.leftImpulse = 0f
        input.up = true
        input.down = false
        input.left = false
        input.right = false
        input.jumping = false
        input.shiftKeyDown = false
    }

    private fun standStill(player: LocalPlayer) {
        val input = player.input
        input.forwardImpulse = 0f
        input.leftImpulse = 0f
        input.up = false
        input.down = false
        input.left = false
        input.right = false
        input.jumping = false
        input.shiftKeyDown = false
    }

    private fun lookAtPoint(player: LocalPlayer, point: Vec3) {
        val dx = point.x - player.x
        val dy = point.y - player.eyeY
        val dz = point.z - player.z
        val horiz = Math.sqrt(dx * dx + dz * dz)
        val yaw = (Mth.atan2(dz, dx) * (180.0 / Math.PI)).toFloat() - 90f
        val pitch = (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI))).toFloat()
        applyYaw(player, ease(player.yRot, yaw, EASE_FACTOR))
        applyPitch(player, ease(player.xRot, pitch, EASE_FACTOR))
    }

    private fun applyYaw(player: LocalPlayer, yaw: Float) {
        // Snapshot the current rotations as their "previous tick" `*O`
        // values BEFORE overwriting. Vanilla only auto-updates
        // `yRotO`/`yHeadRotO`/`yBodyRotO` from `Entity.turn` (mouse
        // input), `lerpTo` (server-driven sync), and `moveTo`/
        // `absMoveTo` (teleport) — none of which fire while a screen
        // is open with no mouse movement. Without this manual
        // snapshot, `*O` stays frozen at whatever value it had when
        // the player last moved the mouse, and `getViewYRot`'s
        // `lerp(partialTick, *O, *)` sweeps the entire gap from
        // stale-value → current each frame, restarting at every
        // tick — visible as a fixed-cadence stutter on the camera.
        player.yRotO = player.yRot
        player.yHeadRotO = player.yHeadRot
        player.yBodyRotO = player.yBodyRot
        player.yRot = yaw
        player.yHeadRot = yaw
        player.yBodyRot = yaw
    }

    /** Sister to [applyYaw] for pitch — same `*O` issue, same fix.
     *  Snapshots `xRotO = xRot` before overwriting so the partial-tick
     *  pitch interpolation actually has a gap to lerp across. */
    private fun applyPitch(player: LocalPlayer, pitch: Float) {
        player.xRotO = player.xRot
        player.xRot = pitch
    }

    private fun voxelShapeCenter(player: LocalPlayer, pos: BlockPos): Vec3 {
        val level = player.level()
        val shape = level.getBlockState(pos).getShape(level, pos)
        if (shape.isEmpty) return Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        val bb = shape.bounds()
        return Vec3(
            pos.x + (bb.minX + bb.maxX) * 0.5,
            pos.y + (bb.minY + bb.maxY) * 0.5,
            pos.z + (bb.minZ + bb.maxZ) * 0.5,
        )
    }

    private fun clearTarget() {
        target = null
        phase = Phase.SEEKING
        scanCooldown = SCAN_COOLDOWN / 2
        ghost?.navigation?.stop()
    }

    private fun reset() {
        active = false
        phase = Phase.SEEKING
        target = null
        staringTicks = 0
        scanCooldown = 0
        scanIndex = 0
        poiCache = emptyList()
        retryTimes.clear()
        ghost?.navigation?.stop()
    }

    fun onDisconnect() {
        reset()
        clearTarget()
        ghost = null
    }

    private fun isActive(player: LocalPlayer): Boolean {
        val lost = player.isAlive && !player.isSpectator && SselithControlLoss.tick(player)
        SselithControlLoss.tickVignette(lost)
        return lost
    }

    /** Hard cap (degrees per tick) on the rotational delta the ease can
     *  apply. The vanilla rendered camera linearly interpolates yaw/pitch
     *  between `yRotO` (this-tick start) and `yRot` (this-tick end) for
     *  every frame — a clean exponential `delta * factor` gives a
     *  different per-tick step each tick (exponential decay), so the
     *  per-frame angular velocity has a visible "kink" at every tick
     *  boundary. Capping the step keeps velocity constant for the bulk
     *  of long turns, and `[factor]` only kicks in on the final approach
     *  where the soft landing is fine. 12°/tick ≈ 240°/s — fast enough
     *  to keep up with a Cataloger's gait, slow enough to read as a
     *  deliberate gaze. */
    private const val MAX_TURN_PER_TICK = 12f

    /** Ease toward an angle, capped at [MAX_TURN_PER_TICK]: constant
     *  per-tick angular velocity for the long-range portion of a turn,
     *  exponential approach for the soft landing. Eliminates the
     *  per-tick deceleration step that read as stutter in the raw
     *  `delta * factor` ease. */
    private fun ease(from: Float, to: Float, factor: Float): Float {
        val delta = Mth.wrapDegrees(to - from)
        val expStep = delta * factor
        val capped = expStep.coerceIn(-MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
        return from + capped
    }

    private val CATALOGER_TARGETS: TagKey<Block> =
        TagKey.create(Registries.BLOCK, EnderkinesisMod.id("cataloger_targets"))
}
