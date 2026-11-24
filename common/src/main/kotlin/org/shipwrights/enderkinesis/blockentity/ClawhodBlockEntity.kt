@file:OptIn(org.valkyrienskies.core.api.util.GameTickOnly::class, org.valkyrienskies.core.api.VsBeta::class)

package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.construction.CLAWHOD_HANDS
import org.shipwrights.enderkinesis.construction.ClawhodWorker
import org.shipwrights.enderkinesis.construction.HAND_SLOTS
import org.shipwrights.enderkinesis.construction.HandMotion
import org.shipwrights.enderkinesis.construction.NO_POS
import org.shipwrights.enderkinesis.construction.isClawhodTool
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.valkyrienskies.core.api.ships.Ship
import java.util.UUID

class ClawhodBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.CLAWHOD.get(), pos, state), Commandable {

    /** The id the build job reserves cells by. VS2 copies it with the NBT when the block moves. */
    var workerId: UUID = UUID.randomUUID()
        private set

    private val hands: Array<NonNullList<ItemStack>> = Array(CLAWHOD_HANDS) { NonNullList.withSize(HAND_SLOTS, ItemStack.EMPTY) }

    /** Server only, made on the first tick. */
    internal var worker: ClawhodWorker? = null
        private set

    /** Where each hand flies to, as a packed block pos in its own frame (shipyard for ship blocks), or [NO_POS] for the gem. */
    private val handTargets = LongArray(CLAWHOD_HANDS) { NO_POS }

    /** One of what each hand carries, for clients to draw; a block update goes out when it changes. */
    private val handShown: Array<ItemStack> = Array(CLAWHOD_HANDS) { ItemStack.EMPTY }

    internal fun shownItem(index: Int): ItemStack = handShown[index]

    /** True when the shown item is the hand's working tool, drawn gripped; a carried tool shows as a held item. */
    private val handShownTool = BooleanArray(CLAWHOD_HANDS)

    internal fun shownIsTool(index: Int): Boolean = handShownTool[index]

    /** The slot of hand [index]'s working tool, or -1; a carried tool is not one. */
    internal fun workToolSlot(index: Int): Int = worker?.workSlotOf(index) ?: hands[index].indexOfFirst(::isClawhodTool)

    /** True while the hand punches the block at its target. */
    private val handPunch = BooleanArray(CLAWHOD_HANDS)

    /** Client: each hand's drawn motion, and the frame time it was last drawn at. Made on first draw, so server copies skip it. */
    private var clientHandsMade: Array<HandMotion>? = null

    internal fun clientHands(): Array<HandMotion> =
        clientHandsMade ?: Array(CLAWHOD_HANDS) { HandMotion() }.also { clientHandsMade = it }
    internal var clientTime = Double.NaN
    internal var lastArcTick = Long.MIN_VALUE

    internal fun hand(index: Int): NonNullList<ItemStack> = hands[index]

    internal fun handTarget(index: Int): Long = handTargets[index]

    internal fun handPunches(index: Int): Boolean = handPunch[index]

    /** The entity id of the loose item the hand's target is (it grabs it, with no stand-off), or [NO_LOOSE]. */
    private val handLoose = IntArray(CLAWHOD_HANDS) { NO_LOOSE }

    internal fun handLooseId(index: Int): Int = handLoose[index]

