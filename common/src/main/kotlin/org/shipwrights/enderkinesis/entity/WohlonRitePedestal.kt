package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractCandleBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.phys.AABB
import org.shipwrights.enderkinesis.block.WohlonCandleRites
import org.shipwrights.enderkinesis.block.WohlonStaffRites
import org.shipwrights.enderkinesis.registry.EKEntities
import org.shipwrights.enderkinesis.registry.EKSounds

class WohlonRitePedestal(type: EntityType<out WohlonRitePedestal>, level: Level) : Entity(type, level) {

    enum class Stage { WAITING, ABSORBING, CLOSING }

    private var candle: BlockPos = BlockPos.ZERO
    private var age = 0
    private var stageAge = 0
    /** Server only; the client sees the first one as [sacrifice] and the count as [filledSlots]. */
    private val offerings = ArrayList<ItemStack>()

    var rite: WohlonCandleRites.Rite
        get() = WohlonCandleRites.Rite.entries[entityData.get(DATA_RITE).toInt()]
        private set(value) = entityData.set(DATA_RITE, value.ordinal.toByte())

    var stage: Stage
        get() = Stage.entries[entityData.get(DATA_STAGE).toInt()]
        private set(value) = entityData.set(DATA_STAGE, value.ordinal.toByte())

    val sacrifice: ItemStack get() = entityData.get(DATA_SACRIFICE)

    /** The staff a Rite of the Staff makes, once its gem is in; empty for the other rites. */
    val staffResult: ItemStack get() = entityData.get(DATA_RESULT)
    val staffColour: Int get() = entityData.get(DATA_COLOUR)
    val staffBodyColour: Int get() = entityData.get(DATA_BODY_COLOUR)
    val staffSlots: Int get() = entityData.get(DATA_SLOTS).toInt()
    val filledSlots: Int get() = entityData.get(DATA_FILLED).toInt()

    /** The staff recipe's advancements, parsed on sync so the client does not parse each frame. */
    var staffAdvancements: List<ResourceLocation> = emptyList()
        private set

    /** Client tick count when [stage] became [Stage.CLOSING]; the visuals fade from there. */
    var closingTick: Int = -1
        private set

    constructor(level: ServerLevel, candle: BlockPos, rite: WohlonCandleRites.Rite) :
        this(EKEntities.WOHLON_RITE_PEDESTAL.get(), level) {
        this.candle = candle.immutable()
        this.rite = rite
        setPos(candle.x + 0.5, candle.y.toDouble(), candle.z + 0.5)
    }

