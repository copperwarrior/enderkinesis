package org.shipwrights.enderkinesis.blockentity

import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.block.FountainBlessings
import org.shipwrights.enderkinesis.block.HeartOfTheWildBlock
import org.shipwrights.enderkinesis.client.FountainOfBlessingParticles
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/** Holds one offered item: it floats in the fountain, charges for [CHARGE_TICKS], and a right-click takes it back blessed. */
class FountainOfBlessingBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.FOUNTAIN_OF_BLESSING.get(), pos, state) {

    /** World Y of the water surface; the block top when there is no water. */
    var surfaceY: Double = pos.y + 1.0
        private set

    /** World Y the spring wells up from: the bottom of the water under the fountain, at most [MAX_SPRING_DEPTH] down. */
    var springY: Double = pos.y.toDouble()
        private set

    /** World Y of the bottom of the heart over the fountain, or NaN when there is none. */
    var heartY: Double = Double.NaN
        private set

    /** The offered item, synced to clients so the BER can float it. */
    var stored: ItemStack = ItemStack.EMPTY
        private set

    private var ruleId: ResourceLocation? = null
    private var chargeStart: Long = NOT_CHARGING

    /** Client only. The [Util.getMillis] time of the last charge-complete burst. */
    var blessStartMillis: Long = NOT_BLESSED
        private set

    // Client only. Phases advance by a rate each tick, so a rate change never jumps the angle.
    private var spin = 0f
    private var prevSpin = 0f
    private var rise = 0f
    private var prevRise = 0f

    fun spinAngle(partialTick: Float): Float = prevSpin + (spin - prevSpin) * partialTick

    fun risePhase(partialTick: Float): Float = prevRise + (rise - prevRise) * partialTick

    private val scan = BlockPos.MutableBlockPos()
    private var heightsKnown = false

    /** 0 with nothing stored, rising to 1 when the item is ready. */
    fun charge(level: Level, partialTick: Float = 0f): Float {
        if (stored.isEmpty || chargeStart == NOT_CHARGING) return 0f
        return ((level.gameTime - chargeStart + partialTick) / CHARGE_TICKS).coerceIn(0f, 1f)
    }

    /** Client-side guess for the hand swing; the server decides. */
    fun canInteract(held: ItemStack): Boolean = !stored.isEmpty || !held.isEmpty

    fun interact(level: ServerLevel, player: Player, hand: InteractionHand): InteractionResult {
        if (stored.isEmpty) {
            val held = player.getItemInHand(hand)
            val rule = FountainBlessings.find(held) ?: return InteractionResult.PASS
            stored = held.split(rule.count)
            ruleId = rule.id
            chargeStart = level.gameTime
            changed(level)
            return InteractionResult.CONSUME
        }
        if (charge(level) < 1f) return InteractionResult.CONSUME
        val item = takeStored()
        val rule = ruleId?.let(FountainBlessings::byId)
        if (rule == null) {
            // The datapack lost the rule while the item charged: hand it back unchanged.
            FountainBlessings.give(player, item)
        } else {
            FountainBlessings.release(level, rule, item, player, Vec3(blockPos.x + 0.5, surfaceY, blockPos.z + 0.5))
        }
        ruleId = null
        changed(level)
        return InteractionResult.CONSUME
    }

    fun takeStored(): ItemStack {
        val item = stored
        stored = ItemStack.EMPTY
        chargeStart = NOT_CHARGING
        return item
    }

