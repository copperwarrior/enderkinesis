package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.shipwrights.enderkinesis.block.ShulkerPufferBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.dynamicBody
import org.shipwrights.enderkinesis.util.WorldBox
import org.shipwrights.enderkinesis.util.BoxSearchScratch
import org.shipwrights.enderkinesis.util.forEachShipBlockInBox
import org.shipwrights.enderkinesis.util.standaloneBodiesInBox
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.core.api.util.GameTickOnly

/**
 * Thrust direction is local `-FACING` (Newton's third — exhaust out the front, reaction
 * into the back). [physTick] rotates it through `shipToWorldRotation` and hands it to
 * [PhysShip.applyWorldForceToModelPos] at the block's shipyard position, so off-axis
 * puffers naturally produce torque (VS2 derives linear + angular from off-COM application).
 *
 * Caches [cachedPower]/[cachedFacing] as volatiles in [serverTick] for the phys thread to
 * read. Auto-attached: VS2's `MixinLevelChunk` registers any [BlockEntityPhysicsListener]
 * on chunk load, so being one is sufficient — no explicit attach/detach.
 */
@OptIn(VsBeta::class)
class ShulkerPufferBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.SHULKER_PUFFER.get(), pos, state),
    BlockEntityPhysicsListener,
    Container {

    @Volatile override lateinit var dimension: DimensionId

    /** FACING is fixed at placement and doesn't change, but it still needs the volatile
     *  cross-thread read because [physTick] runs on the physics thread. */
    @Volatile private var cachedFacing: Direction = state.getValue(BlockStateProperties.FACING)
    @Volatile private var cachedPower: Int = state.getValue(BlockStateProperties.POWER)
    @Volatile private var cachedFueled: Boolean = false
    // World-placed only: the game thread fills these, the physics thread pushes them.
    @Volatile private var exhaustTargets: ExhaustTargets = ExhaustTargets.NONE
    private val exhaustScratch = AABBd()
    private val exhaustCursor = BlockPos.MutableBlockPos()
    private val exhaustNearest = Long2DoubleOpenHashMap()
    private val boxScratch = BoxSearchScratch()

    private val items = net.minecraft.core.NonNullList.withSize(1, ItemStack.EMPTY)

    /** Game ticks of fuelled (boosted) operation remaining. Decremented each game tick while
     *  powered; topped up by [FUEL_TICKS_PER_DOSE] each time we consume a dragon's-breath
     *  bottle. The state is *transient on the physics side* — only the game-tick side reads
     *  / writes this, and pushes the boolean result into [cachedFueled] for the phys tick. */
    private var fuelTicks: Int = 0

    /** Client thread only. Ticks of loop time to the next puff, and the fuel state that set it. */
    var puffCountdown: Float = 0f
    var puffFueled: Boolean = false
    /** Client thread only. The running event burst, or [BURST_NONE]. */
    var burstKind: Int = BURST_NONE
    var burstAge: Int = 0

    fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        cachedFacing = state.getValue(BlockStateProperties.FACING)
        cachedPower = state.getValue(BlockStateProperties.POWER)

        val powered = cachedPower > 0
        if (powered) {
            // Consume a dragon's breath if we're out of fuel and the slot has one. Doesn't
            // gate on whether the user *wants* the boost — fuel burns whenever powered, so
            // disabling boost = remove the fuel rather than toggle a flag.
            if (fuelTicks <= 0) {
                val s = items[0]
                if (!s.isEmpty && s.`is`(Items.DRAGON_BREATH)) {
                    s.shrink(1)
                    if (s.isEmpty) items[0] = ItemStack.EMPTY
                    fuelTicks = FUEL_TICKS_PER_DOSE
                    // Dragon's breath bottles are consumed *as* bottles, not crafted-down to
                    // glass — vanilla brewing returns the empty bottle, and the player would
                    // expect the same from any "fuel" use. Eject out the back: insert into
                    // a container behind us if there is one, else spawn an ItemEntity in the
                    // world at the back face.
                    ejectEmptyBottle(level, pos, cachedFacing)
                    playWorldSound(level, pos, EKSounds.SHULKER_PUFFER_FUEL_IGNITE.get())
                    burst(level, pos, state, BURST_IGNITE)
                    setChanged()
                }
            }
            if (fuelTicks > 0) fuelTicks--
        }
        cachedFueled = fuelTicks > 0
        syncFueled(level, pos, state, powered)
        scanExhaust(level, pos, powered)
    }

    @OptIn(GameTickOnly::class, VsBeta::class)
    private fun scanExhaust(level: ServerLevel, pos: BlockPos, powered: Boolean) {
        val reach = if (!powered || level.getLoadedShipManagingPos(pos) != null) 0.0
            else clearReach(level, pos, cachedFacing)
        if (reach <= 0.0) {
            exhaustTargets = ExhaustTargets.NONE
            return
        }
        val facing = cachedFacing
        val fx = facing.stepX.toDouble()
        val fy = facing.stepY.toDouble()
        val fz = facing.stepZ.toDouble()
        // Exhaust distances are measured from the nozzle face along FACING.
        val n0 = (pos.x + 0.5 + fx * 0.5) * fx + (pos.y + 0.5 + fy * 0.5) * fy + (pos.z + 0.5 + fz * 0.5) * fz
        val box = WorldBox.of(exhaustBox(pos, facing, reach))

        val nearestShip = exhaustNearest.apply { clear() }
        level.forEachShipBlockInBox(box, boxScratch) { ship, _, middle ->
            val d = (middle.x() * fx + middle.y() * fy + middle.z() * fz - n0 - 0.5).coerceAtLeast(0.0)
            if (d < nearestShip.getOrDefault(ship.id, Double.MAX_VALUE)) nearestShip.put(ship.id, d)
        }
        val bodyIds = level.standaloneBodiesInBox(box, boxScratch)
        val bodyDist = DoubleArray(bodyIds.size) { i ->
            val aabb = level.shipObjectWorld.allBodies.getById(bodyIds[i])?.aabb
            if (aabb == null) 0.0 else {
                val near = minOf(aabb.minX() * fx, aabb.maxX() * fx) +
                    minOf(aabb.minY() * fy, aabb.maxY() * fy) +
                    minOf(aabb.minZ() * fz, aabb.maxZ() * fz)
                (near - n0).coerceAtLeast(0.0)
            }
        }
        exhaustTargets = ExhaustTargets(
            nearestShip.keys.toLongArray(), nearestShip.values.toDoubleArray(), bodyIds, bodyDist,
        )
    }

    /** Blocks of exhaust before the first world block that has collision, which stops the push. */
    private fun clearReach(level: ServerLevel, pos: BlockPos, facing: Direction): Double {
        val steps = EXHAUST_REACH.toInt()
        for (k in 1..steps) {
            val p = exhaustCursor.setWithOffset(pos, facing.stepX * k, facing.stepY * k, facing.stepZ * k)
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty) return k - 1.0
        }
        return EXHAUST_REACH
    }

    // Game thread only.
    private fun exhaustBox(pos: BlockPos, facing: Direction, reach: Double): AABBd {
        val nx = pos.x + 0.5 + facing.stepX * 0.5
        val ny = pos.y + 0.5 + facing.stepY * 0.5
        val nz = pos.z + 0.5 + facing.stepZ * 0.5
        val ex = nx + facing.stepX * reach
        val ey = ny + facing.stepY * reach
        val ez = nz + facing.stepZ * reach
        val hx = if (facing.stepX == 0) 0.5 else 0.0
        val hy = if (facing.stepY == 0) 0.5 else 0.0
        val hz = if (facing.stepZ == 0) 0.5 else 0.0
        return exhaustScratch
            .setMin(minOf(nx, ex) - hx, minOf(ny, ey) - hy, minOf(nz, ez) - hz)
            .setMax(maxOf(nx, ex) + hx, maxOf(ny, ey) + hy, maxOf(nz, ez) + hz)
    }

    /** A queued bottle burns on the next tick, so it counts as fuel here. This stops a fuel_out and a
     *  state flicker between two bottles. */
    private fun syncFueled(level: ServerLevel, pos: BlockPos, state: BlockState, powered: Boolean) {
        val fueled = cachedFueled || (powered && items[0].`is`(Items.DRAGON_BREATH))
        if (state.getValue(ShulkerPufferBlock.FUELED) == fueled) return
        level.setBlock(pos, state.setValue(ShulkerPufferBlock.FUELED, fueled), Block.UPDATE_CLIENTS)
        if (!fueled && powered) {
            playWorldSound(level, pos, EKSounds.SHULKER_PUFFER_FUEL_OUT.get())
            burst(level, pos, state, BURST_FUEL_OUT)
        }
    }

    /** The client spawns the puffs, so they can follow the pulses of the loop it plays. */
    override fun triggerEvent(id: Int, param: Int): Boolean {
        if (id != EVENT_BURST) return super.triggerEvent(id, param)
        if (level?.isClientSide == true) {
            burstKind = param
            burstAge = 0
        }
        return true
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level.isClientSide) ShulkerPufferClientRegistry.register(level, this)
    }

    override fun setRemoved() {
        level?.let { if (it.isClientSide) ShulkerPufferClientRegistry.unregister(it, this) }
        super.setRemoved()
    }

    /**
     * Eject one empty glass bottle backwards.
     *
     *  Priority order:
     *   1. Container behind the puffer in its own body frame (chest / hopper / barrel on the
     *      same ship, or world chest if puffer is world-placed). Direct insertion, no
     *      ItemEntity needed.
     *   2. Otherwise, spawn a free [ItemEntity] in **world** coords at the back face — for
     *      ship-mounted puffers we transform the nozzle position + ejection velocity through
     *      `shipToWorld` first, so the bottle pops out at the actual world location of the
     *      ship instead of getting stranded in the shipyard region.
     */
    @OptIn(GameTickOnly::class)
    private fun ejectEmptyBottle(level: ServerLevel, pos: BlockPos, facing: Direction) {
        val backDir = facing.opposite
        val backPos = pos.relative(backDir)
        val ship = level.getLoadedShipManagingPos(pos)

        // Try in-frame container first.
        (level.getBlockEntity(backPos) as? Container)?.let { container ->
            if (tryInsertBottle(container)) return
        }

        // Fall through to spawning an ItemEntity in world space.
        val backLocal = Vector3d(
            pos.x + 0.5 + backDir.stepX * 0.7,
            pos.y + 0.5 + backDir.stepY * 0.7,
            pos.z + 0.5 + backDir.stepZ * 0.7,
        )
        val vel = Vector3d(
            backDir.stepX * EJECT_SPEED,
            backDir.stepY * EJECT_SPEED,
            backDir.stepZ * EJECT_SPEED,
        )
        if (ship != null) {
            ship.transform.shipToWorld.transformPosition(backLocal)
            ship.transform.shipToWorldRotation.transform(vel)
        }
        val entity = ItemEntity(level, backLocal.x, backLocal.y, backLocal.z, ItemStack(Items.GLASS_BOTTLE))
        entity.setDeltaMovement(vel.x, vel.y, vel.z)
        entity.setDefaultPickUpDelay()
        level.addFreshEntity(entity)
    }

    /** Insert a single glass bottle into [container]. Returns true on success (a slot
     *  accepted the bottle). Iterates slots in order, prefers stacking onto an existing
     *  bottle stack over claiming a new empty slot — same priority a hopper uses. */
    private fun tryInsertBottle(container: Container): Boolean {
        // Pass 1: try to stack onto an existing bottle slot.
        for (i in 0 until container.containerSize) {
            val cur = container.getItem(i)
            if (cur.isEmpty || !cur.`is`(Items.GLASS_BOTTLE)) continue
            if (cur.count >= cur.maxStackSize) continue
            if (!container.canPlaceItem(i, cur)) continue
            cur.grow(1)
            container.setChanged()
            return true
        }
        // Pass 2: first empty slot that accepts a glass bottle.
        for (i in 0 until container.containerSize) {
            if (!container.getItem(i).isEmpty) continue
            val one = ItemStack(Items.GLASS_BOTTLE)
            if (!container.canPlaceItem(i, one)) continue
            container.setItem(i, one)
            container.setChanged()
            return true
        }
        return false
    }

    @OptIn(PhysTickOnly::class)
    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        val power = cachedPower
        if (power <= 0) return
        if (physShip == null) {
            pushExhaustTargets(physLevel, power)
            return
        }

        // Local thrust direction: opposite of FACING. The block "blows" exhaust out the
        // front; reaction force points into the back.
        val facing = cachedFacing
        val worldThrust = Vector3d(
            -facing.stepX.toDouble(),
            -facing.stepY.toDouble(),
            -facing.stepZ.toDouble(),
        )
        // Body → world: rotate by the ship's current orientation so the thrust direction
        // tracks ship rotation (a puffer pointing "north in shipyard" thrusts "south in
        // world" only when the ship is at identity rotation; if the ship has yawed, the
        // world direction follows).
        physShip.transform.shipToWorldRotation.transform(worldThrust)

        val rawMag = thrustMagnitude(power)
        // Block-sourced forces scale as scale³ — VS2's fluid drag is
        // cross-section-aware (∝ scale²), so a linear (× scale) multiplier
        // gives drag-divided steady-state velocity ∝ 1/scale (smaller ships
        // fly faster, bigger sluggish). Cubic scaling cancels that and the
        // ship-lengths-per-second the wielder sees stays constant across
        // any Staff-of-Scales setting. Same conclusion ZPL reaches the
        // other way (mass ∝ scale³ + force ∝ scale⁴).
        val s = physShip.transform.shipToWorldScaling.x()
        val mag = rawMag * s * s * s

        // Apply at the block's shipyard centre. Off-COM application produces a torque, which
        // is what makes a puffer mounted away from the ship's centre naturally rotate it —
        // exactly the behaviour Clockwork / Propulsion / VStuff thrusters rely on.
        physShip.applyWorldForceToModelPos(
            Vector3d(worldThrust.x * mag, worldThrust.y * mag, worldThrust.z * mag),
            Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5),
        )
    }

    private fun thrustMagnitude(power: Int): Double {
        val baseMag = MAX_FORCE * (power / 15.0)
        return if (cachedFueled) baseMag * FUEL_BOOST else baseMag
    }

    /** A world-placed puffer has no scale, so the force is the ship thrust at scale 1, along +FACING. */
    @OptIn(PhysTickOnly::class)
    private fun pushExhaustTargets(physLevel: PhysLevel, power: Int) {
        val targets = exhaustTargets
        if (targets.shipIds.isEmpty() && targets.bodyIds.isEmpty()) return
        val facing = cachedFacing
        val mag = thrustMagnitude(power)
        // A point on the exhaust axis: the force acts along that axis, so any point on it gives the same torque.
        val nx = blockPos.x + 0.5 + facing.stepX * 0.5
        val ny = blockPos.y + 0.5 + facing.stepY * 0.5
        val nz = blockPos.z + 0.5 + facing.stepZ * 0.5
        for (i in targets.shipIds.indices) {
            val ship = physLevel.getShipById(targets.shipIds[i]) ?: continue
            val m = mag * exhaustFalloff(targets.shipDist[i])
            ship.applyWorldForce(Vector3d(facing.stepX * m, facing.stepY * m, facing.stepZ * m), Vector3d(nx, ny, nz))
        }
        for (i in targets.bodyIds.indices) {
            val body = physLevel.dynamicBody(targets.bodyIds[i]) ?: continue
            val m = mag * exhaustFalloff(targets.bodyDist[i])
            body.applyWorldForce(Vector3d(facing.stepX * m, facing.stepY * m, facing.stepZ * m), Vector3d(nx, ny, nz))
        }
    }

    /** Force fraction at [distance] blocks from the nozzle. */
    private fun exhaustFalloff(distance: Double): Double = (1.0 - distance / EXHAUST_REACH).coerceIn(0.0, 1.0)

    /** One snapshot, so the physics thread never reads ids and distances from two different scans. */
    private class ExhaustTargets(
        val shipIds: LongArray,
        val shipDist: DoubleArray,
        val bodyIds: LongArray,
        val bodyDist: DoubleArray,
    ) {
        companion object {
            val NONE = ExhaustTargets(LongArray(0), DoubleArray(0), LongArray(0), DoubleArray(0))
        }
    }

    /** For ship-mounted puffers the block position is in shipyard coords,
     *  so we transform through `shipToWorld` first or the sound plays in the far-off
     *  shipyard region instead of next to the player. */
    @OptIn(GameTickOnly::class)
    private fun playWorldSound(level: ServerLevel, pos: BlockPos, event: SoundEvent) {
        val ship = level.getLoadedShipManagingPos(pos)
        val soundPos = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        if (ship != null) ship.transform.shipToWorld.transformPosition(soundPos)
        level.playSound(
            null,                                           // listener=null → broadcast to range
            soundPos.x, soundPos.y, soundPos.z,
            event,
            SoundSource.BLOCKS,
            1.0f,                                           // volume
            1.0f,                                           // pitch
        )
    }


    override fun getContainerSize(): Int = 1
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack = items[slot]
    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val r = ContainerHelper.removeItem(items, slot, amount)
        if (!r.isEmpty) setChanged()
        return r
    }
    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)
    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = stack
        if (stack.count > maxStackSize) stack.count = maxStackSize
        setChanged()
    }
    override fun stillValid(player: Player): Boolean =
        Container.stillValidBlockEntity(this, player)
    override fun clearContent() { items.clear() }
    /** Hopper / dispenser interop: only dragon's breath is accepted as fuel. */
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean =
        stack.`is`(Items.DRAGON_BREATH)


    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        ContainerHelper.saveAllItems(tag, items)
        tag.putInt("FuelTicks", fuelTicks)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        items.clear()
        ContainerHelper.loadAllItems(tag, items)
        fuelTicks = tag.getInt("FuelTicks")
    }

    companion object {
        /** Peak thrust force (Newtons) at full redstone power (15), *unfueled*. Matched to
         *  the same order-of-magnitude band as the void harness's pull (240 kN) so a couple
         *  of puffers can meaningfully shift a small ship without single-block sending it
         *  flying. Fueled, this scales by [FUEL_BOOST] = 4×, hitting the harness's peak. */
        const val MAX_FORCE: Double = 60_000.0

        /** Multiplier applied to thrust force when a dragon's-breath bottle is fueling the
         *  puffer. Per spec — "Dragon's breath allows 4x force". */
        const val FUEL_BOOST: Double = 4.0

        /** Ticks of fueled operation per single dragon's-breath bottle. 600 ticks ≈ 30 s of
         *  boosted thrust — long enough that a player isn't constantly refueling, short
         *  enough that fuel is meaningfully a consumable. */
        const val FUEL_TICKS_PER_DOSE: Int = 600

        /** Backward-ejection speed (blocks/tick) for the empty bottle. Same scale a vanilla
         *  dispenser uses for its ejected items so the bottle pops out at a believable
         *  velocity rather than dribbling or rocketing. */
        private const val EJECT_SPEED: Double = 0.2

        /** Blocks in front of a world-placed puffer whose ships and bodies take the push. */
        private const val EXHAUST_REACH: Double = 4.0

        const val EVENT_BURST: Int = 1
        const val BURST_NONE: Int = -1
        const val BURST_POWER_ON: Int = 0
        const val BURST_POWER_OFF: Int = 1
        const val BURST_IGNITE: Int = 2
        const val BURST_FUEL_OUT: Int = 3

        fun burst(level: Level, pos: BlockPos, state: BlockState, kind: Int) {
            level.blockEvent(pos, state.block, EVENT_BURST, kind)
        }
    }
}
