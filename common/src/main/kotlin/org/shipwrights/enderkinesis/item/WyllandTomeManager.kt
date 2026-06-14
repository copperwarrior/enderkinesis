package org.shipwrights.enderkinesis.item

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.physics.WyllandTomeForceInducer
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/**
 * Server-side bookkeeping + per-tick force application for the Wylland
 * Tome's "gravity gun" mechanic. One [Grab] per player UUID at most;
 * cleared on release, on death, or when the player puts the tome away.
 *
 * Per-tick, for every active grab:
 *
 *  - The desired world position is `player.eyePos + lookDir * grabDistance`.
 *  - **Ships** are nudged toward it by writing the desired target +
 *    accumulated torque impulse into the ship's
 *    [WyllandTomeForceInducer] attachment; the actual force/torque is
 *    applied on the physics thread.
 *  - **Entities** get their `deltaMovement` set to `(target - pos) * k`
 *    (a stiffer direct spring; entities aren't subject to VS2 physics).
 *  - Client mouse motion arrives via [applyRotateInput] (yaw +
 *    pitch degrees) and is converted to a world-frame torque impulse
 *    using the player's local up + right axes — full 3DOF rotation
 *    when the mod key is held.
 */
object WyllandTomeManager {

    /** Per-player grab record. */
    private data class Grab(
        var shipId: Long?,                    // VS2 ship id, or null for entity
        var entityUuid: UUID?,                // entity UUID, or null for ship
        var grabDistance: Double,
        /** Offset from the grabbed object's centre to the actual grab point
         *  in object-local frame — preserves the contact point so the
         *  target doesn't snap to its centre when picked up. */
        val localOffset: Vector3d,
        /** Desired world-frame orientation of the grabbed ship. Mutated
         *  by two sources:
         *   - Per-tick camera delta when the player's camera moves
         *     (default GMod-grab behaviour — ship orientation follows
         *     view).
         *   - Explicit mouse-delta via [applyRotateInput] when the mod
         *     key is held + the client locks the camera (GMod E-while-
         *     holding behaviour — rotate the ship without moving view).
         *  The two never fire on the same tick: camera lock makes the
         *  camera delta identically zero, so the explicit packet path
         *  is the sole contributor in mod-key mode. */
        var idealRotation: Quaterniond = Quaterniond(),
        /** Player camera quaternion at the end of the previous tick.
         *  Together with the current camera gives the per-tick delta
         *  for the always-on camera-driven rotation (ship path).
         *  Initialised on grab begin. */
        var lastPlayerRot: Quaterniond = Quaterniond(),
        /** Same idea as [lastPlayerRot] but in raw MC yaw/pitch degrees,
         *  for the entity grab path which has no quaternion concept. */
        var lastYRot: Float = 0f,
        var lastXRot: Float = 0f,
        /** Ticks of grace remaining for a freshly-assembled ship to show up
         *  in `loadedShips`. While > 0, a null `loadedShips.getById(shipId)`
         *  is treated as "not yet promoted" rather than "gone" so the grab
         *  is held in place until VS2's preTick wraps it into a
         *  `LoadedServerShip`. Zero for regular grabs (the ship was already
         *  loaded at grab time, missing it means gone). */
        var loadGraceTicks: Int = 0,
        /** Captured world-space anchor used while the ship hasn't loaded
         *  yet — sent to the client as the beam endpoint so the visual
         *  doesn't dangle at (0,0,0) during the grace window. */
        val pendingAnchor: Vector3d = Vector3d(),
    )