    private fun changed(level: Level) {
        setChanged()
        level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    fun serverTick(level: ServerLevel) {
        if (!heightsKnown || level.gameTime % SURFACE_RECHECK_TICKS == 0L) updateHeights(level)
        if (!stored.isEmpty && chargeStart != NOT_CHARGING && level.gameTime - chargeStart == CHARGE_TICKS.toLong()) {
            level.blockEvent(blockPos, blockState.block, EVENT_READY, 0)
        }
    }

    fun clientTick(level: Level) {
        if (!heightsKnown || level.gameTime % SURFACE_RECHECK_TICKS == 0L) updateHeights(level)
        val c = charge(level)
        prevSpin = spin
        spin = (spin + SPIN_PER_TICK + SPIN_CHARGE_PER_TICK * c) % TWO_PI
        if (spin < prevSpin) prevSpin -= TWO_PI
        // Wrapping the rise would jump the noise field, so it only wraps far past any seam a player could watch.
        prevRise = rise
        rise = (rise + RISE_PER_TICK + RISE_CHARGE_PER_TICK * c) % RISE_WRAP
        if (rise < prevRise) prevRise = rise
        FountainOfBlessingParticles.tick(this, level)
    }

    private fun updateHeights(level: Level) {
        scan.set(blockPos)
        var top = blockPos.y - 1
        for (i in 0..MAX_SURFACE_SCAN) {
            if (!level.getFluidState(scan).`is`(FluidTags.WATER)) break
            top = scan.y
            scan.move(0, 1, 0)
        }
        // A still source block's surface sits 8/9 up the block.
        surfaceY = if (top < blockPos.y) blockPos.y + 1.0 else top + WATER_SURFACE_HEIGHT
        scan.set(blockPos)
        var bottom = blockPos.y
        for (i in 1..MAX_SPRING_DEPTH) {
            scan.move(0, -1, 0)
            if (!level.getFluidState(scan).`is`(FluidTags.WATER)) break
            bottom = scan.y
        }
        springY = bottom.toDouble()
        heartY = Double.NaN
        scan.set(blockPos.x, Math.floor(surfaceY).toInt() + 1, blockPos.z)
        for (i in 1..MAX_HEART_SCAN) {
            if (level.getBlockState(scan).block is HeartOfTheWildBlock) {
                heartY = scan.y.toDouble()
                break
            }
            scan.move(0, 1, 0)
        }
        heightsKnown = true
    }

    override fun triggerEvent(id: Int, param: Int): Boolean {
        if (id != EVENT_READY) return super.triggerEvent(id, param)
        val lvl = level
        if (lvl != null && lvl.isClientSide) {
            blessStartMillis = Util.getMillis()
            FountainOfBlessingParticles.burst(this, lvl)
        }
        // The server must return true, or vanilla does not send the event to clients.
        return true
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!stored.isEmpty) tag.put(TAG_ITEM, stored.save(CompoundTag()))
        ruleId?.let { tag.putString(TAG_RULE, it.toString()) }
        tag.putLong(TAG_CHARGE_START, chargeStart)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        stored = if (tag.contains(TAG_ITEM)) ItemStack.of(tag.getCompound(TAG_ITEM)) else ItemStack.EMPTY
        ruleId = if (tag.contains(TAG_RULE)) ResourceLocation.tryParse(tag.getString(TAG_RULE)) else null
        chargeStart = if (tag.contains(TAG_CHARGE_START)) tag.getLong(TAG_CHARGE_START) else NOT_CHARGING
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        const val EVENT_READY: Int = 1
        const val NOT_BLESSED: Long = Long.MIN_VALUE
        const val CHARGE_TICKS: Int = 100
        private const val NOT_CHARGING: Long = Long.MIN_VALUE

        private const val TAG_ITEM = "Item"
        private const val TAG_RULE = "Rule"
        private const val TAG_CHARGE_START = "ChargeStart"

        private const val SURFACE_RECHECK_TICKS = 40L
        private const val MAX_SURFACE_SCAN = 16
        private const val MAX_SPRING_DEPTH = 6
        private const val MAX_HEART_SCAN = 8
        private const val WATER_SURFACE_HEIGHT = 8.0 / 9.0
        private const val SPIN_PER_TICK = 0.02f
        private const val SPIN_CHARGE_PER_TICK = 0.03f
        private const val TWO_PI = (Math.PI * 2.0).toFloat()
        /** 2.6 per second at rest, the approved look. */
        private const val RISE_PER_TICK = 0.13f
        private const val RISE_CHARGE_PER_TICK = 0.05f
        private const val RISE_WRAP = 4096f
    }
}
