package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.construction.BuildJobs
import org.shipwrights.enderkinesis.construction.PlanAnchor
import org.shipwrights.enderkinesis.construction.PlanRef
import org.shipwrights.enderkinesis.construction.TablePlacement
import org.shipwrights.enderkinesis.construction.toPlanRef
import org.shipwrights.enderkinesis.construction.toTablePlacement
import org.shipwrights.enderkinesis.construction.toTag
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import java.util.UUID

/** One plan and its placement. The client copy draws the plan ghost. */
class DraftingTableBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.DRAFTING_TABLE.get(), pos, state) {

    /** Moves with the saved data, so a VS2 assembly that moves the table keeps its job and Clawhods. */
    var tableId: UUID = UUID.randomUUID()
        private set
    var plan: PlanRef? = null
        private set
    var placement: TablePlacement = TablePlacement.NONE
        private set
    /** Ghost display only: draw just the cells that can be placed now. Synced to clients. */
    var buildableOnly: Boolean = false
        private set
    private var anchorCache: PlanAnchor? = null
    private var suppliesTick = -1L
    private var suppliesCount = 0

    /** The supply count for [tick], computed once per game tick by [count]. */
    fun suppliesAt(tick: Long, count: () -> Int): Int {
        if (suppliesTick != tick) {
            suppliesCount = count()
            suppliesTick = tick
        }
        return suppliesCount
    }

    /** The plan's anchor here, kept until the plan or placement changes, so the ghost does not allocate per frame. */
    fun anchor(): PlanAnchor? {
        val ref = plan ?: return null
        val level = level ?: return null
        return anchorCache ?: placement.anchor(level.dimension(), blockPos, ref.size).also { anchorCache = it }
    }

    /** A player placed this table: copied data must not share the first table's job or Clawhods. */
    fun renewId() {
        tableId = UUID.randomUUID()
        setChanged()
    }

    fun hold(plan: PlanRef?, placement: TablePlacement) {
        this.plan = plan
        this.placement = placement
        changed()
    }

    fun setBuildableOnly(value: Boolean) {
        buildableOnly = value
        changed()
    }

    fun move(placement: TablePlacement) {
        this.placement = placement
        changed()
    }

    private fun changed() {
        anchorCache = null
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        anchorCache = null
        if (!level.isClientSide) {
            rebind()
        } else if (this !in ClientDraftingTables.loaded) {
            ClientDraftingTables.loaded.add(this)
        }
    }

    override fun setRemoved() {
        super.setRemoved()
        if (level?.isClientSide == true) ClientDraftingTables.loaded.remove(this)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        if (tag.hasUUID(TABLE_ID)) tableId = tag.getUUID(TABLE_ID)
        plan = if (tag.contains(PLAN, Tag.TAG_COMPOUND.toInt())) tag.getCompound(PLAN).toPlanRef() else null
        placement = tag.getCompound(PLACEMENT).toTablePlacement()
        buildableOnly = tag.getBoolean(BUILDABLE_ONLY)
        anchorCache = null
        // A move that loads the saved data into a placed table sets the level first.
        if (level?.isClientSide == false) rebind()
    }

    private fun rebind() {
        val server = level as? ServerLevel ?: return
        // Worldgen threads can place block entities; jobs live on the server thread only.
        if (server.server.isSameThread) BuildJobs.tableLoaded(server, blockPos, this)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putUUID(TABLE_ID, tableId)
        plan?.let { tag.put(PLAN, it.toTag()) }
        tag.put(PLACEMENT, placement.toTag())
        tag.putBoolean(BUILDABLE_ONLY, buildableOnly)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    private companion object {
        const val TABLE_ID = "TableId"
        const val PLAN = "Plan"
        const val PLACEMENT = "Placement"
        const val BUILDABLE_ONLY = "BuildableOnly"
    }
}

/** The drafting tables loaded on the client, for the plan ghosts. Client thread only; a list, so a frame walks it by index. */
object ClientDraftingTables {
    val loaded: MutableList<DraftingTableBlockEntity> = ArrayList()
}
