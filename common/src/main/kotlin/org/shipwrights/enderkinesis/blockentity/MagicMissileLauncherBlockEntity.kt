package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.MagicMissileLauncherBlock
import org.shipwrights.enderkinesis.entity.MagicMissileEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

/**
 * Magic Missile Launcher block entity — 16-slot inventory (a 4×4 grid on the front face)
 * that fires its stored magic missiles one at a time while powered.
 *
 * **Inventory.** 16 [ItemStack] slots, each holds one [EKItems.MAGIC_MISSILE]. Implements
 * [WorldlyContainer] so a hopper feeding into the top inserts and a hopper underneath the
 * bottom extracts (front-face right-click handles manual insertion / removal via
 * [MagicMissileLauncherBlock]).
 *
 * **Firing.** While [BlockStateProperties.POWERED] is true, ticks down [firingCooldown];
 * when it reaches zero, the lowest-index non-empty slot's missile is consumed and a fresh
 * [MagicMissileEntity] is spawned in front of the block with velocity along the launcher's
 * facing direction. Cooldown resets to [FIRE_PERIOD_TICKS] (20 ticks = 1 s) so subsequent
 * fires pace out at exactly one missile per second.
 *
 * **Render sync.** Per-slot rendering is handled by `MagicMissileLauncherRenderer`,
 * which reads `items` straight from this BE every frame. To make sure clients see the
 * latest contents (vanilla doesn't push BE NBT on inventory mutations by default), every
 * inventory mutator calls [markChangedAndSync] which both marks the chunk dirty and
 * broadcasts the BE-data packet to nearby clients.
 */
class MagicMissileLauncherBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.MAGIC_MISSILE_LAUNCHER.get(), pos, state),
    WorldlyContainer, Commandable {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    /** Ticks until the next fire while powered. Starts at zero so a rising power edge
     *  produces an immediate first shot, then paces at [FIRE_PERIOD_TICKS] thereafter. */
    private var firingCooldown: Int = 0

    fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        if (!state.getValue(BlockStateProperties.POWERED)) {
            firingCooldown = 0
            return
        }
        if (firingCooldown > 0) {
            firingCooldown--
            return
        }
        if (fireOne(level, pos, state)) {
            // ±1-tick jitter on top of FIRE_PERIOD_TICKS so consecutive shots don't
            // lock-step with redstone clocks or with adjacent launchers on the same
            // power source — the cadence reads as alive rather than mechanical.
            firingCooldown = FIRE_PERIOD_TICKS + level.random.nextInt(3) - 1
        }
    }

    /** Pick a **random** occupied slot, consume one, spawn the projectile from **that
     *  slot's position** on the front face along the facing axis. Returns true if a
     *  shot was fired. Randomising the firing order makes a partially-loaded launcher
     *  read as alive — adjacent shots come from different slots — rather than draining
     *  predictably top-row-first.
     *
     *  Slot position calc: slot N's centre in model 16ths is `(cx, cy) = (14 − col·4,
     *  14 − row·4)` (matching the BER's slot grid). That maps to block-fraction
     *  `(cxF, cyF)` in default (north-facing) orientation. The world coords depend on
     *  facing — the block's blockstate `"y": 90/180/270` rotation is CW-from-above
     *  around the block centre, so the slot's `(x, z)` on the front face rotates with
     *  the block while `y` (cy) stays the same. */
    private fun fireOne(
        level: ServerLevel, pos: BlockPos, state: BlockState,
        owner: Player? = null, targetPos: Vec3? = null, targetEntity: Entity? = null,
        targetBlockPos: BlockPos? = null,
    ): Boolean {
        // Collect the indices of occupied slots, then pick one uniformly at random.
        // 16 slots max so the per-tick allocation is cheap.
        val occupied = (0 until SLOT_COUNT).filter { !items[it].isEmpty }
        if (occupied.isEmpty()) return false
        val slot = occupied[level.random.nextInt(occupied.size)]

        items[slot].shrink(1)
        if (items[slot].isEmpty) items[slot] = ItemStack.EMPTY

        val facing = state.getValue(MagicMissileLauncherBlock.FACING)
        val col = slot % 4
        val row = slot / 4
        val cxF = (14 - col * 4) / 16.0
        val cyF = (14 - row * 4) / 16.0

        // Slot's block-fraction (x, y, z) after rotating the model with the block's
        // facing. For NORTH (no rotation) the slot lies at `(cxF, cyF, 0)`; horizontal
        // facings rotate around Y (cy stays the world y, cx maps onto whichever
        // horizontal world axis the front face sits on). UP / DOWN rotate around X so
        // the model's vertical axis (cy) becomes a world-z axis and the model's
        // horizontal (cx) stays world-x; cy then dictates *which side* (north or
        // south) of the vertical face the slot is on.
        val (slotX, slotY, slotZ) = when (facing) {
            Direction.NORTH -> Triple(cxF, cyF, 0.0)
            Direction.EAST  -> Triple(1.0, cyF, cxF)
            Direction.SOUTH -> Triple(1.0 - cxF, cyF, 1.0)
            Direction.WEST  -> Triple(0.0, cyF, 1.0 - cxF)
            Direction.UP    -> Triple(cxF, 1.0, cyF)
            Direction.DOWN  -> Triple(cxF, 0.0, 1.0 - cyF)
        }

        val dx = facing.stepX.toDouble()
        val dy = facing.stepY.toDouble()
        val dz = facing.stepZ.toDouble()
        // Spawn just outside the slot's pixel on the front face — far enough that the
        // missile clears the block's own hull immediately.
        var spawnX = pos.x + slotX + dx * SPAWN_OUTWARD_OFFSET
        var spawnY = pos.y + slotY + dy * SPAWN_OUTWARD_OFFSET
        var spawnZ = pos.z + slotZ + dz * SPAWN_OUTWARD_OFFSET
        var vx = dx
        var vy = dy
        var vz = dz

        // If the launcher sits on a VS2 ship, `pos`/`facing` are in **shipyard** frame.
        // Spawn the missile in worldspace directly: transform the spawn position via
        // `shipToWorld` and rotate the velocity vector via the ship's current
        // orientation. This way the projectile is born in world coords with a world-
        // axis velocity, and the trail / hit detection don't have to round-trip
        // through shipyard frames downstream.
        val ship = level.getLoadedShipManagingPos(pos)
        if (ship != null) {
            val transform = ship.transform
            val posV = Vector3d(spawnX, spawnY, spawnZ)
            transform.shipToWorld.transformPosition(posV)
            spawnX = posV.x; spawnY = posV.y; spawnZ = posV.z
            val velV = Vector3d(vx, vy, vz)
            transform.shipToWorld.transformDirection(velV)
            vx = velV.x; vy = velV.y; vz = velV.z
        }

        val missile = MagicMissileEntity(level, spawnX, spawnY, spawnZ)
        missile.homeShipId = ship?.id ?: MagicMissileEntity.NO_SHIP
        missile.deltaMovement = Vec3(vx, vy, vz).normalize().scale(MagicMissileEntity.SPEED)
        if (owner != null) missile.owner = owner
        if (targetPos != null) missile.targetPos = targetPos
        if (targetEntity != null) missile.lockedTargetEntityId = targetEntity.id
        if (targetBlockPos != null) missile.targetBlockPos = targetBlockPos
        level.addFreshEntity(missile)

        level.playSound(
            null, pos,
            SoundEvents.SHULKER_SHOOT, SoundSource.BLOCKS,
            1.0f, 1.0f,
        )

        markChangedAndSync()
        return true
    }

    /** Player right-click insertion / removal at a specific slot index. Returns true if
     *  the interaction did something (so the block can return `SUCCESS`). */
    fun toggleSlot(player: Player, slot: Int, held: ItemStack): Boolean {
        if (slot !in items.indices) return false
        val current = items[slot]
        return if (current.isEmpty) {
            // Empty slot — try to insert from held.
            if (held.isEmpty || held.item != EKItems.MAGIC_MISSILE.get()) return false
            items[slot] = held.copyWithCount(1)
            if (!player.abilities.instabuild) held.shrink(1)
            markChangedAndSync()
            level?.playSound(
                null, blockPos,
                SoundEvents.BOTTLE_FILL_DRAGONBREATH, SoundSource.BLOCKS,
                0.8f, 1.0f,
            )
            true
        } else {
            // Occupied slot — pop the missile back out.
            if (!player.addItem(current)) {
                player.drop(current, false)
            }
            items[slot] = ItemStack.EMPTY
            markChangedAndSync()
            level?.playSound(
                null, blockPos,
                SoundEvents.BOTTLE_FILL_DRAGONBREATH, SoundSource.BLOCKS,
                0.8f, 1.0f,
            )
            true
        }
    }

    /** Mark the chunk dirty and push a BE-data packet to nearby clients so the
     *  [MagicMissileLauncherRenderer] sees the updated inventory immediately. Without
     *  this, vanilla only resyncs BE NBT on chunk reload, so the rendered missile
     *  overlay would lag behind the server inventory until the player walked away. */
    private fun markChangedAndSync() {
        setChanged()
        val lvl = level
        if (lvl is ServerLevel) {
            val pkt = ClientboundBlockEntityDataPacket.create(this) ?: return
            lvl.chunkSource.chunkMap
                .getPlayers(ChunkPos(blockPos), false)
                .forEach { it.connection.send(pkt) }
        }
    }


    override fun getContainerSize(): Int = SLOT_COUNT
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val out = ContainerHelper.removeItem(items, slot, amount)
        if (!out.isEmpty) markChangedAndSync()
        return out
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val out = ContainerHelper.takeItem(items, slot)
        markChangedAndSync()
        return out
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = stack
        if (stack.count > maxStackSize) stack.count = maxStackSize
        markChangedAndSync()
    }

    override fun getMaxStackSize(): Int = 1

    override fun stillValid(player: Player): Boolean {
        val lvl = level ?: return false
        if (lvl.getBlockEntity(blockPos) !== this) return false
        return player.distanceToSqr(
            blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5,
        ) <= 64.0
    }

    override fun clearContent() {
        items.replaceAll { ItemStack.EMPTY }
        markChangedAndSync()
    }

    override fun getSlotsForFace(side: Direction): IntArray = ALL_SLOTS_FOR_HOPPER

    /** Insertion is allowed from any face — a hopper, conveyor, or pipe on any side
     *  can feed the launcher. Lets you wedge the block into a tight build and still
     *  pipe missiles in from whichever direction has room. */
    override fun canPlaceItemThroughFace(
        slot: Int, stack: ItemStack, direction: Direction?,
    ): Boolean = canPlaceItem(slot, stack)

    /** Extraction allowed from any face — vanilla hoppers only pull from the block
     *  directly above them anyway, so any "side extraction" concern was hypothetical
     *  protection against an interaction the game can't actually express. Other
     *  automation (pipes, etc.) gets the same all-face permission as insertion. */
    override fun canTakeItemThroughFace(
        slot: Int, stack: ItemStack, direction: Direction,
    ): Boolean = true

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean =
        items[slot].isEmpty && stack.item == EKItems.MAGIC_MISSILE.get()


    override fun load(tag: CompoundTag) {
        super.load(tag)
        items.replaceAll { ItemStack.EMPTY }
        ContainerHelper.loadAllItems(tag, items)
        firingCooldown = tag.getInt(NBT_COOLDOWN)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        ContainerHelper.saveAllItems(tag, items)
        tag.putInt(NBT_COOLDOWN, firingCooldown)
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    /** Staff of Command dispatch: fire one missile owned by the staff wielder, targeted
     *  at wherever the wielder's gaze lands. Bypasses [BlockStateProperties.POWERED] so
     *  the staff works independently of any redstone hookup, but resets [firingCooldown]
     *  so a powered launcher can't immediately fire a second shot on the same tick.
     *
     *  Entity-vs-point target: a block raycast picks the wall hit (or the 256-block
     *  reach end on a miss); an entity raycast over the same segment picks any entity
     *  in line of sight before the block. If an entity is in front of whatever the
     *  block ray hit, the missile locks onto it (tracks it as it moves); otherwise it
     *  flies to the fixed block-hit point. */
    override fun executeStaffCommand(player: Player, hostShip: Ship?, contextStack: ItemStack) {
        val server = level as? ServerLevel ?: return
        if (firingCooldown > 0) return
        val eye = player.getEyePosition(1f)
        val end = eye.add(player.getViewVector(1f).scale(STAFF_GAZE_REACH))
        val blockHit = server.clip(
            ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player),
        )
        val rayEnd = blockHit.location
        val entityHit = ProjectileUtil.getEntityHitResult(
            server, player, eye, rayEnd, AABB(eye, rayEnd).inflate(1.0),
        ) { e -> e.isAlive && e !== player && e.isPickable }

        // Dragon parts aren't in the level's chunked entity index — they live on
        // `EnderDragon.subEntities`, so `getEntityHitResult` skips them. Re-scan dragons
        // in the ray's AABB, clip against each part's bbox, but return the *parent
        // dragon* as the hit entity — the part's id isn't in the level index either, so
        // locking to it would un-resolve next tick; the missile's hit handler already
        // routes part collisions through the part's `hurt()` to the dragon.
        val dragonHit = raycastEnderDragon(server, eye, rayEnd, player)

        // Pick whichever of the regular hit and the dragon hit is closer along the ray.
        val chosenHit: EntityHitResult? = when {
            entityHit == null -> dragonHit
            dragonHit == null -> entityHit
            eye.distanceToSqr(entityHit.location) <= eye.distanceToSqr(dragonHit.location) -> entityHit
            else -> dragonHit
        }

        val targetEntity = chosenHit?.entity
        val targetPos = if (targetEntity != null) null else rayEnd
        // When the ray actually hit a block, also stamp the raycast's `blockPos` on the
        // missile so the air-check uses the correct coordinate space (shipyard for ship
        // blocks per VS2's mixed-frame clip result). The world `targetPos` still drives
        // flight — only the validity check needs the original block pos.
        val targetBlockPos = if (targetEntity == null && blockHit.type != net.minecraft.world.phys.HitResult.Type.MISS) {
            blockHit.blockPos
        } else null
        if (fireOne(
                server, blockPos, blockState,
                owner = player,
                targetPos = targetPos,
                targetEntity = targetEntity,
                targetBlockPos = targetBlockPos,
            )
        ) {
            firingCooldown = FIRE_PERIOD_TICKS
        }
    }

    /** Clip the gaze ray against every EnderDragon's `subEntities` in the search box.
     *  Returns the closest hit wrapped around the *parent dragon* (not the part itself),
     *  or null if no dragon is in range or no part intersects. Same `0.3` inflate
     *  [ProjectileUtil.getEntityHitResult] uses, so the hit radius matches the regular
     *  entity raycast. */
    private fun raycastEnderDragon(
        server: ServerLevel, eye: Vec3, rayEnd: Vec3, exclude: Entity,
    ): EntityHitResult? {
        val box = AABB(eye, rayEnd).inflate(1.0)
        val dragons = server.getEntitiesOfClass(EnderDragon::class.java, box)
        if (dragons.isEmpty()) return null

        var bestDragon: EnderDragon? = null
        var bestPos: Vec3? = null
        var bestDistSq = Double.MAX_VALUE
        for (dragon in dragons) {
            if (dragon === exclude || !dragon.isAlive) continue
            for (part in dragon.subEntities) {
                if (!part.isPickable) continue
                val clip = part.boundingBox.inflate(0.3).clip(eye, rayEnd)
                if (clip.isPresent) {
                    val distSq = eye.distanceToSqr(clip.get())
                    if (distSq < bestDistSq) {
                        bestDragon = dragon
                        bestPos = clip.get()
                        bestDistSq = distSq
                    }
                }
            }
        }
        return if (bestDragon != null && bestPos != null) EntityHitResult(bestDragon, bestPos) else null
    }

    companion object {
        const val SLOT_COUNT: Int = 16

        /** Ticks between consecutive shots while powered — 20 ticks ≈ 1 second. */
        const val FIRE_PERIOD_TICKS: Int = 20

        /** Spawn distance (blocks) from the slot's pixel along the facing axis. Far
         *  enough that the missile entity clears the launcher's own hull on its first
         *  tick instead of self-colliding. */
        private const val SPAWN_OUTWARD_OFFSET: Double = 0.1

        /** How far the staff's gaze raycast reaches on a [executeStaffCommand]
         *  dispatch. Matches the staff's beam reach so the visual + the missile
         *  target agree even on a long-distance miss. */
        private const val STAFF_GAZE_REACH: Double = 256.0

        private const val NBT_COOLDOWN = "FiringCooldown"

        private val ALL_SLOTS_FOR_HOPPER: IntArray = IntArray(SLOT_COUNT) { it }
    }
}
