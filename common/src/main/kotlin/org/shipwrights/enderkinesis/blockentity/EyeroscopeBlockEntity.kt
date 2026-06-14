package org.shipwrights.enderkinesis.blockentity

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.shipwrights.enderkinesis.item.LedgerOfHuntingPincersItem
import org.shipwrights.enderkinesis.item.LedgerOfWatchingEyesItem
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

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
@OptIn(PhysTickOnly::class, VsBeta::class)
class EyeroscopeBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.EYEROSCOPE.get(), pos, state),
    BlockEntityPhysicsListener {

    @Volatile override lateinit var dimension: DimensionId

    /** Static target yaw (MC convention), NaN when no target. Persisted; ignored while a compass is in the slot. */
    private var staticTargetYawRad: Float = Float.NaN

    var compassStack: ItemStack = ItemStack.EMPTY
        private set

    @Volatile private var compassPinPos: BlockPos? = null

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

    /** IDs of ships currently jointed (directly) to the host ship. Populated each
     *  [physTick] from [org.valkyrienskies.core.internal.world.VsiPhysLevel]'s joint
     *  registry; consumed by [tickLedger] on the game thread to skip ships that are
     *  attached to our host, matching the spec ("not on the current ship and not
     *  attached to the current ship"). Empty for world-placed eyeroscopes. */
    @Volatile private var cachedJointedShipIds: LongArray = EMPTY_JOINT_IDS

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

    /** Ship id this eyeroscope is currently aiming at via the Ledger of Watching Eyes.
     *  `-1` means "no ledger target" — either the slot doesn't hold a ledger, the
     *  sighting list is empty, or every recorded ship has unloaded. Refreshed in
     *  [updateLedgerTarget] each game tick from the latest-loaded entry in the ledger's
     *  `tracked_ships` list. Physics tick reads this directly. */
    @Volatile private var cachedLedgerTargetShipId: Long = -1L

    /** Entity UUID this eyeroscope is currently aiming at via the Ledger of Hunting
     *  Pincers. `null` means no live target. Refreshed each game tick from the latest
     *  loaded entry in the hunting ledger. */
    @Volatile private var cachedHuntingTargetUuid: java.util.UUID? = null

    /** Latest known world position of [cachedHuntingTargetUuid], snapshotted each game
     *  tick. The physics tick reads this directly — `LivingEntity` lookup is
     *  game-thread-only, so we can't resolve the entity from `physTick`. 50 ms latency
     *  versus the entity's true position; indistinguishable from the player POV. */
    @Volatile private var cachedHuntingTargetWorldPos: Vector3d? = null

    /** UUID of the player who last inserted a Ledger of Hunting Pincers. Skipped during
     *  scanning so the holder doesn't track themselves. Persisted with the BE so a
     *  reload doesn't suddenly start hunting the placer. Null if no hunting ledger has
     *  ever been placed (or if the holder right-clicked it in without being a real
     *  player — e.g. a dispenser; in that case nobody is exempt). */
    private var huntingLedgerPlacer: java.util.UUID? = null

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
        staticTargetYawRad = yawRad
        staticTargetPitchRad = if (upgraded) pitchRad else 0f
        cachedTargetYawRad = yawRad
        cachedTargetPitchRad = staticTargetPitchRad
        syncAfterChange()
    }

    /** Echo-shard right-click. No-op if already upgraded so a second shard isn't silently
     *  consumed. Persists immediately. */
    fun applyUpgrade(): Boolean {
        if (upgraded) return false
        upgraded = true
        syncAfterChange()
        return true
    }

    /** Right-click with a compass: capture the compass into the slot and pin its tracked
     *  position. The static yaw is left alone so removing the compass can fall back to it.
     *  The bearing cache is force-refreshed *now* so the ship starts steering immediately
     *  instead of waiting up to PIN_REFRESH_INTERVAL_TICKS for the next scheduled refresh. */
    fun insertCompass(stack: ItemStack, pinned: BlockPos) {
        compassStack = stack
        compassPinPos = pinned
        nextPinRefreshTick = 0L                            // refresh on the very next serverTick
        cachedPinShip = null                               // force resolution on next resolver pass
        // Don't touch cachedTargetYawRad here — pin-mode physTick computes its own target from
        // the cached ship + raw pin pos and ignores `cachedTargetYawRad` entirely.
        syncAfterChange()
    }

    /** Right-click with a [LedgerOfWatchingEyesItem]: takes the same slot the compass
     *  uses but switches the eyeroscope into ship-tracking mode (no pin, no steering —
     *  just logs visiting ships into the stack's NBT). The pin / map / compass paths all
     *  key off `compassStack`'s item identity, so leaving the field empty for the
     *  pin-mode state is all this needs to do. */
    fun insertLedger(stack: ItemStack) {
        compassStack = stack
        compassPinPos = null
        cachedPinShip = null
        nextPinRefreshTick = 0L
        lastPhysInDeadZone = false
        syncAfterChange()
    }

    /** Right-click with a [LedgerOfHuntingPincersItem]: same slot mechanics as
     *  [insertLedger], but also captures the placing player's UUID so that player is
     *  exempt from being tracked. `placer` may be null when the insertion came from
     *  something that isn't a real player (a dispenser, a worldgen processor) — in that
     *  case nobody is exempt. */
    fun insertHuntingLedger(stack: ItemStack, placer: java.util.UUID?) {
        compassStack = stack
        compassPinPos = null
        cachedPinShip = null
        nextPinRefreshTick = 0L
        lastPhysInDeadZone = false
        huntingLedgerPlacer = placer
        // Scrub any pre-existing sighting of the placer from the tracked list — the
        // ledger may have been used in another eyeroscope first, where the new placer
        // *was* tracked. Without this, [tickHuntingLedger]'s "skip placer" guard only
        // prevents *re-adding*; it can't unsee a stale entry that's already there.
        if (placer != null) scrubPlacerFromLedger(stack, placer)
        syncAfterChange()
    }

    /** Client-side accessor — the renderer's `currentHuntingDelta` also needs to skip
     *  the placer, and the field is private. Returns null if no hunting ledger has been
     *  placed, or if the insertion came from a non-player source. */
    fun getHuntingLedgerPlacer(): java.util.UUID? = huntingLedgerPlacer

    private fun scrubPlacerFromLedger(stack: ItemStack, placer: java.util.UUID) {
        val tag = stack.tag ?: return
        if (!tag.contains(LedgerOfHuntingPincersItem.TRACKED_ENTITIES_TAG)) return
        val list = tag.getList(LedgerOfHuntingPincersItem.TRACKED_ENTITIES_TAG, Tag.TAG_COMPOUND.toInt())
        var i = list.size - 1
        var changed = false
        while (i >= 0) {
            if (list.getCompound(i).getUUID("id") == placer) {
                list.removeAt(i)
                changed = true
            }
            i--
        }
        if (changed) tag.put(LedgerOfHuntingPincersItem.TRACKED_ENTITIES_TAG, list)
    }

    /** Right-click empty-handed while a compass is in the slot: pull the compass out, freeze
     *  the most-recently sampled bearing as the new static target. The returned stack is what
     *  gets handed back to the player. */
    fun ejectCompass(): ItemStack {
        val out = compassStack
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
        return out
    }

    private fun syncAfterChange() {
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    override fun setRemoved() {
        super.setRemoved()
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
        // Update the ledger's append-only sighting list and pick the live target ship.
        // Must run *before* the comparator sync below so the "no target → drop signal"
        // and "have target → mirror lastPhysInDeadZone" decisions see this tick's state,
        // not last tick's stale `cachedLedgerTargetShipId`.
        tickLedger(level, pos)
        updateLedgerTarget(level)
        tickHuntingLedger(level, pos)
        updateHuntingTarget(level)

        val newSignal = computeComparatorSignal(level, pos)
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

    /** Ledger of Watching Eyes scan: every [LEDGER_SCAN_INTERVAL_TICKS] game ticks while
     *  the ledger sits in our slot, look at every ship overlapping a [LEDGER_HALF_EXTENT]-
     *  radius cube centred on our world position, drop the host and any ship jointed to it
     *  (cache from [physTick]), and append any newly-seen ship to the stack's
     *  [LedgerOfWatchingEyesItem.TRACKED_SHIPS_TAG] list. The book view itself is generated
     *  on demand by [LedgerOfWatchingEyesItem.writtenBook] from that same list, so all the
     *  eyeroscope ever has to do is keep the list up to date. */
    private fun tickLedger(level: ServerLevel, pos: BlockPos) {
        val stack = compassStack
        if (!stack.`is`(EKItems.LEDGER_OF_WATCHING_EYES.get())) return
        if (level.gameTime % LEDGER_SCAN_INTERVAL_TICKS != 0L) return

        val hostShip = level.getLoadedShipManagingPos(pos)
        val hostId = hostShip?.id ?: Long.MIN_VALUE
        val eyeWorld = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        hostShip?.transform?.shipToWorld?.transformPosition(eyeWorld)

        val r = LEDGER_HALF_EXTENT.toDouble()
        val aabb = AABBd(
            eyeWorld.x - r, eyeWorld.y - r, eyeWorld.z - r,
            eyeWorld.x + r, eyeWorld.y + r, eyeWorld.z + r,
        )
        val jointed = cachedJointedShipIds
        val tag = stack.orCreateTag
        val trackedList = tag.getList(LedgerOfWatchingEyesItem.TRACKED_SHIPS_TAG, Tag.TAG_COMPOUND.toInt())
        // Build a quick membership probe for the already-tracked ids. Sightings are
        // append-only so a linear scan over typical ledger sizes is fine.
        val seenIds = HashSet<Long>(trackedList.size)
        for (i in 0 until trackedList.size) seenIds.add(trackedList.getCompound(i).getLong("id"))

        var changed = false
        for (s in level.shipObjectWorld.allShips.getIntersecting(aabb)) {
            if (s.id == hostId) continue
            if (jointed.any { it == s.id }) continue
            if (!seenIds.add(s.id)) continue
            val entry = CompoundTag()
            entry.putLong("id", s.id)
            entry.putString("slug", s.slug ?: "")
            val sp = s.transform.positionInWorld
            entry.putDouble("x", sp.x())
            entry.putDouble("y", sp.y())
            entry.putDouble("z", sp.z())
            entry.putLong("tick", level.gameTime)
            trackedList.add(entry)
            changed = true
        }

        if (changed) {
            tag.put(LedgerOfWatchingEyesItem.TRACKED_SHIPS_TAG, trackedList)
            setChanged()
            level.sendBlockUpdated(pos, blockState, blockState, 3)
        }
    }

    /** Ledger of Hunting Pincers scan — same shape as [tickLedger] but for living
     *  entities. Uses `level.getEntitiesOfClass(LivingEntity, AABB)` over the same
     *  [LEDGER_HALF_EXTENT] cube as the ship ledger. Skips the placer player's UUID so
     *  the holder isn't tracked as their own target. */
    private fun tickHuntingLedger(level: ServerLevel, pos: BlockPos) {
        val stack = compassStack
        if (!stack.`is`(EKItems.LEDGER_OF_HUNTING_PINCERS.get())) return
        if (level.gameTime % LEDGER_SCAN_INTERVAL_TICKS != 0L) return

        val hostShip = level.getLoadedShipManagingPos(pos)
        val eyeWorld = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        hostShip?.transform?.shipToWorld?.transformPosition(eyeWorld)

        val r = LEDGER_HALF_EXTENT.toDouble()
        val box = net.minecraft.world.phys.AABB(
            eyeWorld.x - r, eyeWorld.y - r, eyeWorld.z - r,
            eyeWorld.x + r, eyeWorld.y + r, eyeWorld.z + r,
        )
        val entities = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity::class.java, box)
        if (entities.isEmpty()) return

        val placer = huntingLedgerPlacer
        val tag = stack.orCreateTag
        val trackedList = tag.getList(LedgerOfHuntingPincersItem.TRACKED_ENTITIES_TAG, Tag.TAG_COMPOUND.toInt())
        val seenIds = HashSet<java.util.UUID>(trackedList.size)
        for (i in 0 until trackedList.size) seenIds.add(trackedList.getCompound(i).getUUID("id"))

        var changed = false
        for (e in entities) {
            if (placer != null && e.uuid == placer) continue            // placer is exempt
            if (!seenIds.add(e.uuid)) continue
            val entry = CompoundTag()
            entry.putUUID("id", e.uuid)
            entry.putString("type", e.type.toShortString())
            entry.putString("name", e.name.string)
            entry.putDouble("x", e.x)
            entry.putDouble("y", e.y)
            entry.putDouble("z", e.z)
            entry.putLong("tick", level.gameTime)
            trackedList.add(entry)
            changed = true
        }

        if (changed) {
            tag.put(LedgerOfHuntingPincersItem.TRACKED_ENTITIES_TAG, trackedList)
            setChanged()
            level.sendBlockUpdated(pos, blockState, blockState, 3)
        }
    }

    /** Pick the eyeroscope's current hunting steering target: the loaded tracked entity
     *  *closest* to this eyeroscope. Null if the slot isn't a hunting ledger, the list
     *  is empty, or every recorded entity is unloaded. */
    private fun updateHuntingTarget(level: ServerLevel) {
        val stack = compassStack
        if (!stack.`is`(EKItems.LEDGER_OF_HUNTING_PINCERS.get())) {
            cachedHuntingTargetUuid = null
            cachedHuntingTargetWorldPos = null
            return
        }
        val tag = stack.tag ?: run {
            cachedHuntingTargetUuid = null
            cachedHuntingTargetWorldPos = null
            return
        }
        val list = tag.getList(LedgerOfHuntingPincersItem.TRACKED_ENTITIES_TAG, Tag.TAG_COMPOUND.toInt())

        val eyeWorld = Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        findShipForShipyardPos(level, blockPos)?.transform?.shipToWorld?.transformPosition(eyeWorld)

        val placer = huntingLedgerPlacer
        var bestUuid: java.util.UUID? = null
        var bestPos: Vector3d? = null
        var bestDistSq = Double.MAX_VALUE
        for (i in 0 until list.size) {
            val uuid = list.getCompound(i).getUUID("id")
            if (uuid == placer) continue                       // defensive: never aim at the placer
            val entity = level.getEntity(uuid) ?: continue
            val dx = entity.x - eyeWorld.x
            val dy = entity.y - eyeWorld.y
            val dz = entity.z - eyeWorld.z
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestUuid = uuid
                bestPos = Vector3d(entity.x, entity.y, entity.z)
            }
        }
        cachedHuntingTargetUuid = bestUuid
        cachedHuntingTargetWorldPos = bestPos
        if (bestUuid != null) cachedDeadZoneSq = computeDeadZoneSq(findShipForShipyardPos(level, blockPos))
    }

    /** Pick the eyeroscope's current ledger steering target: the loaded tracked ship
     *  whose `transform.positionInWorld` is *closest* to this eyeroscope. Sets `-1` if
     *  the slot isn't a ledger, the list is empty, or every recorded ship is unloaded.
     *
     *  Also refreshes [cachedDeadZoneSq] from the host ship's AABB on the same cadence as
     *  the compass-pin path does in [refreshPinShipResolution] — the physics tick's
     *  "we've arrived" gate (and the comparator that mirrors it) needs the live host-
     *  size dead-zone, not the stale value left over from a prior compass-pin session. */
    private fun updateLedgerTarget(level: ServerLevel) {
        val stack = compassStack
        if (!stack.`is`(EKItems.LEDGER_OF_WATCHING_EYES.get())) {
            cachedLedgerTargetShipId = -1L
            return
        }
        val tag = stack.tag ?: run { cachedLedgerTargetShipId = -1L; return }
        val list = tag.getList(LedgerOfWatchingEyesItem.TRACKED_SHIPS_TAG, Tag.TAG_COMPOUND.toInt())

        val eyeWorld = Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        findShipForShipyardPos(level, blockPos)?.transform?.shipToWorld?.transformPosition(eyeWorld)

        var bestId = -1L
        var bestDistSq = Double.MAX_VALUE
        for (i in 0 until list.size) {
            val shipId = list.getCompound(i).getLong("id")
            val ship = level.shipObjectWorld.allShips.getById(shipId) ?: continue
            val p = ship.transform.positionInWorld
            val dx = p.x() - eyeWorld.x
            val dy = p.y() - eyeWorld.y
            val dz = p.z() - eyeWorld.z
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestId = shipId
            }
        }
        cachedLedgerTargetShipId = bestId
        if (bestId != -1L) cachedDeadZoneSq = computeDeadZoneSq(findShipForShipyardPos(level, blockPos))
    }

    /** Walk the host ship's joint set on the physics thread, collecting the OTHER ship
     *  id of every direct connection. Used by [tickLedger] (game thread) via the
     *  [cachedJointedShipIds] field. Empty array on the worldless / no-joints case. */
    private fun collectJointedShipIds(physShip: PhysShip, physLevel: PhysLevel): LongArray {
        val physLvl = physLevel as? org.valkyrienskies.core.internal.world.VsiPhysLevel
            ?: return EMPTY_JOINT_IDS
        val hostId = physShip.id
        val jointIds = try { physLvl.getJointsFromShip(hostId) } catch (_: Throwable) { return EMPTY_JOINT_IDS }
        if (jointIds.isEmpty()) return EMPTY_JOINT_IDS
        val out = LongArray(jointIds.size)
        var idx = 0
        for (jointId in jointIds) {
            val joint = physLvl.getJointById(jointId) ?: continue
            val otherId = if (joint.shipId0 == hostId) joint.shipId1 else joint.shipId0
            if (otherId != null && otherId != hostId) out[idx++] = otherId
        }
        return if (idx == out.size) out else out.copyOf(idx)
    }

    /** Re-register self in [eyeroscopesByShip] (or remove if we just became worldless),
     *  recompute our distance² to the current target, and decide whether we're the
     *  primary eyeroscope for our host ship. World-placed eyeroscopes are always primary
     *  (no contention possible). */
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
     *  2. **Ledger target** — target is the latest-loaded tracked ship's
     *     `transform.positionInWorld`.
     *
     *  Returns [Double.MAX_VALUE] when neither source has a target — those eyeroscopes
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

        val ledgerShipId = cachedLedgerTargetShipId
        if (ledgerShipId != -1L) {
            val ship = level.shipObjectWorld.allShips.getById(ledgerShipId)
            if (ship != null) {
                val tp = ship.transform.positionInWorld
                val dx = eyeWorld.x - tp.x()
                val dy = eyeWorld.y - tp.y()
                val dz = eyeWorld.z - tp.z()
                return dx * dx + dy * dy + dz * dz
            }
        }

        val huntingPos = cachedHuntingTargetWorldPos
        if (huntingPos != null) {
            val dx = eyeWorld.x - huntingPos.x
            val dy = eyeWorld.y - huntingPos.y
            val dz = eyeWorld.z - huntingPos.z
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

    /** Compute this tick's comparator output. Two shapes depending on the steering mode:
     *
     *   - **Compass-pin** (or no target) — binary 0/15 mirrored from [lastPhysInDeadZone].
     *     Full strength means "we've arrived at the pin," matching the original mechanic.
     *   - **Ledger of Watching Eyes** — *distance-proportional*, scaling 0..15 with the
     *     eye-to-target world distance from 0 to [LEDGER_COMPARATOR_MAX_DIST]. The
     *     redstone signal is *strongest* when the target is far, weakest when it's close
     *     — automation can read distance directly off the comparator.
     */
    private fun computeComparatorSignal(level: ServerLevel, pos: BlockPos): Int {
        if (compassPinPos != null) {
            return if (lastPhysInDeadZone) MAX_REDSTONE else 0
        }

        // Both ledger modes use the same distance-to-target → signal-strength mapping
        // (farther = stronger). Resolve the target's world position, then ramp.
        val eyeWorld = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        findShipForShipyardPos(level, pos)?.transform?.shipToWorld?.transformPosition(eyeWorld)

        val targetWorld: Vector3d = when {
            cachedLedgerTargetShipId != -1L -> {
                val ship = level.shipObjectWorld.allShips.getById(cachedLedgerTargetShipId) ?: return 0
                val p = ship.transform.positionInWorld
                Vector3d(p.x(), p.y(), p.z())
            }
            cachedHuntingTargetUuid != null -> cachedHuntingTargetWorldPos ?: return 0
            else -> return 0
        }
        val dx = targetWorld.x - eyeWorld.x
        val dy = targetWorld.y - eyeWorld.y
        val dz = targetWorld.z - eyeWorld.z
        val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
        val frac = (dist / LEDGER_COMPARATOR_MAX_DIST).coerceIn(0.0, 1.0)
        return (frac * MAX_REDSTONE).toInt()
    }

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

    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        // Refresh jointed-ship cache for the ledger's connectivity filter regardless of
        // whether we'll steer this tick — the cache is read on the game thread and only
        // physTick has access to VS2's joint registry.
        cachedJointedShipIds =
            if (physShip != null) collectJointedShipIds(physShip, physLevel) else EMPTY_JOINT_IDS

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
            // Ledger-of-Watching-Eyes mode — aim at the most recently-sighted *loaded*
            // ship's live COM. We resolve the target ship on the physics thread so the
            // bearing keeps up with both the host's and the target's translation at
            // 60 Hz; the game-thread `cachedLedgerTargetShipId` is only the *which*.
            val ledgerShipId = cachedLedgerTargetShipId
            if (ledgerShipId != -1L) {
                val target = physLevel.getShipById(ledgerShipId)
                if (target != null) {
                    applyLedgerSteering(physShip, target, powerScale)
                    return
                }
            }

            // Ledger-of-Hunting-Pincers mode — aim at the live world position of a
            // tracked entity. The entity has to be looked up via the level (not the
            // phys-level) so the bearing here is one tick behind the entity's true
            // position; that's still 50 ms latency, indistinguishable in practice.
            val huntingUuid = cachedHuntingTargetUuid
            if (huntingUuid != null) {
                val targetPos = cachedHuntingTargetWorldPos
                if (targetPos != null) {
                    applyHuntingSteering(physShip, targetPos, powerScale)
                    return
                }
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

    /** Hunting-target steering. Identical math shape to the compass-pin / ledger paths,
     *  but the target position is the game-thread-cached entity world position. The
     *  cache is refreshed once per game tick (20 Hz); inside that window the physics
     *  tick re-evaluates the bearing each step against the stale cache. 50 ms of
     *  position lag is invisible at gameplay speeds. */
    private fun applyHuntingSteering(physShip: PhysShip, targetPos: Vector3d, powerScale: Double) {
        val myWorld = Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        physShip.transform.shipToWorld.transformPosition(myWorld)

        val dx = targetPos.x - myWorld.x
        val dy = targetPos.y - myWorld.y
        val dz = targetPos.z - myWorld.z
        val horizDistSq = dx * dx + dz * dz

        // No PD dead-zone for hunting mode — the entity target can squat right on top of
        // the eyeroscope (a player walking onto the host ship) and we still want the eye
        // and the ship's yaw to track them. The comparator is distance-proportional
        // (computed game-side from the ledger's `LEDGER_COMPARATOR_MAX_DIST` ramp), so
        // `lastPhysInDeadZone` isn't consumed in this mode either.

        val targetYaw = Math.atan2(-dx, dz).toFloat()
        if (upgraded) {
            val targetPitch = Math.atan2(-dy, Math.sqrt(horizDistSq)).toFloat()
            physTick3Axis(physShip, targetYaw, targetPitch, powerScale)
        } else {
            physTickYawOnly(physShip, targetYaw, powerScale)
        }
    }

    /** Ledger-target steering. Identical math shape to the compass-pin branch but the
     *  target's world position comes from the live phys-thread `target.kinematics.position`
     *  instead of a static [BlockPos] — so the eyeroscope swings with the tracked ship's
     *  motion at full physics-tick resolution. */
    private fun applyLedgerSteering(physShip: PhysShip, target: PhysShip, powerScale: Double) {
        val myWorld = Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5)
        physShip.transform.shipToWorld.transformPosition(myWorld)

        val targetPos = target.kinematics.position
        val dx = targetPos.x() - myWorld.x
        val dy = targetPos.y() - myWorld.y
        val dz = targetPos.z() - myWorld.z
        val horizDistSq = dx * dx + dz * dz

        // Comparator uses the 32-b-capped threshold; PD uses the uncapped ship-length one.
        lastPhysInDeadZone = horizDistSq < comparatorDeadZoneSq()
        if (horizDistSq < pdDeadZoneSq()) return

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
        huntingLedgerPlacer?.let { tag.putUUID("HuntingPlacer", it) }
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
        huntingLedgerPlacer = if (tag.hasUUID("HuntingPlacer")) tag.getUUID("HuntingPlacer") else null
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

    private companion object {
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
         *  notice that the target is close. Only consulted by the compass-pin branch of
         *  [computeComparatorSignal]; the ledger branch uses [LEDGER_COMPARATOR_MAX_DIST]
         *  to map distance directly onto signal strength instead. */
        const val MAX_COMPARATOR_DEADZONE_RADIUS: Double = 32.0

        /** Distance (blocks) at which the ledger comparator saturates at signal 15. The
         *  output ramps linearly from 0 at the eyeroscope to 15 at this distance, so an
         *  automation circuit reads "how far the tracked ship has gotten" — closer ⇒
         *  weaker, farther ⇒ stronger. 128 b spans the ledger's natural tracking range
         *  with headroom for ships that drift well past the original sighting cube. */
        const val LEDGER_COMPARATOR_MAX_DIST: Double = 128.0


        /** Standard vanilla redstone signal cap. Centralised so the power-scaling math
         *  and the "we've arrived" comparator output reference the same value. */
        const val MAX_REDSTONE: Int = 15

        /** Ledger of Watching Eyes scan: half-extent (blocks) of the 64-cube around the
         *  eyeroscope's world position used for the ship-overlap query. */
        const val LEDGER_HALF_EXTENT: Int = 32

        /** Game ticks between ledger scans. 10 t = twice per second — fast enough that a
         *  ship passing through at modest speed lands in the log, slow enough that the
         *  AABB query + NBT rewrite stay negligible. */
        const val LEDGER_SCAN_INTERVAL_TICKS: Long = 10L

        /** Empty backing for [cachedJointedShipIds] — shared so every fresh BE starts
         *  with the same zero-allocation array. */
        val EMPTY_JOINT_IDS: LongArray = LongArray(0)

        /** Per-host-ship registry of active eyeroscopes. Keyed by host ship id; value is
         *  pos→BE so [updatePriorityRegistry] can iterate siblings and decide which one
         *  is closest to its target. Shared across all loaded eyeroscopes. */
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
