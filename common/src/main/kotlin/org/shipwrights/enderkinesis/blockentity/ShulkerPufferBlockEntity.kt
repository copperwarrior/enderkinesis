package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener

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
@OptIn(PhysTickOnly::class, VsBeta::class)
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

    private val items = net.minecraft.core.NonNullList.withSize(1, ItemStack.EMPTY)

    /** Game ticks of fuelled (boosted) operation remaining. Decremented each game tick while
     *  powered; topped up by [FUEL_TICKS_PER_DOSE] each time we consume a dragon's-breath
     *  bottle. The state is *transient on the physics side* — only the game-tick side reads
     *  / writes this, and pushes the boolean result into [cachedFueled] for the phys tick. */
    private var fuelTicks: Int = 0

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
                    playFuelConsumeSound(level, pos)
                    setChanged()
                }
            }
            if (fuelTicks > 0) fuelTicks--
        }
        cachedFueled = fuelTicks > 0

        if (powered) spawnPuffParticles(level, pos, cachedFacing, cachedPower, cachedFueled)
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

    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        if (physShip == null) return                       // only thrusts when ship-mounted
        val power = cachedPower
        if (power <= 0) return

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

        val baseMag = MAX_FORCE * (power / 15.0)
        val rawMag = if (cachedFueled) baseMag * FUEL_BOOST else baseMag
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

    private fun spawnPuffParticles(
        level: ServerLevel, pos: BlockPos, facing: Direction, power: Int, fueled: Boolean,
    ) {
        // Tick-throttle the puffs. Vanilla DRAGON_BREATH at 1+ particles/tick reads as a
        // chimney; a thruster looks more like a thruster at 1 particle every few ticks.
        // Fuel triples the rate (~3 puffs/sec at 20 TPS) for the "engine kicked into a
        // higher gear" cue.
        val throttle = if (fueled) PARTICLE_THROTTLE_FUELED else PARTICLE_THROTTLE_UNFUELED
        if (level.gameTime % throttle != 0L) return

        // Nozzle is just past the front face — 0.55 m out from block centre so the puff
        // doesn't spawn inside the block model and immediately get culled by it.
        val nozzleWorld = Vector3d(
            pos.x + 0.5 + facing.stepX * 0.55,
            pos.y + 0.5 + facing.stepY * 0.55,
            pos.z + 0.5 + facing.stepZ * 0.55,
        )
        // "More oomph": initial particle velocity scales 0.2 (unfueled, low power) → 0.55
        // (fueled, full power) blocks/tick. The [ShulkerPufferParticle] uses this directly
        // to drive its distance-keyed grow/fade curve — higher velocity = particle hits the
        // 1-block "fully-grown" mark faster, then has more lifetime budget to fade.
        val baseSpeed = if (fueled) FUELED_PARTICLE_SPEED else UNFUELED_PARTICLE_SPEED
        val speed = baseSpeed * (0.5 + power / 15.0 * 0.5)        // 50–100 % of base by power
        val ejectWorld = Vector3d(
            facing.stepX * speed,
            facing.stepY * speed,
            facing.stepZ * speed,
        )
        // Ship-mounted puffer: transform position + ejection direction through the live ship
        // pose so the puff appears at the ship's actual world location with velocity rotated
        // to match the ship's orientation. World-placed puffers skip and use raw values.
        val ship = level.getLoadedShipManagingPos(pos)
        if (ship != null) {
            ship.transform.shipToWorld.transformPosition(nozzleWorld)
            ship.transform.shipToWorldRotation.transform(ejectWorld)
        }
        // Ship velocity inheritance: add the world-frame point velocity of the ship at
        // the nozzle position (linear + ω × r), converted to particle units. Without
        // this, a puffer on a fast-moving ship emits particles that hang in space
        // behind the ship — exhaust should travel *with* the ship the moment it leaves
        // the nozzle, then drift due to friction.
        //
        // CRITICAL unit conversion: VS2's `Ship.getVelocity()` is m/s and
        // `getAngularVelocity()` is rad/s (standard physics). Particle `xd/yd/zd` is in
        // **blocks per tick**. At 20 TPS, `1 m/s = 0.05 b/t`. Without the conversion,
        // ship.velocity dominates ejectWorld by ~20×, sending particles streaming in
        // the ship-motion direction at huge speeds instead of out the nozzle.
        val shipPointVel = Vector3d(0.0, 0.0, 0.0)
        if (ship != null) {
            val vel = ship.velocity
            val omega = ship.omega
            val comWorld = ship.transform.positionInWorld
            val rX = nozzleWorld.x - comWorld.x()
            val rY = nozzleWorld.y - comWorld.y()
            val rZ = nozzleWorld.z - comWorld.z()
            // v_point = v_linear + ω × r. Both vel and omega are world-frame; result
            // is m/s. Scale to b/t for the particle.
            shipPointVel.set(
                (vel.x() + omega.y() * rZ - omega.z() * rY) * METER_PER_SEC_TO_BLOCKS_PER_TICK,
                (vel.y() + omega.z() * rX - omega.x() * rZ) * METER_PER_SEC_TO_BLOCKS_PER_TICK,
                (vel.z() + omega.x() * rY - omega.y() * rX) * METER_PER_SEC_TO_BLOCKS_PER_TICK,
            )
        }

        val jitter = 0.04
        level.sendParticles(
            EKParticles.shulkerPuffer(),
            nozzleWorld.x + (level.random.nextDouble() - 0.5) * jitter,
            nozzleWorld.y + (level.random.nextDouble() - 0.5) * jitter,
            nozzleWorld.z + (level.random.nextDouble() - 0.5) * jitter,
            0,                                              // count=0 + xyzd as velocity
            shipPointVel.x + ejectWorld.x,
            shipPointVel.y + ejectWorld.y,
            shipPointVel.z + ejectWorld.z,
            1.0,                                            // velocity scale
        )
    }

    /** Play the ender dragon's fireball shot sound at the puffer's world position. Used as
     *  the audio cue for each bottle of dragon's breath consumed — same source sample as a
     *  dragon spitting at you, which lines up with the puffer's exhaust looking like
     *  dragon's breath. For ship-mounted puffers the block position is in shipyard coords,
     *  so we transform through `shipToWorld` first or the sound plays in the far-off
     *  shipyard region instead of next to the player. */
    private fun playFuelConsumeSound(level: ServerLevel, pos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(pos)
        val soundPos = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        if (ship != null) ship.transform.shipToWorld.transformPosition(soundPos)
        level.playSound(
            null,                                           // listener=null → broadcast to range
            soundPos.x, soundPos.y, soundPos.z,
            SoundEvents.ENDER_DRAGON_SHOOT,
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

        /** Server ticks between particle emissions while *unfueled* — one puff every N
         *  ticks instead of vanilla DRAGON_BREATH's "every tick". 6 ticks → ~3 Hz, reads as
         *  a deliberate exhaust pulse rather than a chimney. */
        private const val PARTICLE_THROTTLE_UNFUELED: Long = 6L

        /** Faster cadence when fuelled — same kind of pulse, three times as frequent. */
        private const val PARTICLE_THROTTLE_FUELED: Long = 2L

        /** Initial particle velocity (blocks/tick) for an unfuelled puffer at full redstone.
         *  Scales 50–100 % linearly with redstone power. Tuned so the particle covers the
         *  1-block grow distance in ~4 ticks at top, which feels like a punchy puff against
         *  the 8–12 tick lifetime. */
        private const val UNFUELED_PARTICLE_SPEED: Double = 0.25

        /** Same idea, but the boosted exhaust travels twice as fast — that's the visual
         *  cue that fuel is doing something distinct from "more particles". */
        private const val FUELED_PARTICLE_SPEED: Double = 0.5

        /** Backward-ejection speed (blocks/tick) for the empty bottle. Same scale a vanilla
         *  dispenser uses for its ejected items so the bottle pops out at a believable
         *  velocity rather than dribbling or rocketing. */
        private const val EJECT_SPEED: Double = 0.2

        /** Unit conversion: 1 m/s of ship velocity equals this many blocks-per-tick of
         *  particle velocity, at MC's 20 TPS (1 m / (20 ticks/s) ≈ 0.05 b/t per m/s).
         *  Used in [spawnPuffParticles] to bring [Ship.velocity] / [Ship.omega] (which
         *  VS2 expresses as m/s and rad/s) into the b/t domain the particle's
         *  per-tick `xd/yd/zd` integration expects. Without this scale, ship velocity
         *  inheritance was 20× too large and dominated the particle's nozzle ejection. */
        private const val METER_PER_SEC_TO_BLOCKS_PER_TICK: Double = 1.0 / 20.0
    }
}