    /** Server: a hand flies to [packed] (or home with [NO_POS]) and punches it or not; one block update per change. */
    internal fun setHandTarget(index: Int, packed: Long, punch: Boolean = false, looseId: Int = NO_LOOSE) {
        if (handTargets[index] == packed && handPunch[index] == punch && handLoose[index] == looseId) return
        handTargets[index] = packed
        handPunch[index] = punch
        handLoose[index] = looseId
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    internal fun ensureWorker(): ClawhodWorker = worker ?: ClawhodWorker(this).also { worker = it }

    /** Call after any change to the hand slots. */
    internal fun handsChanged() {
        setChanged()
        if (refreshShown()) level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    /** True when what a hand shows changed. The working tool shows first, then the first other item. */
    private fun refreshShown(): Boolean {
        var changed = false
        for (h in hands.indices) {
            val slot = workToolSlot(h)
            val tool = slot >= 0
            val first = if (tool) hands[h][slot] else hands[h].firstOrNull { !it.isEmpty } ?: ItemStack.EMPTY
            if (tool == handShownTool[h] && first.isEmpty == handShown[h].isEmpty &&
                ItemStack.isSameItemSameTags(first, handShown[h])
            ) {
                continue
            }
            handShown[h] = if (first.isEmpty) ItemStack.EMPTY else first.copyWithCount(1)
            handShownTool[h] = tool
            changed = true
        }
        return changed
    }

    /** Empties both hands and returns their items. */
    fun takeAllItems(): List<ItemStack> {
        val out = ArrayList<ItemStack>(CLAWHOD_HANDS * HAND_SLOTS)
        for (hand in hands) {
            for (i in 0 until hand.size) {
                if (!hand[i].isEmpty) out.add(hand[i])
                hand[i] = ItemStack.EMPTY
            }
        }
        if (out.isNotEmpty()) handsChanged()
        return out
    }

    // A drafting table recruits Clawhods itself; the staff right-click has no action here.
    override fun executeStaffCommand(player: Player, hostShip: Ship?, contextStack: ItemStack) = Unit

    override fun executeAimCommand(player: Player, hostShip: Ship?, contextStack: ItemStack) {
        val level = level as? ServerLevel ?: return
        worker?.stop(level, "a staff left-click stopped it")
    }

    override fun setRemoved() {
        (level as? ServerLevel)?.let { worker?.unload(it) }
        super.setRemoved()
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        tag.putUUID(NBT_WORKER, workerId)
        val list = ListTag()
        for (hand in hands) list.add(ContainerHelper.saveAllItems(CompoundTag(), hand))
        tag.put(NBT_HANDS, list)
        worker?.save(tag)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        if (tag.hasUUID(NBT_WORKER)) workerId = tag.getUUID(NBT_WORKER)
        val list = tag.getList(NBT_HANDS, Tag.TAG_COMPOUND.toInt())
        for (h in hands.indices) {
            hands[h].clear()
            if (h < list.size) ContainerHelper.loadAllItems(list.getCompound(h), hands[h])
        }
        if (ClawhodWorker.hasState(tag)) ensureWorker().load(tag)
        if (tag.contains(NBT_HANDS)) refreshShown()
        readTargets(tag)
    }

    // Clients draw only the hand targets.
    override fun getUpdateTag(): CompoundTag = CompoundTag().also { tag ->
        tag.put(NBT_TARGETS, LongArrayTag(handTargets.copyOf()))
        tag.putByteArray(NBT_PUNCH, ByteArray(CLAWHOD_HANDS) { if (handPunch[it]) 1 else 0 })
        tag.putIntArray(NBT_LOOSE, handLoose.copyOf())
        val shown = ListTag()
        for (stack in handShown) shown.add(stack.save(CompoundTag()))
        tag.put(NBT_SHOWN, shown)
        tag.putByteArray(NBT_SHOWN_TOOL, ByteArray(CLAWHOD_HANDS) { if (handShownTool[it]) 1 else 0 })
    }

    private fun readTargets(tag: CompoundTag) {
        if (!tag.contains(NBT_TARGETS)) return
        val targets = tag.getLongArray(NBT_TARGETS)
        for (i in handTargets.indices) handTargets[i] = targets.getOrElse(i) { NO_POS }
        val shown = tag.getList(NBT_SHOWN, Tag.TAG_COMPOUND.toInt())
        for (i in handShown.indices) handShown[i] = if (i < shown.size) ItemStack.of(shown.getCompound(i)) else ItemStack.EMPTY
        val shownTool = tag.getByteArray(NBT_SHOWN_TOOL)
        for (i in handShownTool.indices) handShownTool[i] = shownTool.getOrElse(i) { 0 } != 0.toByte()
        val punch = tag.getByteArray(NBT_PUNCH)
        for (i in handPunch.indices) handPunch[i] = punch.getOrElse(i) { 0 } != 0.toByte()
        val loose = tag.getIntArray(NBT_LOOSE)
        for (i in handLoose.indices) handLoose[i] = loose.getOrElse(i) { NO_LOOSE }
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        const val NO_LOOSE = -1
        private const val NBT_WORKER = "EkWorker"
        private const val NBT_HANDS = "EkHands"
        private const val NBT_TARGETS = "EkHandTargets"
        private const val NBT_PUNCH = "EkHandPunch"
        private const val NBT_LOOSE = "EkHandLoose"
        private const val NBT_SHOWN = "EkHandShown"
        private const val NBT_SHOWN_TOOL = "EkHandShownTool"
    }
}
