package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.util.ServerTicks
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import java.util.concurrent.ConcurrentHashMap

/** Phys thread queues hits via [queueHit]; the game-thread [serverTick] drains them and calls Level.explode. */
class CrystalExplosiveBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.CRYSTAL_EXPLOSIVE.get(), pos, state), Container {

    var crystal: ItemStack = ItemStack.EMPTY
        private set

    val armed: Boolean get() = !crystal.isEmpty

    @Volatile private var hitPending: Boolean = false

    private var registeredShipId: ShipId? = null

    private var registeredDim: DimensionId? = null

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level is ServerLevel) refreshRegistration(level)
    }

    fun serverTick(level: ServerLevel, pos: BlockPos) {
        refreshRegistration(level)
        if (!hitPending) return
        hitPending = false
        if (!armed) return
        // The crystal is spent, so the removal below must not drop it or start a chain.
        crystal = ItemStack.EMPTY
        // Before the removal: removing a ship's last block can drop the ship and its transform.
        val worldPos = worldCentre(level, pos)
        level.removeBlock(pos, false)
        level.explode(null, worldPos.x, worldPos.y, worldPos.z, EXPLOSION_RADIUS, Level.ExplosionInteraction.TNT)
    }

    /** Phys thread. */
    fun queueHit() {
        hitPending = true
    }

    /** Moves one crystal from [stack] into the empty slot. */
    fun insert(stack: ItemStack) {
        require(canPlaceItem(0, stack)) { "Crystal Explosive takes one end crystal into an empty slot" }
        setItem(0, stack.split(1))
    }

    fun take(): ItemStack = removeItemNoUpdate(0)

    override fun setChanged() {
        super.setChanged()
        val level = level ?: return
        if (!level.isClientSide) level.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun getContainerSize(): Int = 1
    override fun isEmpty(): Boolean = crystal.isEmpty
    override fun getItem(slot: Int): ItemStack = crystal
    override fun getMaxStackSize(): Int = 1
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = !armed && stack.`is`(Items.END_CRYSTAL)

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        if (amount <= 0 || !armed) return ItemStack.EMPTY
        return take()
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack {
        val out = crystal
        crystal = ItemStack.EMPTY
        if (!out.isEmpty) setChanged()
        return out
    }

    override fun setItem(slot: Int, stack: ItemStack) {
        crystal = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        setChanged()
    }

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() {
        crystal = ItemStack.EMPTY
        setChanged()
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        crystal = if (tag.contains(NBT_CRYSTAL)) ItemStack.of(tag.getCompound(NBT_CRYSTAL)) else ItemStack.EMPTY
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (armed) tag.put(NBT_CRYSTAL, crystal.save(CompoundTag()))
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    // Assembly can move the block onto a ship and back. Called from setLevel and serverTick, on the server thread.
    @OptIn(GameTickOnly::class)
    private fun refreshRegistration(level: ServerLevel) {
        val ship = level.getLoadedShipManagingPos(blockPos)
        val newShipId = ship?.id
        val newDim = if (ship == null) level.dimensionId else null
        if (newShipId == registeredShipId && newDim == registeredDim) return
        registeredShipId?.let { CrystalExplosiveCollisionRouter.removeShip(this, it) }
        registeredDim?.let { CrystalExplosiveCollisionRouter.removeWorld(this, it) }
        if (newShipId != null) {
            CrystalExplosiveCollisionRouter.addShip(this, newShipId)
        } else if (newDim != null) {
            CrystalExplosiveCollisionRouter.addWorld(this, newDim)
        }
        registeredShipId = newShipId
        registeredDim = newDim
    }

    override fun setRemoved() {
        super.setRemoved()
        registeredShipId?.let { CrystalExplosiveCollisionRouter.removeShip(this, it) }
        registeredDim?.let { CrystalExplosiveCollisionRouter.removeWorld(this, it) }
        registeredShipId = null
        registeredDim = null
    }

    companion object {
        private const val NBT_CRYSTAL = "Crystal"

        /** Vanilla end crystal explosion power. */
        const val EXPLOSION_RADIUS: Float = 6.0f

        const val CHAIN_EXPLOSION_RADIUS: Float = 5.0f

        /** Armed explosives removed this tick. An explosion calls `onRemove` before `wasExploded`, when the
         *  block entity is already gone, so `wasExploded` reads the armed state from here. */
        private val armedRemovals: MutableSet<GlobalPos> = ConcurrentHashMap.newKeySet()

        fun noteArmedRemoval(level: ServerLevel, pos: BlockPos) {
            val key = GlobalPos.of(level.dimension(), pos.immutable())
            armedRemovals.add(key)
            ServerTicks.runAfter(level.server, 1) { armedRemovals.remove(key) }
        }

        fun consumeArmedRemoval(level: ServerLevel, pos: BlockPos): Boolean =
            armedRemovals.remove(GlobalPos.of(level.dimension(), pos))

        /** Deferred one tick so a line of explosives goes off over consecutive ticks, not by recursion. */
        fun queueChainExplosion(level: ServerLevel, pos: BlockPos) {
            val worldPos = worldCentre(level, pos)
            ServerTicks.runAfter(level.server, 1) {
                level.explode(
                    null, worldPos.x, worldPos.y, worldPos.z, CHAIN_EXPLOSION_RADIUS, Level.ExplosionInteraction.TNT,
                )
            }
        }

        // Shipyard to world for the blast. Called from serverTick and wasExploded, on the server thread.
        @OptIn(GameTickOnly::class)
        private fun worldCentre(level: ServerLevel, pos: BlockPos): Vector3d {
            val worldPos = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            level.getLoadedShipManagingPos(pos)?.transform?.shipToWorld?.transformPosition(worldPos)
            return worldPos
        }

        fun clearSession() {
            armedRemovals.clear()
        }
    }
}