    override fun defineSynchedData() {
        entityData.define(DATA_RITE, 0)
        entityData.define(DATA_STAGE, 0)
        entityData.define(DATA_SACRIFICE, ItemStack.EMPTY)
        entityData.define(DATA_RESULT, ItemStack.EMPTY)
        entityData.define(DATA_COLOUR, 0)
        entityData.define(DATA_BODY_COLOUR, WohlonStaffRites.NO_BODY_COLOUR)
        entityData.define(DATA_SLOTS, 0)
        entityData.define(DATA_FILLED, 0)
        entityData.define(DATA_ADVANCEMENTS, "")
    }

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (key == DATA_STAGE && level().isClientSide && stage == Stage.CLOSING) closingTick = tickCount
        if (key == DATA_ADVANCEMENTS) {
            staffAdvancements = entityData.get(DATA_ADVANCEMENTS).split(' ').filter { it.isNotEmpty() }.mapNotNull(ResourceLocation::tryParse)
        }
    }

    override fun tick() {
        super.tick()
        val server = level() as? ServerLevel ?: return
        age++
        stageAge++
        when (stage) {
            Stage.WAITING -> tickWaiting(server)
            Stage.ABSORBING -> if (stageAge >= ABSORB_TICKS) finish(server)
            Stage.CLOSING -> if (stageAge >= CLOSE_TICKS) discard()
        }
    }

    // Each offering taken starts the wait again, so a rite with many slots does not run out of time.
    private fun tickWaiting(server: ServerLevel) {
        if (!WohlonCandleRites.isLitCenter(server.getBlockState(candle))) {
            // Snuffing is how a staff rite is cancelled, and its offerings burn with it.
            if (rite == WohlonCandleRites.Rite.STAFF) offerings.clear()
            close(server, fizzle = true)
        } else if (stageAge >= WAIT_TICKS) {
            close(server, fizzle = true)
        }
    }

    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        if (stage != Stage.WAITING) return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        if (held.isEmpty && !player.abilities.mayBuild) return InteractionResult.PASS
        // The client does not hold the staff recipes; claim the click so the held item does not use itself.
        val server = level() as? ServerLevel ?: return InteractionResult.SUCCESS
        if (held.isEmpty) {
            // The pedestal covers the candle from above, so it takes the empty-hand snuff that the candle would.
            val state = server.getBlockState(candle)
            if (WohlonCandleRites.isLitCenter(state)) AbstractCandleBlock.extinguish(player, state, server, candle)
            return InteractionResult.CONSUME
        }
        if (age < RISE_TICKS || !rite.wants(offerings, held)) return InteractionResult.PASS
        val recipe = if (rite == WohlonCandleRites.Rite.STAFF) WohlonCandleRites.staffRecipe(offerings) else null
        if (recipe != null && player is ServerPlayer && !recipe.isKnownBy(player)) {
            player.displayClientMessage(Component.translatable(MISSING_KNOWLEDGE), true)
            return InteractionResult.CONSUME
        }
        accept(server, if (player.abilities.instabuild) held.copyWithCount(1) else held.split(1))
        return InteractionResult.CONSUME
    }

    private fun accept(server: ServerLevel, stack: ItemStack) {
        offerings.add(stack)
        syncOfferings()
        stageAge = 0
        if (rite.isComplete(offerings)) stage = Stage.ABSORBING
        server.playSound(null, x, cradleY(), z, EKSounds.WOHLON_RITUAL_HEART_ARRIVE.get(), SoundSource.BLOCKS, 1.5f, 1f)
    }

    private fun syncOfferings() {
        entityData.set(DATA_SACRIFICE, offerings.firstOrNull()?.copy() ?: ItemStack.EMPTY)
        entityData.set(DATA_FILLED, maxOf(offerings.size - 1, 0).toByte())
        val recipe = if (rite == WohlonCandleRites.Rite.STAFF) WohlonCandleRites.staffRecipe(offerings) else null
        entityData.set(DATA_RESULT, recipe?.let { ItemStack(it.result) } ?: ItemStack.EMPTY)
        entityData.set(DATA_COLOUR, recipe?.colour ?: 0)
        entityData.set(DATA_BODY_COLOUR, recipe?.bodyColour ?: WohlonStaffRites.NO_BODY_COLOUR)
        entityData.set(DATA_SLOTS, (recipe?.slots?.size ?: 0).toByte())
        entityData.set(DATA_ADVANCEMENTS, recipe?.advancements?.joinToString(" ") ?: "")
    }

    private fun finish(server: ServerLevel) {
        val made = rite.resolve(server, candle, offerings)
        if (made == null) {
            dropOfferings(server)
        } else {
            offerings.clear()
            if (!made.isEmpty) drop(server, made)
        }
        close(server, fizzle = made == null)
    }

    private fun dropOfferings(server: ServerLevel) {
        for (stack in offerings) drop(server, stack)
        offerings.clear()
    }

    private fun drop(server: ServerLevel, stack: ItemStack) {
        val item = ItemEntity(server, x, cradleY(), z, stack)
        item.setDefaultPickUpDelay()
        server.addFreshEntity(item)
    }

    private fun close(server: ServerLevel, fizzle: Boolean) {
        if (fizzle) dropOfferings(server)
        entityData.set(DATA_SACRIFICE, ItemStack.EMPTY)
        val state = server.getBlockState(candle)
        if (state.hasProperty(CandleBlock.LIT) && state.getValue(CandleBlock.LIT)) {
            server.setBlock(candle, state.setValue(CandleBlock.LIT, false), 11)
        }
        if (fizzle) {
            server.playSound(null, x, cradleY(), z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1f, 1f)
        } else {
            server.playSound(null, x, cradleY(), z, EKSounds.WOHLON_RITUAL_COMPLETE.get(), SoundSource.BLOCKS, 2f, 1f)
        }
        stage = Stage.CLOSING
        stageAge = 0
    }

    private fun cradleY(): Double = y + CRADLE_HEIGHT

    // The box is the offering zone over the cradle. It starts above the candle so the candle stays clickable from the side.
    override fun makeBoundingBox(): AABB = super.makeBoundingBox().move(0.0, RECEIVE_BOTTOM, 0.0)

    override fun isPickable(): Boolean = !isRemoved
    override fun skipAttackInteraction(entity: Entity): Boolean = true
    override fun hurt(source: DamageSource, amount: Float): Boolean = false
    override fun isPushable(): Boolean = false
    override fun isNoGravity(): Boolean = true

    override fun addAdditionalSaveData(tag: CompoundTag) {
        tag.putLong("candle", candle.asLong())
        tag.putByte("rite", rite.ordinal.toByte())
        tag.putByte("stage", stage.ordinal.toByte())
        tag.putInt("age", age)
        tag.putInt("stageAge", stageAge)
        val list = ListTag()
        for (stack in offerings) list.add(stack.save(CompoundTag()))
        tag.put("offerings", list)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        candle = BlockPos.of(tag.getLong("candle"))
        rite = WohlonCandleRites.Rite.entries[tag.getByte("rite").toInt().coerceIn(0, WohlonCandleRites.Rite.entries.size - 1)]
        stage = Stage.entries[tag.getByte("stage").toInt().coerceIn(0, Stage.entries.size - 1)]
        age = tag.getInt("age")
        stageAge = tag.getInt("stageAge")
        offerings.clear()
        val list = tag.getList("offerings", Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) ItemStack.of(list.getCompound(i)).takeUnless { it.isEmpty }?.let(offerings::add)
        syncOfferings()
    }

    companion object {
        const val ID_PATH = "wohlon_rite_pedestal"
        private const val MISSING_KNOWLEDGE = "message.enderkinesis.wohlon_staff_rite.missing_knowledge"

        const val RISE_TICKS = 30
        const val WAIT_TICKS = 20 * 20
        const val ABSORB_TICKS = 30
        const val CLOSE_TICKS = 40
        const val CRADLE_HEIGHT = 1.35
        private const val RECEIVE_BOTTOM = 0.5

        private val DATA_RITE: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.BYTE)
        private val DATA_STAGE: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.BYTE)
        private val DATA_SACRIFICE: EntityDataAccessor<ItemStack> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.ITEM_STACK)
        private val DATA_RESULT: EntityDataAccessor<ItemStack> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.ITEM_STACK)
        private val DATA_COLOUR: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.INT)
        private val DATA_BODY_COLOUR: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.INT)
        private val DATA_SLOTS: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.BYTE)
        private val DATA_FILLED: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.BYTE)
        private val DATA_ADVANCEMENTS: EntityDataAccessor<String> =
            SynchedEntityData.defineId(WohlonRitePedestal::class.java, EntityDataSerializers.STRING)
    }
}