    private val grabs = ConcurrentHashMap<UUID, Grab>()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick { level ->
            tickAll(level)
        })
        // Cancel entity attacks while the player holds a tome — left-click
        // is the grab gesture, it must never hurt entities.
        PlayerEvent.ATTACK_ENTITY.register(
            PlayerEvent.AttackEntity { player, _, _, _, _ ->
                if (player.mainHandItem.item === EKItems.WYLLAND_TOME.get())
                    EventResult.interruptFalse()
                else
                    EventResult.pass()
            }
        )
    }

    /** Player wants to grab whatever's under their crosshair. Called from
     *  the network receiver. */
    fun beginGrab(player: ServerPlayer, target: GrabTarget) {
        if (player.mainHandItem.item !== EKItems.WYLLAND_TOME.get()) return
        // A spent tome refuses to grab until it's repaired. It never
        // shatters — it just goes inert at the bottom of its bar.
        if (WyllandTomeItem.isBroken(player.mainHandItem)) return
        when (target) {
            is GrabTarget.Ship -> {
                val ship = player.serverLevel().shipObjectWorld
                    .loadedShips.getById(target.shipId) ?: return
                // Client sends ship-frame coords directly — see
                // [WyllandTomeClient.tryBeginGrab]. The client re-runs
                // the voxelshape clip in ship-frame so partial blocks
                // (slabs, stairs) land exactly on the visible surface,
                // sidestepping VS2's mixed-frame BlockHitResult.
                grabLoadedShip(player, ship, target.shipLocalX, target.shipLocalY, target.shipLocalZ)
            }
            is GrabTarget.Entity -> {
                val entity = (player.level() as ServerLevel)
                    .getEntity(target.entityUuid) ?: return
                val eye = player.eyePosition
                val rawDist = entity.position().distanceTo(eye)
                val dist = rawDist.coerceIn(
                    WyllandTomeItem.MIN_GRAB_DISTANCE,
                    WyllandTomeItem.MAX_GRAB_DISTANCE,
                )
                grabs[player.uuid] = Grab(
                    shipId = null,
                    entityUuid = entity.uuid,
                    grabDistance = dist,
                    localOffset = Vector3d(0.0, 0.0, 0.0),
                    lastPlayerRot = playerRotToQuat(player.xRot.toDouble(), player.yRot.toDouble()),
                    lastYRot = player.yRot,
                    lastXRot = player.xRot,
                )
                // Mirror vanilla deflection (AbstractHurtingProjectile#hurt → setOwner): a
                // grabbed ghast fireball / dragon fireball / wither skull should hereafter
                // count as "thrown by the player", so kills attribute to the player and the
                // projectile won't pop on its original owner. Per-tick re-aim of velocity
                // and xPower/yPower/zPower lives in [applyToEntity].
                if (entity is AbstractHurtingProjectile) entity.owner = player
            }
        }
    }

    /** Install a grab record on the player keyed to an already-resolved
     *  loaded ship. Shared by the two entry points that get to this
     *  point with different ship-handle origins: [beginGrab] looks the
     *  ship up by id from a client packet; [beginGrabBlock] uses the
     *  freshly-assembled ship returned by [ShipAssembler.assembleToShip]
     *  directly (going through `loadedShips.getById` for that one fails
     *  — the ship isn't published to the loaded-ships index until the
     *  next tick, and the grab silently no-ops). */
    private fun grabLoadedShip(
        player: ServerPlayer,
        ship: LoadedServerShip,
        shipLocalX: Double, shipLocalY: Double, shipLocalZ: Double,
    ) {
        // Refuse to grab a ship that's currently dragging the player —
        // otherwise the player's "frame of reference" becomes the thing
        // they're trying to throw around and the result is a feedback
        // loop / teleport mess.
        if (draggingShipIdOf(player) == ship.id) return
        val grabPointLocal = Vector3d(shipLocalX, shipLocalY, shipLocalZ)
        val offset = Vector3d(grabPointLocal).sub(
            ship.transform.positionInShip
        )
        // World position only needed for the player-distance clamp.
        val grabPointWorld = Vector3d(grabPointLocal)
        ship.transform.shipToWorld.transformPosition(grabPointWorld)
        val eye = player.eyePosition
        val rawDist = grabPointWorld.distance(Vector3d(eye.x, eye.y, eye.z))
        val dist = rawDist.coerceIn(
            WyllandTomeItem.MIN_GRAB_DISTANCE,
            WyllandTomeItem.MAX_GRAB_DISTANCE,
        )
        grabs[player.uuid] = Grab(
            shipId = ship.id,
            entityUuid = null,
            grabDistance = dist,
            localOffset = offset,
            idealRotation = Quaterniond(ship.transform.shipToWorldRotation),
            lastPlayerRot = playerRotToQuat(player.xRot.toDouble(), player.yRot.toDouble()),
            lastYRot = player.yRot,
            lastXRot = player.xRot,
        )
    }

    /** Euclidean radius of the rip-up sphere, in blocks. 2.5 keeps the
     *  affected region inside a 5×5×5 bounding cube. The flood fill
     *  caps the BFS frontier at this distance from the click target so
     *  that a long, narrow strip of soft material (a buried wood beam,
     *  a tunnel of dirt) doesn't turn into a giant string of a ship. */
    private const val FLOOD_RADIUS: Double = 2.5

    /** Explosion-resistance cutoff for "hard" materials that the flood
     *  fill won't expand through. 6.0 is the resistance of stone — and
     *  cobblestone, deepslate, andesite, granite, iron block, etc. —
     *  putting wood logs (2.0), dirt (0.5) and leaves (0.2) on the soft
     *  side. Hard blocks the flood reaches are included as the *skin*
     *  of the rip, but they don't enqueue further neighbours; that's
     *  what produces the "stone wall stops the flood" behaviour. */
    private const val HARD_BLOCK_THRESHOLD: Float = 6.0f

    /** Raycast hit a world block. Flood-fill outward from the click
     *  target through connected solid blocks (face-neighbours, bounded
     *  by [FLOOD_RADIUS] from the click), stopping at hard materials
     *  and at air. Assemble that block set into a fresh VS2 ship and
     *  hand it to the existing grab path.
     *
     *  Authoritative validation lives here: the click target must still
     *  exist, be breakable, and not already belong to a ship. The client
     *  does a cheap pre-filter so it doesn't spam packets on unbreakable
     *  blocks, but a stale client view just no-ops here. */
    fun beginGrabBlock(
        player: ServerPlayer,
        pos: BlockPos,
        hitX: Double, hitY: Double, hitZ: Double,
    ) {
        if (player.mainHandItem.item !== EKItems.WYLLAND_TOME.get()) return
        if (WyllandTomeItem.isBroken(player.mainHandItem)) return
        val level = player.serverLevel()
        if (!level.isLoaded(pos)) return
        // Already part of a ship — the regular ship-grab path handles
        // these, we should not reach here from a sane client.
        if (level.getLoadedShipManagingPos(pos) != null || level.isBlockInShipyard(pos)) return

        val state = level.getBlockState(pos)
        if (state.isAir) return
        if (state.getDestroySpeed(level, pos) < 0f) return               // bedrock / unbreakable

        val blocks = floodFillSphere(level, pos)
        if (blocks.isEmpty()) return

        val ship = ShipAssembler.assembleToShip(level, blocks, 1.0)
        // Convert the world hit point into the new ship's shipyard
        // (local) frame. The transform is set up by `assembleToShip`
        // before it returns (kinematics are written via
        // `unsafeSetKinematics`), so it's safe to read here even though
        // the ship is not yet a `LoadedServerShip` — that promotion
        // happens during VS2's next game-tick pass.
        val localHit = Vector3d(hitX, hitY, hitZ)
        ship.transform.worldToShip.transformPosition(localHit)

        // Install the grab record IMMEDIATELY. The previous scheme
        // deferred install via a tick-task that polled `loadedShips`,
        // which left the player without a working grab for at least one
        // tick (and sometimes more, when VS2's preTick mass-check
        // bounced the promotion to a later tick). With the grab record
        // in place from the moment of assembly, all client inputs
        // (rotate, scroll, distance) accumulate from t=0 and the beam
        // visual lines up with a real grab record — applyGrab tolerates
        // a null `loadedShips.getById` lookup for [LOAD_GRACE_TICKS]
        // ticks so the wait for VS2 promotion happens transparently.
        installFreshlyAssembledGrab(player, ship,
            localHit.x, localHit.y, localHit.z,
            hitX, hitY, hitZ)
    }

    /** Grace window (ticks) we allow a freshly-assembled ship to take
     *  before it shows up in `loadedShips`. In practice it lands on the
     *  next tick; the wide budget (3 s at 20 tps) covers the mass-check
     *  edge case where VS2 delays the promotion until block updates have
     *  flushed. The grab record stays installed during the wait, so
     *  inputs are accumulated and the beam reads as connected. */
    private const val LOAD_GRACE_TICKS: Int = 60

    /** Install the grab record using a fresh [ServerShip] (i.e. one that
     *  has not yet been wrapped into a `LoadedServerShip`). Shares the
     *  rotation / offset / distance math with [grabLoadedShip] but reads
     *  off the raw ServerShip's transform (which is fully populated by
     *  `assembleToShip` before it returns), and sets [LOAD_GRACE_TICKS]
     *  so the grab survives the wait for VS2's preTick promotion. */
    private fun installFreshlyAssembledGrab(
        player: ServerPlayer,
        ship: org.valkyrienskies.core.api.ships.ServerShip,
        shipLocalX: Double, shipLocalY: Double, shipLocalZ: Double,
        worldHitX: Double, worldHitY: Double, worldHitZ: Double,
    ) {
        // Self-grab guard — same rule as [grabLoadedShip].
        if (draggingShipIdOf(player) == ship.id) return
        val grabPointLocal = Vector3d(shipLocalX, shipLocalY, shipLocalZ)
        val offset = Vector3d(grabPointLocal).sub(ship.transform.positionInShip)
        val eye = player.eyePosition
        val dx = worldHitX - eye.x
        val dy = worldHitY - eye.y
        val dz = worldHitZ - eye.z
        val rawDist = Math.sqrt(dx * dx + dy * dy + dz * dz)
        val dist = rawDist.coerceIn(
            WyllandTomeItem.MIN_GRAB_DISTANCE,
            WyllandTomeItem.MAX_GRAB_DISTANCE,
        )
        grabs[player.uuid] = Grab(
            shipId = ship.id,
            entityUuid = null,
            grabDistance = dist,
            localOffset = offset,
            idealRotation = Quaterniond(ship.transform.shipToWorldRotation),
            lastPlayerRot = playerRotToQuat(player.xRot.toDouble(), player.yRot.toDouble()),
            lastYRot = player.yRot,
            lastXRot = player.xRot,
            loadGraceTicks = LOAD_GRACE_TICKS,
            pendingAnchor = Vector3d(worldHitX, worldHitY, worldHitZ),
        )
    }

    /** Connected flood fill outward from [origin] through solid blocks,
     *  capped at [FLOOD_RADIUS] Euclidean blocks. Face-neighbour BFS so
     *  a one-block-thick wall is enough to block the flood (a 26-neighbour
     *  expansion would leak around corners). Reached blocks are added to
     *  the result set; only *soft* blocks (`explosionResistance` below
     *  [HARD_BLOCK_THRESHOLD]) enqueue further — hard blocks form the
     *  outer skin of the rip but stop propagation, which is how walls
     *  of stone bound the sphere without disappearing from it.
     *
     *  Air blocks and shipyard / loaded-ship blocks also bound the flood
     *  (we don't traverse empty space and we won't suck blocks out of
     *  someone else's hull). Unbreakable blocks (bedrock, etc.) get
     *  rejected even if reached. */
    private fun floodFillSphere(level: ServerLevel, origin: BlockPos): MutableSet<BlockPos> {
        val seed = origin.immutable()
        val included = HashSet<BlockPos>()
        val visited = HashSet<BlockPos>()
        included.add(seed)
        visited.add(seed)
        val queue = ArrayDeque<BlockPos>()
        queue.add(seed)
        val r2 = FLOOD_RADIUS * FLOOD_RADIUS
        val offsets = arrayOf(
            intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
            intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
            intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        )
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (off in offsets) {
                val n = BlockPos(p.x + off[0], p.y + off[1], p.z + off[2])
                if (!visited.add(n)) continue
                val dx = (n.x - origin.x).toDouble()
                val dy = (n.y - origin.y).toDouble()
                val dz = (n.z - origin.z).toDouble()
                if (dx * dx + dy * dy + dz * dz > r2) continue
                val ns = level.getBlockState(n)
                if (ns.isAir) continue
                if (level.getLoadedShipManagingPos(n) != null) continue
                if (level.isBlockInShipyard(n)) continue
                if (ns.getDestroySpeed(level, n) < 0f) continue
                val immutableN = n.immutable()
                included.add(immutableN)
                // Soft blocks keep the flood going; hard ones are skin only.
                if (ns.block.explosionResistance < HARD_BLOCK_THRESHOLD) {
                    queue.add(immutableN)
                }
            }
        }
        return included
    }

    /** Player let go of the grab key. */
    fun release(player: ServerPlayer) {
        val g = grabs.remove(player.uuid) ?: return
        deactivateShipInducer(player, g)
    }

    /** Server-initiated grab termination — used by all the per-tick
     *  invalidation paths (target gone, distance exceeded, player being
     *  dragged by their own grab). Deactivates the ship inducer (if any)
     *  and tells the client to stop drawing the beam this tick. The
     *  client-initiated [release] path skips the network notify because
     *  the client already knows. */
    private fun severGrab(player: ServerPlayer, g: Grab) {
        deactivateShipInducer(player, g)
        WyllandTomeNetwork.sendGrabEnd(player)
    }

    /** Flip the ship's [WyllandTomeForceInducer] back to inactive so the
     *  physics thread stops applying the cursor spring. The attachment
     *  stays on the ship for cheap reuse on a future grab. */
    private fun deactivateShipInducer(player: ServerPlayer, g: Grab) {
        val shipId = g.shipId ?: return
        val ship = player.serverLevel().shipObjectWorld.loadedShips.getById(shipId) ?: return
        ship.getAttachment(WyllandTomeForceInducer::class.java)?.let { ind ->
            ind.active = false
        }
    }

    /** Explicit mouse-delta rotation from the client, only sent while
     *  the player is holding the mod key (which locks their camera —
     *  so the per-tick camera-delta path in [applyGrab] contributes
     *  zero this tick and we're the sole rotation source).
     *
     *  Yaw rotates around world UP; pitch rotates around the player's
     *  camera-right axis on the horizontal plane. Combined into a
     *  single delta quaternion and left-multiplied onto [idealRotation]
     *  so it accumulates in world frame. */
    fun applyRotateInput(player: ServerPlayer, yawDelta: Double, pitchDelta: Double) {
        val g = grabs[player.uuid] ?: return
        if (g.shipId != null) {
            val yawRad = Math.toRadians(player.yRot.toDouble())
            val rightX = Math.cos(yawRad)
            val rightZ = Math.sin(yawRad)
            val deltaRot = Quaterniond()
                .rotateAxis(Math.toRadians(-yawDelta), 0.0, 1.0, 0.0)
                .rotateAxis(Math.toRadians(pitchDelta), rightX, 0.0, rightZ)
            g.idealRotation = deltaRot.mul(g.idealRotation, Quaterniond()).normalize()
        } else if (g.entityUuid != null) {
            val level = player.level() as ServerLevel
            val entity = level.getEntity(g.entityUuid!!) ?: return
            // Entities only get yaw — pitching a cow / villager / etc.
            // looks broken (head-attached, no proper pitched model) and
            // serves no game purpose. Ignore pitchDelta.
            entity.yRot = (entity.yRot + yawDelta.toFloat())
            entity.yRotO = entity.yRot
        }
    }

    /** Scroll-wheel roll (signed degrees). Applied immediately around
     *  the player's current look direction — for ships only; entities
     *  have no roll DOF and silently ignore. */
    fun applyRollInput(player: ServerPlayer, degrees: Double) {
        val g = grabs[player.uuid] ?: return
        g.shipId ?: return
        val look = player.lookAngle
        val deltaRot = Quaterniond()
            .rotateAxis(Math.toRadians(degrees), look.x, look.y, look.z)
        g.idealRotation = deltaRot.mul(g.idealRotation, Quaterniond()).normalize()
    }

    /** MC yaw/pitch → world-frame camera quaternion. Same convention as
     *  VMod's `playerRotToQuat` so the inducer's PD reads orientations
     *  in the expected frame. */
    private fun playerRotToQuat(pitch: Double, yaw: Double): Quaterniond =
        Quaterniond().rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch))

    /** Push/pull the target along the player's look ray. Positive
     *  [delta] pulls closer (scroll-up), negative pushes away
     *  (scroll-down). Clamped to the configured min/max grab distance. */
    fun adjustDistance(player: ServerPlayer, delta: Double) {
        val g = grabs[player.uuid] ?: return
        g.grabDistance = (g.grabDistance + delta).coerceIn(
            WyllandTomeItem.MIN_GRAB_DISTANCE,
            WyllandTomeItem.MAX_GRAB_DISTANCE,
        )
    }

    /** VS2 ship id that is currently dragging the player (player is
     *  standing on or seated in it), or null if the player is free.
     *  Used by [beginGrab] / [applyGrab] to prevent self-referential
     *  grabs that would lock the player to a moving frame they're
     *  trying to push. */
    private fun draggingShipIdOf(player: ServerPlayer): Long? {
        val drag = (player as IEntityDraggingInformationProvider).draggingInformation
        return if (drag.isEntityBeingDraggedByAShip()) drag.lastShipStoodOn else null
    }

    /** Returns the current grab world position for the player, or null
     *  if not grabbing — used by the client to render the beam endpoint. */
    fun grabPoint(playerUuid: UUID, level: ServerLevel): Vec3? {
        val g = grabs[playerUuid] ?: return null
        return resolveGrabPoint(g, level)
    }

    private fun resolveGrabPoint(g: Grab, level: ServerLevel): Vec3? {
        val ship = g.shipId?.let { level.shipObjectWorld.loadedShips.getById(it) }
        if (ship != null) {
            val localPlusOffset = Vector3d(g.localOffset).add(ship.transform.positionInShip)
            ship.transform.shipToWorld.transformPosition(localPlusOffset)
            return Vec3(localPlusOffset.x, localPlusOffset.y, localPlusOffset.z)
        }
        val entityUuid = g.entityUuid ?: return null
        val entity = level.getEntity(entityUuid) ?: return null
        return entity.position()
    }

    private fun tickAll(level: ServerLevel) {
        if (grabs.isEmpty()) return
        val it = grabs.entries.iterator()
        while (it.hasNext()) {
            val (uuid, g) = it.next()
            val player = level.getPlayerByUUID(uuid) as? ServerPlayer
            if (player == null || player.level() !== level) continue
            // Drop the grab if the player put the tome away (or died).
            if (!player.isAlive || player.mainHandItem.item !== EKItems.WYLLAND_TOME.get()) {
                it.remove()
                severGrab(player, g)
                continue
            }
            applyGrab(player, g, level)
        }
    }

    private fun applyGrab(player: ServerPlayer, g: Grab, level: ServerLevel) {
        // Durability: holding a target wears the tome down. When it
        // bottoms out the grab severs and won't re-engage until the
        // player feeds it written books — but the item is never
        // destroyed (see [WyllandTomeItem.isBroken]). Creative-mode
        // players bypass the drain entirely, matching the convention
        // used for book consumption in [WyllandTomeItem.use].
        val tome = player.mainHandItem
        if (!player.abilities.instabuild) {
            if (WyllandTomeItem.isBroken(tome)) {
                grabs.remove(player.uuid)
                severGrab(player, g)
                return
            }
            WyllandTomeItem.drain(tome)
        }

        val eye = player.eyePosition
        val look = player.lookAngle
        val target = Vec3(
            eye.x + look.x * g.grabDistance,
            eye.y + look.y * g.grabDistance,
            eye.z + look.z * g.grabDistance,
        )

        // Default GMod-grab behaviour: the player's camera rotation
        // delta is applied to the grabbed object every tick, so the
        // ship "follows the view". When the mod key is held the
        // client-side mixin freezes the camera (player.xRot/yRot don't
        // change), so this delta is identically zero and the explicit
        // [applyRotateInput] path is the sole rotation source.
        val currentRot = playerRotToQuat(player.xRot.toDouble(), player.yRot.toDouble())
        val entityYawDelta = (player.yRot - g.lastYRot).toDouble()
        if (g.shipId != null) {
            val delta = currentRot.mul(
                g.lastPlayerRot.conjugate(Quaterniond()),
                Quaterniond(),
            )
            g.idealRotation = delta.mul(g.idealRotation, Quaterniond()).normalize()
        }
        g.lastPlayerRot = currentRot
        g.lastYRot = player.yRot
        g.lastXRot = player.xRot

        if (g.shipId != null) {
            val ship = level.shipObjectWorld.loadedShips.getById(g.shipId!!)
            if (ship == null) {
                // Freshly-assembled ships take at least one tick to be
                // promoted into `loadedShips`. Hold the grab record open
                // for [LOAD_GRACE_TICKS] ticks while we wait; the client
                // beam keeps pointing at the captured initial anchor so
                // the visual reads as a continuous connection.
                if (g.loadGraceTicks > 0) {
                    g.loadGraceTicks--
                    WyllandTomeNetwork.sendGrabPointSync(
                        player,
                        g.shipId,
                        g.pendingAnchor.x, g.pendingAnchor.y, g.pendingAnchor.z,
                        target.x, target.y, target.z,
                    )
                    return
                }
                grabs.remove(player.uuid)
                // Ship gone — nothing to deactivate; the attachment died
                // with the ship. Client still needs to clear its beam.
                WyllandTomeNetwork.sendGrabEnd(player)
                return
            }
            // Ship is now loaded — clear the grace so any later
            // disappearance is treated as "gone" and severs the grab.
            g.loadGraceTicks = 0
            // Cut the connection if the player has stepped onto / been
            // mounted to the ship they're holding — same reasoning as in
            // beginGrab.
            if (draggingShipIdOf(player) == ship.id) {
                grabs.remove(player.uuid)
                severGrab(player, g)
                return
            }
            // Drop the grab if the anchor has drifted too far from the
            // player (e.g. ship slammed into a wall, player ran away).
            val anchor = shipGrabPointWorld(ship, g)
            if (player.eyePosition.distanceToSqr(anchor) >
                WyllandTomeItem.MAX_HOLD_DISTANCE * WyllandTomeItem.MAX_HOLD_DISTANCE
            ) {
                grabs.remove(player.uuid)
                severGrab(player, g)
                return
            }
            applyToShip(ship, target, g.idealRotation, g.localOffset)
            // Sync the actual grab anchor world position back to the
            // grabbing player so their beam draws to the contact point on
            // the ship. Also send the cursor (look-projected spring
            // target) — the client uses it as a bezier control point so
            // the beam visually bows when the ship lags behind the cursor.
            val grabPoint = shipGrabPointWorld(ship, g)
            WyllandTomeNetwork.sendGrabPointSync(
                player,
                ship.id,
                grabPoint.x, grabPoint.y, grabPoint.z,
                target.x, target.y, target.z,
            )
        } else if (g.entityUuid != null) {
            val entity = level.getEntity(g.entityUuid!!)
            if (entity == null || !entity.isAlive) {
                grabs.remove(player.uuid)
                WyllandTomeNetwork.sendGrabEnd(player)
                return
            }
            val anchor = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)
            if (player.eyePosition.distanceToSqr(anchor) >
                WyllandTomeItem.MAX_HOLD_DISTANCE * WyllandTomeItem.MAX_HOLD_DISTANCE
            ) {
                grabs.remove(player.uuid)
                WyllandTomeNetwork.sendGrabEnd(player)
                return
            }
            applyToEntity(entity, target, entityYawDelta)
            WyllandTomeNetwork.sendGrabPointSync(
                player,
                null,
                anchor.x, anchor.y, anchor.z,
                target.x, target.y, target.z,
            )
        }
    }

    /** Recover the world-space position of the grab anchor on a ship
     *  using the captured ship-local offset. */
    private fun shipGrabPointWorld(ship: LoadedServerShip, g: Grab): Vec3 {
        val localPlusOffset = Vector3d(g.localOffset).add(ship.transform.positionInShip)
        ship.transform.shipToWorld.transformPosition(localPlusOffset)
        return Vec3(localPlusOffset.x, localPlusOffset.y, localPlusOffset.z)
    }

    private fun applyToShip(
        ship: LoadedServerShip,
        target: Vec3,
        idealRotation: Quaterniond,
        localOffset: Vector3d,
    ) {
        // Push fresh state across to the physics thread. The inducer's
        // physTick reads these and computes the actual force / torque.
        val inducer = ship.getOrPutAttachment(WyllandTomeForceInducer::class.java) {
            WyllandTomeForceInducer()
        }
        inducer.targetX = target.x
        inducer.targetY = target.y
        inducer.targetZ = target.z
        inducer.localOffsetX = localOffset.x
        inducer.localOffsetY = localOffset.y
        inducer.localOffsetZ = localOffset.z
        // Copy in the player's current target orientation — defensive
        // copy because the inducer reads on the physics thread and the
        // manager mutates this on the network thread inside
        // applyRotateInput. JOML quaternions are mutable.
        inducer.idealRotation.set(idealRotation)
        inducer.active = true
    }

    private fun applyToEntity(
        entity: Entity,
        target: Vec3,
        yawDeg: Double,
    ) {
        val pos = entity.position()
        val dx = target.x - pos.x
        val dy = target.y - pos.y
        val dz = target.z - pos.z
        // Set velocity directly — entities are far lighter than ships so a
        // stiffer spring keeps them locked under the cursor without lag.
        val motion = Vec3(
            dx * WyllandTomeItem.SPRING_STIFFNESS,
            dy * WyllandTomeItem.SPRING_STIFFNESS,
            dz * WyllandTomeItem.SPRING_STIFFNESS,
        )
        entity.deltaMovement = motion
        entity.fallDistance = 0f
        entity.hurtMarked = true
        if (entity is Projectile) {
            // Arrows/tridents/etc. use their yRot/xRot to drive both the visual orientation
            // AND vanilla's next-tick re-aim (AbstractArrow#tick re-derives heading from
            // rotation when no acceleration is set). Without this, a grabbed arrow keeps
            // pointing the way it was originally fired, drifts off the motion vector, and
            // can fly sideways on release. Re-orient to the motion direction whenever the
            // motion is non-degenerate; below that floor the previous orientation is the
            // best guess.
            val mLenSq = motion.lengthSqr()
            if (mLenSq > 1e-8) {
                val mLen = Math.sqrt(mLenSq)
                val horiz = Math.sqrt(motion.x * motion.x + motion.z * motion.z)
                val degPerRad = 180.0 / Math.PI
                entity.yRot = (Math.atan2(motion.x, motion.z) * degPerRad).toFloat()
                entity.xRot = (Math.atan2(motion.y, horiz) * degPerRad).toFloat()
                entity.yRotO = entity.yRot
                entity.xRotO = entity.xRot
                // Ghast / dragon fireballs and wither skulls extend AbstractHurtingProjectile,
                // whose tick adds (xPower, yPower, zPower) to deltaMovement EVERY frame. The
                // velocity we just set is undone next tick unless we re-aim that acceleration
                // too — otherwise the fireball nudges back toward its original heading and on
                // release flies off in the wrong direction. Match vanilla's launch convention
                // of `dir.normalized * 0.1` so the released fireball cruises at fireball-y
                // speed in the cursor direction.
                if (entity is AbstractHurtingProjectile) {
                    val inv = 0.1 / mLen
                    entity.xPower = motion.x * inv
                    entity.yPower = motion.y * inv
                    entity.zPower = motion.z * inv
                }
            }
        } else if (yawDeg != 0.0) {
            // Yaw only — pitch on mob models looks broken (head decoupled
            // from body, etc.) and serves no game purpose.
            entity.yRot = (entity.yRot + yawDeg.toFloat())
            entity.yRotO = entity.yRot
        }
    }

    /** Target descriptors carried in the begin-grab network packet. */
    sealed interface GrabTarget {
        /** Hit position is in **ship-local (shipyard) coordinates** — the
         *  client re-runs the voxelshape clip in ship-frame after VS2's
         *  raycast identifies the block, so the value lands exactly on
         *  the visible block surface (including partial blocks). The
         *  server stores it as-is. */
        data class Ship(val shipId: Long, val shipLocalX: Double, val shipLocalY: Double, val shipLocalZ: Double) : GrabTarget
        data class Entity(val entityUuid: UUID) : GrabTarget
    }
}
