package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

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

    private var compassPinPos: BlockPos? = null

    private var nextPinRefreshTick: Long = 0L

    /** Bridges the game-tick refresh (needs Level) to the physics-tick consumer (can't touch Level). */
    @Volatile private var cachedTargetYawRad: Float = Float.NaN

    @Volatile private var cachedFacing: Direction =
        state.getValueOrElse(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)

    /** PD output scales by `(15 − cachedPower) / 15`. Power 15 disables steering entirely. */
    @Volatile private var cachedPower: Int = 0

    /** Comparator-output signal. 15 when a pin is set and the eyeroscope is inside the same
     *  ship-length-scaled dead-zone the PD uses as "we've arrived", 0 otherwise. Recomputed
     *  by [refreshPinBearing] (and cleared in [ejectCompass]); transitions ping comparator
     *  neighbours via [Level.updateNeighbourForOutputSignal] so the redstone graph picks
     *  up the new value at the same tick boundary the BE observed it. */
    var comparatorSignal: Int = 0
        private set

    /** Right-click empty-handed: stamp the player's current world look as the static target.
     *  Does NOT clear an existing compass pin — the use-handler checks slot state first and
     *  routes empty-handed clicks to either eject (slot full) or this (slot empty). */
    fun setStaticTargetYaw(yawRad: Float) {
        staticTargetYawRad = yawRad
        cachedTargetYawRad = yawRad
        syncAfterChange()
    }

    /** Right-click with a compass: capture the compass into the slot and pin its tracked
     *  position. The static yaw is left alone so removing the compass can fall back to it.
     *  The bearing cache is force-refreshed *now* so the ship starts steering immediately
     *  instead of waiting up to PIN_REFRESH_INTERVAL_TICKS for the next scheduled refresh. */
    fun insertCompass(stack: ItemStack, pinned: BlockPos) {
        compassStack = stack
        compassPinPos = pinned
        nextPinRefreshTick = 0L                            // refresh on the very next serverTick
        // Don't touch cachedTargetYawRad here — the game thread isn't guaranteed to know the
        // current shipToWorld yet (e.g. during NBT load before the first physTick has run).
        // serverTick will populate it on its next pass.
        syncAfterChange()
    }

    /** Right-click empty-handed while a compass is in the slot: pull the compass out, freeze
     *  the most-recently sampled bearing as the new static target. The returned stack is what
     *  gets handed back to the player. */
    fun ejectCompass(): ItemStack {
        val out = compassStack
        val frozen = cachedTargetYawRad
        if (!frozen.isNaN()) staticTargetYawRad = frozen
        cachedTargetYawRad = staticTargetYawRad
        compassStack = ItemStack.EMPTY
        compassPinPos = null
        syncAfterChange()
        return out
    }

    private fun syncAfterChange() {
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, 3)
    }

    /** Renderer reads this. NaN if nothing's set; finite radians (MC yaw) otherwise. */
    fun getStaticTargetYaw(): Float = staticTargetYawRad

    /** Renderer side: pinned position if any, else null. */
    fun getCompassPin(): BlockPos? = compassPinPos

    fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        // Refresh facing each tick — cheap. Survives the block being rotated by some other
        // mod's tooling without needing a custom hook.
        cachedFacing = state.getValueOrElse(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
        // Poll redstone input — 6 neighbour lookups; PD scales itself by (15 − power)/15.
        cachedPower = level.getBestNeighborSignal(pos)

        // Pin removed (or never set) — make sure comparator output drops to 0. The
        // refreshPinBearing path handles the same transition when a pin *is* set; this is
        // the other direction (pin → no pin via ejectCompass / NBT load with no pin).
        if (compassPinPos == null && comparatorSignal != 0) {
            comparatorSignal = 0
            level.updateNeighbourForOutputSignal(pos, blockState.block)
        }

        // Compass-pinned mode: refresh the cached bearing on a slow cadence. The pin is
        // static and the ship's worldPos drifts only a few blocks per second, so 0.5 s is
        // more than fine — and matters: the physics thread runs at 60 Hz and we don't want
        // to be doing a getLoadedShipManagingPos lookup + atan2 from there.
        if (compassPinPos != null && level.gameTime >= nextPinRefreshTick) {
            refreshPinBearing(level, pos)
            nextPinRefreshTick = level.gameTime + PIN_REFRESH_INTERVAL_TICKS
        }
    }

    /** Game-tick recomputation of the pin bearing. Reads the *ship-managing* transform via
     *  the level (the level handles the world-vs-ship distinction internally), takes the
     *  XZ delta to the pin, and writes the MC-yaw atan2 into the volatile mirror.
     *
     *  Also drives the comparator output: 15 inside the ship-length dead-zone (we've
     *  arrived), 0 outside. Transitions ping comparator neighbours so the redstone graph
     *  sees the change at the same tick the BE sees it. */
    private fun refreshPinBearing(level: ServerLevel, pos: BlockPos) {
        val pin = compassPinPos ?: return
        val ship = level.getLoadedShipManagingPos(pos)
        // Eyeroscope's world XZ centre. blockPos is shipyard coords when on a ship, so route
        // it through shipToWorld first; for world-placed eyeroscopes the ship lookup returns
        // null and we use the shipyard pos directly (which equals world).
        val w = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        if (ship != null) ship.transform.shipToWorld.transformPosition(w)
        val dx = (pin.x + 0.5) - w.x
        val dz = (pin.z + 0.5) - w.z
        val inDeadZone = (dx * dx + dz * dz) < computeDeadZoneSq(ship)

        val newSignal = if (inDeadZone) MAX_REDSTONE else 0
        if (newSignal != comparatorSignal) {
            comparatorSignal = newSignal
            level.updateNeighbourForOutputSignal(pos, blockState.block)
        }

        if (inDeadZone) {
            // Inside the ship-scaled dead-zone: gate the PD off entirely. Setting the target
            // to NaN makes physTick return early — no torque, no spinning trying to chase a
            // bearing that's bouncing between every quadrant of a 1-block radius noise band.
            cachedTargetYawRad = Float.NaN
            return
        }
        cachedTargetYawRad = Math.atan2(-dx, dz).toFloat()
    }

    /** Pin dead-zone radius² in blocks². 1.2× the ship's *longest horizontal extent* (max of
     *  X- and Z-side lengths of its local AABB) lets a 30-block-long ship treat anywhere
     *  within ~36 blocks of the pin as "arrived" rather than circling in place. Falls back
     *  to a fixed 1.0 m² for world-placed eyeroscopes (no ship → no spin problem to gate). */
    private fun computeDeadZoneSq(ship: Ship?): Double {
        val sb = ship?.shipAABB ?: return WORLD_FALLBACK_DEADZONE_SQ
        val dx = (sb.maxX() + 1 - sb.minX()).toDouble()
        val dz = (sb.maxZ() + 1 - sb.minZ()).toDouble()
        val length = Math.max(dx, dz)
        val r = SHIP_LENGTH_DEADZONE_FACTOR * length
        return r * r
    }

    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        if (physShip == null) return                       // world-placed: nothing to turn
        if (physShip.isStatic || physShip.mass <= 0.0) return

        // Redstone gate: power 15 multiplies PD output by 0, fully suppressing torque so
        // the ship coasts on its own angular damping. Power 0 leaves the controller at
        // full strength. (We could early-out only when the scale is zero, but checking
        // here also avoids the trig/quaternion math when the user has the brake fully on.)
        val powerScale = (MAX_REDSTONE - cachedPower).toDouble() / MAX_REDSTONE
        if (powerScale <= 0.0) return

        val target = cachedTargetYawRad
        if (target.isNaN()) return

        val rotation = physShip.transform.shipToWorldRotation
        val currentYaw = facingMcYaw(rotation, cachedFacing)
        val errMC = shortestArc(target - currentYaw)
        val angVelY = physShip.angularVelocity.y()
        if (Math.abs(errMC) < SETTLE_EPSILON && Math.abs(angVelY) < SETTLE_EPSILON) return

        // World-Y angular acceleration we want, scaled by the redstone brake. PD in MC
        // convention: positive errMC ⇒ want CW-from-above rotation ⇒ negative JOML ω̇_y;
        // damping always opposes current JOML ω_y.
        val desiredAccel = Vector3d(0.0, (-K_P * errMC - K_D * angVelY) * powerScale, 0.0)

        // R·I·R⁻¹ sandwich: world-frame α → body-frame α → body-frame τ via inertia tensor
        // → world-frame τ. Makes the response invariant to inertia tensor shape, so a long
        // thin ship turns at the same rate as a cube of equal mass.
        val torque = Vector3d()
        rotation.transformInverse(desiredAccel, torque)
        physShip.momentOfInertia.transform(torque)
        rotation.transform(torque)
        physShip.applyWorldTorque(torque)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!staticTargetYawRad.isNaN()) tag.putFloat("TargetYaw", staticTargetYawRad)
        if (!compassStack.isEmpty) {
            val cTag = CompoundTag()
            compassStack.save(cTag)
            tag.put("Compass", cTag)
        }
        compassPinPos?.let {
            tag.putInt("PinX", it.x); tag.putInt("PinY", it.y); tag.putInt("PinZ", it.z)
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        staticTargetYawRad = if (tag.contains("TargetYaw")) tag.getFloat("TargetYaw") else Float.NaN
        compassStack = if (tag.contains("Compass")) ItemStack.of(tag.getCompound("Compass")) else ItemStack.EMPTY
        compassPinPos = if (tag.contains("PinX")) {
            BlockPos(tag.getInt("PinX"), tag.getInt("PinY"), tag.getInt("PinZ"))
        } else null
        // In static mode, the cache is just the static yaw. In compass mode, leave it NaN
        // until the first serverTick refresh populates a real bearing (no physTick-side
        // torque happens before that, which is exactly what we want during chunk-load).
        cachedTargetYawRad = if (compassPinPos == null) staticTargetYawRad else Float.NaN
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
        /** Proportional gain (1/s²) — desired α per radian of MC-yaw error. With K_D below
         *  the steady-state JOML yaw rate is `K_P·err / K_D ≈ 0.67·err` rad/s, so a 90°
         *  error settles toward ~60°/s and a 180° tune completes in ~3 s. */
        const val K_P: Double = 1.0

        /** Derivative gain (1/s) — damping coefficient on world-Y angular velocity. Critical
         *  at `2·√K_P ≈ 2.0`; 1.5 is slightly *under*-damped so the turn ends with a small
         *  visible coast-in instead of a dead-stick stop. */
        const val K_D: Double = 1.5

        /** Below this error AND this angular rate the controller emits no torque — keeps the
         *  ship from twitching on tiny floating-point residuals once it's settled. */
        const val SETTLE_EPSILON: Double = 0.001

        /** Game-tick cadence for recomputing the pin bearing in compass mode. 10 ticks
         *  (0.5 s at 20 TPS) keeps the steered heading visibly fresh while doing the lookup
         *  + trig only a couple of times per second — *not* on every physics tick. */
        const val PIN_REFRESH_INTERVAL_TICKS: Long = 10L

        /** Pin dead-zone radius as a multiple of the ship's longest horizontal AABB extent.
         *  1.2× gives ships room to settle without the bearing wrapping endlessly — a 20 m
         *  ship treats anywhere within 24 m of the pin as arrived. */
        const val SHIP_LENGTH_DEADZONE_FACTOR: Double = 1.2

        /** Fallback dead-zone (blocks²) used only when the eyeroscope isn't on a ship. There's
         *  no ship to spin in this case; the value just keeps the math symmetric. */
        const val WORLD_FALLBACK_DEADZONE_SQ: Double = 1.0

        /** Standard vanilla redstone signal cap. Centralised so the power-scaling math
         *  and the "we've arrived" comparator output reference the same value. */
        const val MAX_REDSTONE: Int = 15

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
