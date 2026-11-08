package org.shipwrights.enderkinesis.blockentity

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.ShipBlockMove
import org.shipwrights.enderkinesis.util.ShipMoveListener
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.util.GameTickOnly
import org.shipwrights.enderkinesis.util.loadedRayRange

/**
 * Two modes: static yaw (empty-hand right-click captures `player.lookAngle`) and compass-pinned
 * (slot compass pins world XZ). Pin bearing is recomputed every ~0.5 s on the game tick, not
 * the physics tick. PD gates off within ~1.2× ship horizontal AABB of the pin to avoid
 * bearing-spin when the eyeroscope is close but offset from ship centre.
 *
 * **Frame gotcha:** MC yaw is CW-positive but JOML +Y rotation is CCW-positive. Never extract
 * ship MC yaw via Euler decomp — transform the block's ship-local forward through
 * `shipToWorldRotation` and read `atan2(-x, z)`, same convention as `player.lookAngle`.
 *
 * **PD around world Y** uses `R·I·R⁻¹` sandwich (same pattern as [WyllandTomeForceInducer]).
 * Leading minus on the PD output: positive MC-yaw error means rotate CW (decreasing JOML +Y ω).
 */
@OptIn(VsBeta::class)
class EyeroscopeBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.EYEROSCOPE.get(), pos, state),
    BlockEntityPhysicsListener,
    Commandable,
    ShipMoveListener {

    @Volatile override lateinit var dimension: DimensionId

    /** Static target yaw (MC convention), NaN when no target. Persisted; ignored while a compass is in the slot. */
    private var staticTargetYawRad: Float = Float.NaN

    var compassStack: ItemStack = ItemStack.EMPTY
        private set

    @Volatile private var compassPinPos: BlockPos? = null

    /** World-frame target point set by the Staff of Command's block-hit branch. When
     *  non-null, [physTick] recomputes the steer yaw every tick from the current
     *  eyeroscope world position to this point — the same dynamic pattern compass-pin
     *  uses. A static yaw would freeze at command time and drift as the ship rotated
     *  the eyeroscope's world position around the COM, which is what produced the
     *  "aims off, inconsistent error" symptom. Cleared by compass insert,
     *  right-click empty-hand (static-yaw override), and the staff's no-hit
     *  fallback. Persists across save/load. */
    @Volatile private var staffPinWorld: Vec3? = null

    private var nextPinRefreshTick: Long = 0L

    /** Bridges the game-tick refresh (needs Level) to the physics-tick consumer (can't touch Level).
     *  Only used in *static yaw* mode (no compass). Compass-pinned mode computes the target
     *  fresh on every [physTick] from the host and pin transforms — no caching round-trip. */
    @Volatile private var cachedTargetYawRad: Float = Float.NaN

    /** MC-pitch component of the static target. 0 except in upgraded mode — non-upgraded
     *  eyeroscopes ignore pitch entirely. Same lifecycle as [cachedTargetYawRad]. */
    @Volatile private var cachedTargetPitchRad: Float = 0f

    /** Pin's owning ship (game-thread reference), cached for the physics tick to consult so
     *  it can transform the pin's shipyard coords to the *current* world position without a
     *  level lookup. `null` means the pin is world-placed and the raw BlockPos is its world
     *  centre. Refreshed periodically from [serverTick]. */
    @Volatile private var cachedPinShip: Ship? = null

    /** Host-ship-AABB-derived "we've arrived" radius², cached so [physTick] can compare
     *  against the squared distance to the pin without rebuilding the AABB every physics
     *  tick. Refreshed from [serverTick]. */
    @Volatile private var cachedDeadZoneSq: Double = WORLD_FALLBACK_DEADZONE_SQ

    /** Priority gate. When several eyeroscopes share a host ship, the one with the
     *  smallest [lastTargetDistSq] is "primary" and applies its PD torque each tick;
     *  the rest yield so they don't fight each other. Defaults to true so a freshly-
     *  loaded eyeroscope still steers before its first [serverTick] computes the
     *  comparison. World-placed eyeroscopes are always primary (no contention). */
    @Volatile private var isPrimaryEye: Boolean = true

    /** Distance² from this eyeroscope's world position to its target world position,
     *  cached from [serverTick] so the cross-eyeroscope comparison doesn't repeat the
     *  ship-transform math. [Double.MAX_VALUE] means "no pin set" — used as a loser
     *  sentinel in the priority compare. */
    private var lastTargetDistSq: Double = Double.MAX_VALUE

    /** Host ship id we're currently bound to in [eyeroscopesByShip]. `null` means we
     *  aren't registered (worldless or first tick). Changes trigger a
     *  remove-from-old + add-to-new in [updatePriorityRegistry]. */
    private var registeredHostId: Long? = null

    /** Set by the physics tick when the eyeroscope is inside the pin dead-zone — the game
     *  tick polls this to drive the comparator output, since the redstone update call
     *  itself needs to happen on the game thread. */
    @Volatile private var lastPhysInDeadZone: Boolean = false

    /** Companion to [staticTargetYawRad] for upgraded mode. NaN means "no static pitch" (the
     *  upgrade-eligible cases that haven't been stamped yet); 0 means horizontal aim. */
    private var staticTargetPitchRad: Float = 0f

    /** Echo-shard upgrade flag. When true, [physTick] runs the 3-axis controller and the
     *  renderer draws a glinted eye. One-way: once upgraded, no downgrade path. */
    var upgraded: Boolean = false
        private set

    @Volatile private var cachedFacing: Direction =
        state.getValueOrElse(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)

    /** PD output scales by `(15 − cachedPower) / 15`. Power 15 disables steering entirely. */
    @Volatile private var cachedPower: Int = 0

    /** Reused holder stub for the map's `tickCarriedBy` — see `tickMapHolder`. */
    private var mapHolder: EyeroscopeMapHolder? = null

    /** Comparator-output signal. 15 when a pin is set and the eyeroscope is inside the same
     *  ship-length-scaled dead-zone the PD uses as "we've arrived", 0 otherwise. Recomputed
     *  by [refreshPinBearing] (and cleared in [ejectCompass]); transitions ping comparator
     *  neighbours via [Level.updateNeighbourForOutputSignal] so the redstone graph picks
     *  up the new value at the same tick boundary the BE observed it. */
    var comparatorSignal: Int = 0
        private set

    /** Right-click empty-handed: stamp the player's current world look as the static target.
     *  Does NOT clear an existing compass pin — the use-handler checks slot state first and
     *  routes empty-handed clicks to either eject (slot full) or this (slot empty). In
     *  upgraded mode the pitch component is honoured; otherwise we silently drop it so the
     *  yaw-only controller sees the same value it would have without the upgrade. */
    fun setStaticTargetYaw(yawRad: Float, pitchRad: Float = 0f) {
        val newPitch = if (upgraded) pitchRad else 0f
        val changed = staffPinWorld != null || yawRad != staticTargetYawRad || newPitch != staticTargetPitchRad
        staticTargetYawRad = yawRad
        staticTargetPitchRad = newPitch
        cachedTargetYawRad = yawRad
        cachedTargetPitchRad = staticTargetPitchRad
        staffPinWorld = null   // explicit static heading overrides any prior staff pin
        syncAfterChange()
        if (changed) playAimSound()
    }

    private var lastAimSoundTick = 0L

    /** The aim files are approximately 2 s long. The cooldown prevents a stack of them. */
    private fun playAimSound() {
        val serverLevel = level as? ServerLevel ?: return
        if (serverLevel.gameTime - lastAimSoundTick in 0 until AIM_SOUND_COOLDOWN_TICKS) return
        lastAimSoundTick = serverLevel.gameTime
        val pitch = AIM_SOUND_PITCH_MIN + serverLevel.random.nextFloat() * AIM_SOUND_PITCH_SPREAD
        serverLevel.playSound(null, blockPos, EKSounds.EYEROSCOPE_AIM.get(), SoundSource.BLOCKS, AIM_SOUND_VOLUME, pitch)
    }

    private fun playSlotSound(sound: SoundEvent) {
        (level as? ServerLevel)?.playSound(null, blockPos, sound, SoundSource.BLOCKS, 1f, 1f)
    }

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        if (level.isClientSide) EyeroscopeClientRegistry.register(level, this)
    }

    /** Staff of Command implementation. Default (empty off-hand): clip a ray from the
     *  wielder's eye in their look direction. If it hits a block, latch the hit world
     *  point as a **dynamic pin** ([staffPinWorld]) — [physTick] recomputes the steer
     *  yaw every tick from the eyeroscope's current world position, so the heading
     *  stays accurate as the ship rotates the eyeroscope around its COM. If the ray
     *  misses, fall back to a static yaw matching the wielder's look direction (the
     *  same semantic as right-clicking the eyeroscope empty-handed). Future off-hand
     *  modes are gated by the [contextStack] check below.
     *
     *  Why dynamic rather than a frozen yaw: storing one yaw at command time only works
     *  when the eyeroscope sits exactly on the ship COM. Anywhere else, ship rotation
     *  translates the eyeroscope's world position, and the angle it needs to face the
     *  same world point drifts — which is the "aims off, error not consistent" symptom
     *  the original frozen-yaw implementation produced. The compass-pin mode handles
     *  the same problem by tracking a world position and recomputing yaw every tick;
     *  the staff hits pin shares that machinery. */
    override fun executeStaffCommand(player: Player, hostShip: Ship?, contextStack: ItemStack) {
        // The eyeroscope has no trigger — pointing it somewhere IS the whole
        // command, which the staff's left-click "Aim To" delivers below.
    }

    override fun executeAimCommand(player: Player, hostShip: Ship?, contextStack: ItemStack) {
        if (!contextStack.isEmpty) return  // reserved for future off-hand-driven modes
        val serverLevel = level as? ServerLevel ?: return

        // 256-block reach raycast. clip()'s BlockHitResult is mixed-frame on VS2 ship
        // blocks, but .location is always world coords — that's what we latch.
        val eye = player.getEyePosition(1f)
        val look = player.getViewVector(1f)
        val end = eye.add(look.scale(serverLevel.loadedRayRange(eye, look, 256.0)))
        val hit = serverLevel.clip(
            ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player),
        )

        if (hit.type == HitResult.Type.BLOCK) {
            val previousPin = staffPinWorld
            pinFromGazeHit(serverLevel, hit)
            if (staffPinWorld != previousPin) playAimSound()
            return
        }

        // Ray missed every block — fall back to the wielder's look direction.
        // VS2 mixes ship rotation into Player.getLookAngle, so this is world-frame
        // even when the wielder is standing on a turned ship. setStaticTargetYaw
        // clears [staffPinWorld] internally so a previous staff pin is dropped.
        val yawRad = Math.atan2(-look.x, look.z).toFloat()
        val pitchRad = Math.asin(-look.y).toFloat()
        setStaticTargetYaw(yawRad, pitchRad)
    }

    /** Ship-block pin anchor: when the staff's gaze lands on a SHIP block, the pin
     *  tracks the block's WORLD position as the ship moves ([serverTick] re-derives
     *  [staffPinWorld] through the live transform). -1 = plain static world pin. */
    private var pinShipId: Long = -1L
    private var pinShipX = 0.0
    private var pinShipY = 0.0
    private var pinShipZ = 0.0

    /** Pin the eye on a world point (staff gaze hit or a Shulkonaut order). Pin
     *  overrides any prior static yaw; cachedTargetYawRad goes NaN so the physTick
     *  branch order picks the pin path. */
    private fun pinToWorldPoint(point: net.minecraft.world.phys.Vec3) {
        pinShipId = -1L
        staffPinWorld = point
        staticTargetYawRad = Float.NaN
        cachedTargetYawRad = Float.NaN
        staticTargetPitchRad = 0f
        cachedTargetPitchRad = 0f
        syncAfterChange()
    }

    /** Pin onto a SHIP-frame point — the eye keeps tracking the block through the
     *  ship's motion instead of staring at where it used to be. */
    private fun pinToShipPoint(shipId: Long, shipFrame: org.joml.Vector3dc, worldNow: net.minecraft.world.phys.Vec3) {
        pinToWorldPoint(worldNow)
        pinShipId = shipId
        pinShipX = shipFrame.x()
        pinShipY = shipFrame.y()
        pinShipZ = shipFrame.z()
    }

    /** Re-derive a tracked pin's world point from the live ship transform. */
    @OptIn(GameTickOnly::class)
    private fun refreshTrackedPin(level: ServerLevel) {
        if (pinShipId == -1L) return
        val ship = level.shipObjectWorld.loadedShips.getById(pinShipId) ?: return
        val v = ship.shipToWorld.transformPosition(org.joml.Vector3d(pinShipX, pinShipY, pinShipZ))
        staffPinWorld = Vec3(v.x, v.y, v.z)
    }

    override fun onShipBlocksMoved(level: ServerLevel, move: ShipBlockMove) {
        val point = org.joml.Vector3d(pinShipX, pinShipY, pinShipZ)
        if (move.followPoint(pinShipId, point)) {
            pinShipId = move.toShipId
            pinShipX = point.x
            pinShipY = point.y
            pinShipZ = point.z
            setChanged()
        }
        compassPinPos?.let { pin ->
            if (move.contains(pin)) {
                compassPinPos = move.mapBlock(pin)
                setChanged()
            }
        }
    }

    /** Classify a mixed-frame gaze hit: pin tracked for ship blocks, static otherwise.
     *  VS2's clip returns .location in WORLD and .blockPos in SHIPYARD coords. */
    private fun pinFromGazeHit(serverLevel: ServerLevel, hit: net.minecraft.world.phys.BlockHitResult) {
        val ship = serverLevel.getShipManagingPos(hit.blockPos)
        if (ship != null) {
            val local = ship.transform.worldToShip.transformPosition(
                org.joml.Vector3d(hit.location.x, hit.location.y, hit.location.z),
            )
            pinToShipPoint(ship.id, local, hit.location)
        } else {
            pinToWorldPoint(hit.location)
        }
    }

    /** Shulkonaut orders: track the target. Re-pinned every command beat, so a moving
     *  target gets followed in steps. */
    override fun executeAutoCommand(target: net.minecraft.world.entity.Entity, hostShip: Ship?) {
        pinToWorldPoint(target.getEyePosition(1f))
    }

    override fun executeAutoCommandAt(
        targetPos: net.minecraft.world.phys.Vec3,
        targetAnchor: net.minecraft.core.BlockPos?,
        hostShip: Ship?,
    ) {
        pinToWorldPoint(targetPos)
    }

    override fun executeAutoAim(targetPos: net.minecraft.world.phys.Vec3, hostShip: Ship?): Boolean {
        pinToWorldPoint(targetPos)
        return true
    }

    /** Echo-shard right-click. No-op if already upgraded so a second shard isn't silently
     *  consumed. Persists immediately. */
    fun applyUpgrade(): Boolean {
        if (upgraded) return false
        upgraded = true
        syncAfterChange()
        playSlotSound(EKSounds.EYEROSCOPE_UPGRADE.get())
        return true
    }

    /** Right-click with a compass: capture the compass into the slot and pin its tracked
     *  position. The static yaw is left alone so removing the compass can fall back to it.
     *  The bearing cache is force-refreshed *now* so the ship starts steering immediately
     *  instead of waiting up to PIN_REFRESH_INTERVAL_TICKS for the next scheduled refresh. */
    fun insertCompass(stack: ItemStack, pinned: BlockPos) {
        compassStack = stack
        compassPinPos = pinned
        staffPinWorld = null                               // compass-pin overrides any staff pin
        nextPinRefreshTick = 0L                            // refresh on the very next serverTick
        cachedPinShip = null                               // force resolution on next resolver pass
        // Don't touch cachedTargetYawRad here — pin-mode physTick computes its own target from
        // the cached ship + raw pin pos and ignores `cachedTargetYawRad` entirely.
        syncAfterChange()
        playSlotSound(if (isBook(stack)) EKSounds.EYEROSCOPE_BOOK_INSERT.get() else EKSounds.EYEROSCOPE_INSERT.get())
    }

    private fun isBook(stack: ItemStack): Boolean = stack.`is`(Items.WRITTEN_BOOK) || stack.`is`(Items.WRITABLE_BOOK)

    /** Right-click empty-handed while a compass is in the slot: pull the compass out, freeze
     *  the most-recently sampled bearing as the new static target. The returned stack is what
     *  gets handed back to the player. */
    fun ejectCompass(): ItemStack {
        val out = compassStack
        releaseMapHolder(out)
        val frozen = cachedTargetYawRad
        if (!frozen.isNaN()) staticTargetYawRad = frozen
        if (upgraded) staticTargetPitchRad = cachedTargetPitchRad
        cachedTargetYawRad = staticTargetYawRad
        cachedTargetPitchRad = if (upgraded) staticTargetPitchRad else 0f
        compassStack = ItemStack.EMPTY
        compassPinPos = null
        cachedPinShip = null
        lastPhysInDeadZone = false
        syncAfterChange()
        playSlotSound(if (isBook(out)) EKSounds.EYEROSCOPE_BOOK_EJECT.get() else EKSounds.EYEROSCOPE_EJECT.get())
        return out
    }

    private fun syncAfterChange() {
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    override fun setRemoved() {
        level?.let { if (it.isClientSide) EyeroscopeClientRegistry.unregister(it, this) }
        super.setRemoved()
        releaseMapHolder(compassStack)
        registeredHostId?.let { eyeroscopesByShip[it]?.remove(blockPos) }
        registeredHostId = null
    }

    /** Renderer reads this. NaN if nothing's set; finite radians (MC yaw) otherwise. */
    fun getStaticTargetYaw(): Float = staticTargetYawRad

    /** Static MC pitch for the eye to aim at. Always 0 in non-upgraded mode (the field
     *  is silently zeroed inside [setStaticTargetYaw]); only upgraded eyeroscopes can
     *  store a non-zero static pitch. */
    fun getStaticTargetPitch(): Float = staticTargetPitchRad

    /** Renderer side: pinned position if any, else null. */
    fun getCompassPin(): BlockPos? = compassPinPos

    fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        refreshTrackedPin(level)
        // Refresh facing each tick — cheap. Survives the block being rotated by some other
        // mod's tooling without needing a custom hook.
        cachedFacing = state.getValueOrElse(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
        // Poll redstone input — 6 neighbour lookups; PD scales itself by (15 − power)/15.
        cachedPower = level.getBestNeighborSignal(pos)

        // Compass-pinned mode: refresh ship references + dead-zone² on a slow cadence.
        // The bearing itself is computed every physics tick in [physTick] from these
        // cached references, so the game tick only does the Level-bound resolution work
        // (which can't run from the physics thread). The cadence trades a 0.5-second
        // staleness on pin-ship attachment changes against not re-running
        // `findShipForShipyardPos` 60 times a second.
        if (compassPinPos != null && level.gameTime >= nextPinRefreshTick) {
            refreshPinShipResolution(level, pos)
            nextPinRefreshTick = level.gameTime + PIN_REFRESH_INTERVAL_TICKS
        }

        tickMapHolder(level, pos)

        val newSignal = computeComparatorSignal()
        if (newSignal != comparatorSignal) {
            comparatorSignal = newSignal
            level.updateNeighbourForOutputSignal(pos, blockState.block)
        }

        updatePriorityRegistry(level, pos)
    }

    /** Drive the filled map's column-sampling + cursor placement off the eyeroscope's
     *  own pose rather than a passing player's. A reused stub `Player` is teleported to
     *  the eyeroscope's world XYZ and yawed to FACING's world MC yaw before every
     *  `tickCarriedBy` call; vanilla then reads `getX/Y/Z` for the discovery centre and
     *  `getYRot` for the PLAYER decoration's rotation byte. Net result: the cursor sits
     *  on the eyeroscope and spins as the ship turns, and newly-revealed chunks fill in
     *  centred on the block rather than wherever a nearby rider happens to be standing.
     *
     *  The map is in this BE (not anyone's real inventory) so vanilla's
     *  `MapItem.inventoryTick` path never fires — without this call the colours array
     *  freezes at whatever was on the map at insert time and no cursor appears. */
    private fun tickMapHolder(level: ServerLevel, pos: BlockPos) {
        val stack = compassStack
        if (!stack.`is`(Items.FILLED_MAP)) return
        val mapData = MapItem.getSavedData(stack, level) ?: return

        val ship = findShipForShipyardPos(level, pos)
        val w = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        if (ship != null) ship.transform.shipToWorld.transformPosition(w)

        // World MC yaw of FACING — block-local facing rotated through shipToWorld, then
        // atan2 in the standard MC convention (matches `player.lookAngle`'s yaw).
        val facingDir = Vector3d(cachedFacing.stepX.toDouble(), 0.0, cachedFacing.stepZ.toDouble())
        if (ship != null) ship.transform.shipToWorld.transformDirection(facingDir)
        val headingDeg = Math.toDegrees(Math.atan2(-facingDir.x, facingDir.z)).toFloat()

        val holder = ensureMapHolder(level)
        holder.setPos(w.x, w.y, w.z)
        holder.setYRot(headingDeg)
        holder.yRotO = headingDeg
        holder.setYHeadRot(headingDeg)
        holder.yHeadRotO = headingDeg
        // Vanilla `tickCarriedBy` (offsets 131-148 in 1.20.1) removes the holder and its
        // decoration whenever `player.getInventory().contains(mapStack)` is false. Our
        // stub starts with an empty inventory, so without this line the cursor flickers
        // off every tick and any real player who happens to also hold a copy of the same
        // mapId becomes the only PLAYER decoration in the data → cursor follows them.
        // Planting our stack in slot 0 makes the contains check pass.
        holder.inventory.setItem(0, stack)
        mapData.tickCarriedBy(holder, stack)
    }

    /** Re-register self in [eyeroscopesByShip] (or remove if we just became worldless),
     *  recompute our distance² to the current target, and decide whether we're the
     *  primary eyeroscope for our host ship. World-placed eyeroscopes are always primary
     *  (no contention possible). */
    @OptIn(GameTickOnly::class)
    private fun updatePriorityRegistry(level: ServerLevel, pos: BlockPos) {
        val host = level.getLoadedShipManagingPos(pos)
        val currentId = host?.id
        if (currentId != registeredHostId) {
            registeredHostId?.let { eyeroscopesByShip[it]?.remove(pos) }
            if (currentId != null) {
                eyeroscopesByShip.computeIfAbsent(currentId) { ConcurrentHashMap() }[pos] = this
            }
            registeredHostId = currentId
        }

        lastTargetDistSq = computeTargetDistSq(level, pos)

        if (currentId == null) { isPrimaryEye = true; return }
        val siblings = eyeroscopesByShip[currentId]
        if (siblings == null || siblings.size <= 1) { isPrimaryEye = true; return }
        val myPosLong = pos.asLong()
        val myDist = lastTargetDistSq
        var iWin = true
        for ((otherPos, otherBe) in siblings) {
            if (otherBe === this) continue
            val theirDist = otherBe.lastTargetDistSq
            if (theirDist < myDist) { iWin = false; break }
            if (theirDist == myDist && otherPos.asLong() < myPosLong) { iWin = false; break }
        }
        isPrimaryEye = iWin
    }

    /** Eye-to-target world distance². Sources, in order of priority:
     *
     *  1. **Compass pin** — target is the pin's shipyard centre transformed through
     *     [cachedPinShip]'s `shipToWorld` (or the raw centre for world-placed pins).
     *
     *  Returns [Double.MAX_VALUE] when there is no pin — those eyeroscopes
     *  lose every priority compare with an aimed sibling and only win among themselves,
     *  which is fine because their physics tick already gates on a non-NaN static-yaw
     *  target before applying torque. */
    private fun computeTargetDistSq(level: ServerLevel, pos: BlockPos): Double {
        val eyeWorld = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        val hostShip = findShipForShipyardPos(level, pos)
        hostShip?.transform?.shipToWorld?.transformPosition(eyeWorld)

        val pin = compassPinPos
        if (pin != null) {
            val pinWorld = Vector3d(pin.x + 0.5, pin.y + 0.5, pin.z + 0.5)
            cachedPinShip?.transform?.shipToWorld?.transformPosition(pinWorld)
            val dx = eyeWorld.x - pinWorld.x
            val dy = eyeWorld.y - pinWorld.y
            val dz = eyeWorld.z - pinWorld.z
            return dx * dx + dy * dy + dz * dz
        }

        return Double.MAX_VALUE
    }

    /** Lazy-create the server-side holder stub. One per BE, reused across ticks — vanilla's
     *  `carriedByPlayers` map keys on the Player *object reference*, so creating a fresh
     *  stub each tick would silently accumulate dead entries in the map data. The
     *  per-BE stub gets one entry that stays live for the BE's lifetime; on world reload
     *  the BE rebuilds it, and vanilla's `carriedBy` lists are transient so the old one
     *  is gone. Stable UUID + name derived from `blockPos` so the PLAYER decoration
     *  (keyed by name) doesn't collide between eyeroscopes. */
    /** Vanilla removes a holder whose inventory does not have the map. Without this call the
     *  map data keeps the stub player and its decoration for the session. */
    private fun releaseMapHolder(stack: ItemStack) {
        val holder = mapHolder ?: return
        mapHolder = null
        val server = level as? ServerLevel ?: return
        if (!stack.`is`(Items.FILLED_MAP)) return
        holder.inventory.setItem(0, ItemStack.EMPTY)
        MapItem.getSavedData(stack, server)?.tickCarriedBy(holder, stack)
    }

    private fun ensureMapHolder(level: ServerLevel): EyeroscopeMapHolder {
        val existing = mapHolder
        if (existing != null && existing.level() === level) return existing
        val seed = "eyeroscope:${blockPos.asLong()}"
        val uuid = java.util.UUID.nameUUIDFromBytes(seed.toByteArray())
        val profile = com.mojang.authlib.GameProfile(uuid, "Eyeroscope_${blockPos.x}_${blockPos.y}_${blockPos.z}")
        val fresh = EyeroscopeMapHolder(level, blockPos, profile)
        mapHolder = fresh
        return fresh
    }

    /** Minimal server-side `Player` subclass — exists only so `tickCarriedBy` has
     *  something to read position/rotation off. Never added to the level's player list,
     *  never has an inventory item, never gets ticked by the entity system. */
    private class EyeroscopeMapHolder(
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        profile: com.mojang.authlib.GameProfile,
    ) : net.minecraft.world.entity.player.Player(level, pos, 0f, profile) {
        override fun isSpectator(): Boolean = false
        override fun isCreative(): Boolean = false
    }

    /** Game-tick pin maintenance. Resolves the pin's owning ship (so [physTick] can apply
     *  its transform to the raw pin BlockPos every physics tick) and recomputes the host
     *  ship's dead-zone radius² (which depends on its shipAABB). Both are level-bound
     *  lookups; cache them so the physics thread doesn't need to touch the level.
     *
     *  The bearing itself is **not** computed here any more — [physTick] does it fresh
     *  every physics tick from the cached ship references, so the steered heading keeps
     *  up with translation even at high ship speeds. */
    private fun refreshPinShipResolution(level: ServerLevel, pos: BlockPos) {
        val pin = compassPinPos ?: return
        cachedPinShip = findShipForShipyardPos(level, pin)
        cachedDeadZoneSq = computeDeadZoneSq(findShipForShipyardPos(level, pos))
    }

    /** Resolve a `BlockPos` to whichever ship's shipyard it falls into, or null when the
     *  coordinates aren't in any ship's shipyard region. Uses `allShips.getByChunkPos`
     *  rather than `getLoadedShipManagingPos` so a ship that exists with a saved
     *  transform but isn't currently in `loadedShips` (no active physics step) still
     *  resolves — `Ship.transform.shipToWorld` is populated either way and gives us the
     *  best available world position. */
    @OptIn(GameTickOnly::class)
    private fun findShipForShipyardPos(level: ServerLevel, pos: BlockPos): Ship? {
        val sow = level.shipObjectWorld
        val dim = level.dimensionId
        val cx = pos.x shr 4
        val cz = pos.z shr 4
        if (!sow.isChunkInShipyard(cx, cz, dim)) return null
        return sow.allShips.getByChunkPos(cx, cz, dim)
    }

    /** PD dead-zone radius² in blocks². 1.2× the ship's *longest horizontal extent* (max of
     *  X- and Z-side lengths of its local AABB) lets a 30-block-long ship treat anywhere
     *  within ~36 blocks of the pin as "arrived" rather than circling in place. Falls back
     *  to a fixed 1.0 m² for world-placed eyeroscopes (no ship → no spin problem to gate).
     *  Uncapped on purpose: a giant ship needs a giant dead-zone to stop spinning. The
     *  *comparator* output is capped separately — see [comparatorDeadZoneSq]. */
    private fun computeDeadZoneSq(ship: Ship?): Double {
        val sb = ship?.shipAABB ?: return WORLD_FALLBACK_DEADZONE_SQ
        val dx = (sb.maxX() + 1 - sb.minX()).toDouble()
        val dz = (sb.maxZ() + 1 - sb.minZ()).toDouble()
        val length = Math.max(dx, dz)
        val r = SHIP_LENGTH_DEADZONE_FACTOR * length
        return r * r
    }

    /** Binary 0/15 mirrored from [lastPhysInDeadZone]: full strength means "we've arrived at the pin". */
    private fun computeComparatorSignal(): Int =
        if (compassPinPos != null && lastPhysInDeadZone) MAX_REDSTONE else 0

    /** PD "stop steering, we've arrived" threshold. Mirrors [cachedDeadZoneSq] up to a
     *  hard cap of [MAX_PD_DEADZONE_RADIUS]. Independent of the comparator cap so the two
     *  knobs can be tuned separately. */
    private fun pdDeadZoneSq(): Double =
        Math.min(cachedDeadZoneSq, MAX_PD_DEADZONE_RADIUS * MAX_PD_DEADZONE_RADIUS)

    /** Comparator "arrived at target" threshold. Mirrors [cachedDeadZoneSq] up to a hard
     *  cap of [MAX_COMPARATOR_DEADZONE_RADIUS] — so the comparator output flips to full
     *  strength within 32 b of the target regardless of how big the host ship is. */
    private fun comparatorDeadZoneSq(): Double =
        Math.min(cachedDeadZoneSq, MAX_COMPARATOR_DEADZONE_RADIUS * MAX_COMPARATOR_DEADZONE_RADIUS)

    @OptIn(PhysTickOnly::class)
    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        if (physShip == null) return                       // world-placed: nothing to turn
        if (!isPrimaryEye) return                          // a sibling eyeroscope on this ship aimed closer
        if (physShip.isStatic || physShip.mass <= 0.0) return

        // Redstone gate: power 15 multiplies PD output by 0, fully suppressing torque so
        // the ship coasts on its own angular damping. Power 0 leaves the controller at
        // full strength.
        val powerScale = (MAX_REDSTONE - cachedPower).toDouble() / MAX_REDSTONE
        if (powerScale <= 0.0) return

        val pin = compassPinPos
        if (pin == null) {
            // Staff-of-Command pin mode — same dynamic-yaw pattern the compass pin uses,
            // but the target is a raw world point captured by the staff's raycast hit
            // (no owning-ship tracking, no game-tick refresh cycle). Recomputing every
            // physics tick from the eyeroscope's current world position is what keeps
            // the heading accurate while the ship rotates.
            val staff = staffPinWorld
            if (staff != null) {
                val myWorld = Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
                physShip.transform.shipToWorld.transformPosition(myWorld)
                val dx = staff.x - myWorld.x
                val dy = staff.y - myWorld.y
                val dz = staff.z - myWorld.z
                val horizDistSq = dx * dx + dz * dz
                if (horizDistSq >= pdDeadZoneSq()) {
                    val targetYaw = Math.atan2(-dx, dz).toFloat()
                    if (upgraded) {
                        val targetPitch = Math.atan2(-dy, Math.sqrt(horizDistSq)).toFloat()
                        physTick3Axis(physShip, targetYaw, targetPitch, powerScale)
                    } else {
                        physTickYawOnly(physShip, targetYaw, powerScale)
                    }
                }
                return
            }

            // Static yaw mode — cached target was stamped from a player look on the game
            // thread; physics tick just reads it.
            val staticYaw = cachedTargetYawRad
            if (staticYaw.isNaN()) return
            if (upgraded) physTick3Axis(physShip, staticYaw, cachedTargetPitchRad, powerScale)
            else physTickYawOnly(physShip, staticYaw, powerScale)
            return
        }

        // Compass-pinned mode — bearing recomputed every physics tick (60 Hz) directly
        // from live transforms so the steered heading keeps up with translation. The
        // host's transform is read from `physShip` (live phys-thread state); the pin's
        // is read from `cachedPinShip` (game-thread Ship, ≤PIN_REFRESH_INTERVAL_TICKS
        // stale on rare attachment changes — its `transform.shipToWorld` itself is
        // always the latest published snapshot). World-placed pins skip the transform.
        val myWorld = Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        physShip.transform.shipToWorld.transformPosition(myWorld)

        val pinWorld = Vector3d(pin.x + 0.5, pin.y + 0.5, pin.z + 0.5)
        cachedPinShip?.transform?.shipToWorld?.transformPosition(pinWorld)

        val dx = pinWorld.x - myWorld.x
        val dy = pinWorld.y - myWorld.y
        val dz = pinWorld.z - myWorld.z
        val horizDistSq = dx * dx + dz * dz

        // Comparator uses the 32-b-capped threshold; PD uses the uncapped ship-length
        // one so a long ship can still anti-spin past 32 b without circling.
        lastPhysInDeadZone = horizDistSq < comparatorDeadZoneSq()
        if (horizDistSq < pdDeadZoneSq()) return         // gate PD off inside the dead-zone

        val targetYaw = Math.atan2(-dx, dz).toFloat()
        if (upgraded) {
            val targetPitch = Math.atan2(-dy, Math.sqrt(horizDistSq)).toFloat()
            physTick3Axis(physShip, targetYaw, targetPitch, powerScale)
        } else {
            physTickYawOnly(physShip, targetYaw, powerScale)
        }
    }

    /** Yaw-only PD controller. World-Y torque only — the ship can pitch and roll on its
     *  own (e.g. ocean wave forcing) but we don't drive those axes. */
    @OptIn(PhysTickOnly::class)
    private fun physTickYawOnly(physShip: PhysShip, target: Float, powerScale: Double) {
        val rotation = physShip.transform.shipToWorldRotation
        val currentYaw = facingMcYaw(rotation, cachedFacing)
        val errMC = shortestArc(target - currentYaw)
        val angVelY = physShip.angularVelocity.y()
        if (Math.abs(errMC) < SETTLE_EPSILON && Math.abs(angVelY) < SETTLE_EPSILON) return

        // Gyroscopic-roll gate. Pure world-Y torque produces clean yaw in calm conditions,
        // but if the ship already has pitch/roll velocity (waves), the Newton-Euler cross
        // term ω × (I·ω) feeds the eyeroscope's ω_y into a roll torque proportional to
        // ω_body_⊥ · ω_y · ΔI. The product compounds when a puffer (off-COM yaw bias) forces
        // the eyeroscope to continuously generate ω_y, exciting parametric roll resonance.
        // Project ω into body frame, take the perpendicular (non-yaw) magnitude, and ramp
        // the PD output off as that magnitude approaches [OMEGA_PERP_CUTOFF]. The
        // eyeroscope still steers during the quiet phases of a wave cycle; it just doesn't
        // pump energy into roll during the violent phases.
        val omegaBody = Vector3d(
            physShip.angularVelocity.x(), physShip.angularVelocity.y(), physShip.angularVelocity.z(),
        )
        rotation.transformInverse(omegaBody)
        val omegaPerp = Math.sqrt(omegaBody.x * omegaBody.x + omegaBody.z * omegaBody.z)
        val gate = (1.0 - omegaPerp / OMEGA_PERP_CUTOFF).coerceIn(0.0, 1.0)

        // PD in MC convention: positive errMC ⇒ want CW-from-above rotation ⇒ negative
        // JOML ω̇_y; damping always opposes current JOML ω_y.
        val accelY = (-K_P * errMC - K_D * angVelY) * powerScale * gate

        // Pure world-Y torque. The natural R·I·R⁻¹·(0,α,0) form would deliver clean yaw
        // acceleration in the inertial frame but leaks off-diagonal X/Z torque whenever
        // the ship is tilted, feeding energy straight into roll/pitch and capable of
        // self-exciting a rocking cycle when the ship is already being pushed. Apply the
        // same scalar magnitude (I_yy_world = bodyY·I_body·bodyY, where bodyY is world +Y
        // in body coords) as a pure (0, τ_y, 0) world torque — keeps inertia-aware yaw
        // scaling while guaranteeing the eyeroscope can never torque any axis but yaw.
        val bodyY = Vector3d()
        rotation.transformInverse(Vector3d(0.0, 1.0, 0.0), bodyY)
        val IbodyY = Vector3d(bodyY)
        physShip.momentOfInertia.transform(IbodyY)
        val IyyWorld = bodyY.dot(IbodyY)
        physShip.applyWorldTorque(Vector3d(0.0, IyyWorld * accelY, 0.0))
    }

    /** Upgraded mode: pitch + yaw tracking via cross-product PD on the angle between the
     *  block's current world-FACING direction and the desired look direction. K_D damps
     *  all three world-frame angular-velocity components, so any spurious roll picked up
     *  in transit decays on its own — no explicit roll controller. */
    @OptIn(PhysTickOnly::class)
    private fun physTick3Axis(physShip: PhysShip, targetYaw: Float, targetPitch: Float, powerScale: Double) {
        if (targetPitch.isNaN()) return

        // Target world direction from MC (yaw, pitch). Matches `Entity.calculateViewVector`.
        val cy = Math.cos(targetYaw.toDouble()); val sy = Math.sin(targetYaw.toDouble())
        val cp = Math.cos(targetPitch.toDouble()); val sp = Math.sin(targetPitch.toDouble())
        val target = Vector3d(-sy * cp, -sp, cy * cp)

        // Current block-local FACING direction rotated into world.
        val rotation = physShip.transform.shipToWorldRotation
        val facingLocal = Vector3d(
            cachedFacing.stepX.toDouble(),
            0.0,
            cachedFacing.stepZ.toDouble(),
        )
        val facingWorld = Vector3d()
        rotation.transform(facingLocal, facingWorld)

        // Cross product gives the world-frame rotation axis that swings facingWorld onto
        // target; its magnitude is sin(angle). atan2 with the dot product gives the signed
        // angle in [0, π]. Together: err = axis × angle (a world-frame "rotation vector").
        val cross = Vector3d(facingWorld).cross(target)
        val crossLen = cross.length()
        val angle = Math.atan2(crossLen, facingWorld.dot(target))
        val angVel = physShip.angularVelocity
        if (Math.abs(angle) < SETTLE_EPSILON &&
            Math.abs(angVel.x()) < SETTLE_EPSILON &&
            Math.abs(angVel.y()) < SETTLE_EPSILON &&
            Math.abs(angVel.z()) < SETTLE_EPSILON
        ) return

        val errX: Double
        val errY: Double
        val errZ: Double
        if (crossLen > 1e-9) {
            val inv = angle / crossLen
            errX = cross.x * inv; errY = cross.y * inv; errZ = cross.z * inv
        } else {
            // Aligned or anti-aligned. Aligned → angle ≈ 0 (early-out above handles that).
            // Anti-aligned (angle ≈ π) → no preferred axis; pick world +Y so the ship spins
            // around vertical to break the tie rather than locking in a degenerate pose.
            errX = 0.0; errY = angle; errZ = 0.0
        }

        // PD in the world frame on all three axes. K_D damps any angular velocity, so a
        // momentary roll induced by a pitch/yaw swing bleeds off naturally.
        val desiredAccel = Vector3d(
            (K_P * errX - K_D * angVel.x()) * powerScale,
            (K_P * errY - K_D * angVel.y()) * powerScale,
            (K_P * errZ - K_D * angVel.z()) * powerScale,
        )

        val torque = Vector3d()
        rotation.transformInverse(desiredAccel, torque)
        physShip.momentOfInertia.transform(torque)
        rotation.transform(torque)
        physShip.applyWorldTorque(torque)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!staticTargetYawRad.isNaN()) tag.putFloat("TargetYaw", staticTargetYawRad)
        if (upgraded) {
            tag.putBoolean("Upgraded", true)
            tag.putFloat("TargetPitch", staticTargetPitchRad)
        }
        if (!compassStack.isEmpty) {
            val cTag = CompoundTag()
            compassStack.save(cTag)
            tag.put("Compass", cTag)
        }
        compassPinPos?.let {
            tag.putInt("PinX", it.x); tag.putInt("PinY", it.y); tag.putInt("PinZ", it.z)
        }
        staffPinWorld?.let {
            tag.putDouble("StaffPinX", it.x); tag.putDouble("StaffPinY", it.y); tag.putDouble("StaffPinZ", it.z)
            if (pinShipId != -1L) {
                tag.putLong("StaffPinShip", pinShipId)
                tag.putDouble("StaffPinSX", pinShipX)
                tag.putDouble("StaffPinSY", pinShipY)
                tag.putDouble("StaffPinSZ", pinShipZ)
            }
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        staticTargetYawRad = if (tag.contains("TargetYaw")) tag.getFloat("TargetYaw") else Float.NaN
        upgraded = tag.getBoolean("Upgraded")
        staticTargetPitchRad = if (upgraded && tag.contains("TargetPitch")) tag.getFloat("TargetPitch") else 0f
        compassStack = if (tag.contains("Compass")) ItemStack.of(tag.getCompound("Compass")) else ItemStack.EMPTY
        compassPinPos = if (tag.contains("PinX")) {
            BlockPos(tag.getInt("PinX"), tag.getInt("PinY"), tag.getInt("PinZ"))
        } else null
        staffPinWorld = if (tag.contains("StaffPinX")) {
            Vec3(tag.getDouble("StaffPinX"), tag.getDouble("StaffPinY"), tag.getDouble("StaffPinZ"))
                .also {
                    if (tag.contains("StaffPinShip")) {
                        pinShipId = tag.getLong("StaffPinShip")
                        pinShipX = tag.getDouble("StaffPinSX")
                        pinShipY = tag.getDouble("StaffPinSY")
                        pinShipZ = tag.getDouble("StaffPinSZ")
                    }
                }
        } else null
        // In static mode, the cache is just the static yaw. In compass mode, leave it NaN
        // until the first serverTick refresh populates a real bearing (no physTick-side
        // torque happens before that, which is exactly what we want during chunk-load).
        cachedTargetYawRad = if (compassPinPos == null) staticTargetYawRad else Float.NaN
        cachedTargetPitchRad = if (compassPinPos == null) staticTargetPitchRad else 0f
        nextPinRefreshTick = 0L
        cachedFacing = blockState.getValueOrElse(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(): CompoundTag {
        val tag = CompoundTag()
        saveAdditional(tag)
        return tag
    }

    internal companion object {
        private const val AIM_SOUND_COOLDOWN_TICKS = 10L
        private const val AIM_SOUND_VOLUME = 0.8f
        private const val AIM_SOUND_PITCH_MIN = 0.95f
        private const val AIM_SOUND_PITCH_SPREAD = 0.1f

        /** Proportional gain (1/s²) — desired α per radian of MC-yaw error. Doubled from
         *  the legacy 1.0 to compensate for the per-physics-tick fresh bearing read: the
         *  old game-tick refresh effectively overshot stale targets each refresh window,
         *  which gave the turn a snappy "kick" we lose under continuous-error tracking.
         *  Steady-state JOML yaw rate is `K_P·err / K_D ≈ 1·err` rad/s, so a 90° error
         *  settles toward ~90°/s and a 180° tune completes in ~1.5 s. */
        const val K_P: Double = 2.0

        /** Derivative gain (1/s) — damping coefficient on world-Y angular velocity. Critical
         *  at `2·√K_P ≈ 2.83`; 2.0 is ~70 % of critical, slightly *under*-damped so the
         *  turn ends with a small visible coast-in instead of a dead-stick stop. Scaled up
         *  with [K_P] to keep the same damping ratio the legacy 1.0/1.5 pair had. */
        const val K_D: Double = 2.0

        /** Below this error AND this angular rate the controller emits no torque — keeps the
         *  ship from twitching on tiny floating-point residuals once it's settled. */
        const val SETTLE_EPSILON: Double = 0.001

        /** Body-frame perpendicular angular rate (rad/s) at which the yaw controller is fully
         *  gated off. Calm water sits at ~0.05–0.1 rad/s here, heavy chop hits ~0.4+. Below
         *  the cutoff the gate ramps linearly to full strength; above it the eyeroscope
         *  emits no torque, breaking the ω_x·ω_y product that drives parametric roll
         *  resonance under sustained puffer push. */
        const val OMEGA_PERP_CUTOFF: Double = 0.5

        /** Game-tick cadence for re-resolving the pin's owning ship + the host's dead-zone
         *  radius. These are level-bound lookups that can't run from the physics thread;
         *  the bearing itself is recomputed every physics tick from the cached references. */
        const val PIN_REFRESH_INTERVAL_TICKS: Long = 10L

        /** Pin dead-zone radius as a multiple of the ship's longest horizontal AABB extent.
         *  1.2× gives ships room to settle without the bearing wrapping endlessly — a 20 m
         *  ship treats anywhere within 24 m of the pin as arrived. */
        const val SHIP_LENGTH_DEADZONE_FACTOR: Double = 1.2

        /** Fallback dead-zone (blocks²) used only when the eyeroscope isn't on a ship. There's
         *  no ship to spin in this case; the value just keeps the math symmetric. */
        const val WORLD_FALLBACK_DEADZONE_SQ: Double = 1.0

        /** Hard cap (blocks) on the *PD's* "stop steering, we've arrived" radius. A long
         *  ship's anti-spin dead-zone would otherwise scale unboundedly with its length;
         *  16 b keeps even a 100-b ship steering tightly to its target instead of giving
         *  up early. */
        const val MAX_PD_DEADZONE_RADIUS: Double = 16.0

        /** Hard cap (blocks) on the *comparator's* "arrived at target" radius. Larger
         *  than [MAX_PD_DEADZONE_RADIUS] on purpose — the redstone output is meant to
         *  trigger before the PD fully relaxes, so an automation circuit gets advance
         *  notice that the target is close. */
        const val MAX_COMPARATOR_DEADZONE_RADIUS: Double = 32.0

        /** Standard vanilla redstone signal cap. Centralised so the power-scaling math
         *  and the "we've arrived" comparator output reference the same value. */
        const val MAX_REDSTONE: Int = 15

        /** Per-host-ship registry of active eyeroscopes. Keyed by host ship id; value is
         *  pos→BE so [updatePriorityRegistry] can iterate siblings and decide which one
         *  is closest to its target. Shared across all loaded eyeroscopes. */
        /** A ship id of an old world can be the id of a different ship in the next world. */
        fun clearSession() = eyeroscopesByShip.clear()

        val eyeroscopesByShip: ConcurrentHashMap<Long, MutableMap<BlockPos, EyeroscopeBlockEntity>> =
            ConcurrentHashMap()

        /** Eyeroscope's *current heading* in MC yaw radians. Defined as the world-frame
         *  bearing of the block's [Direction] FACING vector after `shipToWorldRotation`.
         *  Captures both the player's installed orientation (the block's FACING) and the
         *  ship's live world rotation, giving a single number for "where the eyeroscope is
         *  pointing in the world right now." */
        fun facingMcYaw(rotation: Quaterniondc, facing: Direction): Float {
            val v = Vector3d(facing.stepX.toDouble(), 0.0, facing.stepZ.toDouble())
            rotation.transform(v)
            return Math.atan2(-v.x, v.z).toFloat()
        }

        /** Wrap an angle (radians) into `[-π, π]` so PD operates on the shortest arc. */
        fun shortestArc(rad: Float): Float {
            val twoPi = (2.0 * Math.PI).toFloat()
            var r = rad
            while (r > Math.PI.toFloat()) r -= twoPi
            while (r < -Math.PI.toFloat()) r += twoPi
            return r
        }
    }
}

/** Read a property if it's on this state, else fall back. End-portal-frame's parent model is
 *  shared so this is mostly a guard against a future blockstate edit removing FACING. */
private fun <T : Comparable<T>> BlockState.getValueOrElse(
    prop: net.minecraft.world.level.block.state.properties.Property<T>, fallback: T,
): T = if (this.hasProperty(prop)) this.getValue(prop) else fallback
