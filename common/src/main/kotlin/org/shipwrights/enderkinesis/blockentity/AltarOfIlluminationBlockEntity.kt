package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Inscription
import org.shipwrights.enderkinesis.spell.Spell

/**
 * Glyphs never come back out; only an opposite undoes one. The finish takes its ink bottle
 * only when the scroll rises ([inkDropPending], a scheduled block tick).
 */
// Not a Container: hoppers and other automation must find no inventory here.
class AltarOfIlluminationBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ALTAR_OF_ILLUMINATION.get(), pos, state) {

    /** For ANNIHILATE, [after] is what stays in place (half of a broken fusion), or null.
     *  [origin] is the glyph index at the event; later events move [index]. */
    class Event(
        val kind: Kind, val time: Long,
        val shard: Element? = null, val index: Int = -1, val before: Element? = null, val after: Element? = null,
        val glyphs: List<Element> = emptyList(), val inkBefore: Int = 0, val paper: ItemStack = ItemStack.EMPTY,
        val origin: Int = index,
    ) {
        enum class Kind { FUSE, ANNIHILATE, FINISH }

        /** The glyphs after this one move up a place. */
        val removes: Boolean get() = kind == Kind.ANNIHILATE && after == null

        fun movedTo(newIndex: Int) = Event(kind, time, shard, newIndex, before, after, glyphs, inkBefore, paper, origin)

        fun save(): CompoundTag = CompoundTag().also { tag ->
            tag.putString("Kind", kind.name)
            tag.putLong("Time", time)
            shard?.let { tag.putString("Shard", it.id) }
            tag.putInt("Index", index)
            before?.let { tag.putString("Before", it.id) }
            after?.let { tag.putString("After", it.id) }
            tag.put("Glyphs", ListTag().apply { glyphs.forEach { add(StringTag.valueOf(it.id)) } })
            tag.putInt("InkBefore", inkBefore)
            if (!paper.isEmpty) tag.put("Paper", paper.save(CompoundTag()))
            tag.putInt("Origin", origin)
        }

        companion object {
            fun load(tag: CompoundTag): Event? {
                val kind = runCatching { Kind.valueOf(tag.getString("Kind")) }.getOrNull() ?: return null
                fun element(key: String) = if (tag.contains(key)) Element.byId(tag.getString(key)) else null
                return Event(
                    kind, tag.getLong("Time"), element("Shard"), tag.getInt("Index"), element("Before"), element("After"),
                    tag.getList("Glyphs", Tag.TAG_STRING.toInt()).mapNotNull { Element.byId(it.asString) }, tag.getInt("InkBefore"),
                    if (tag.contains("Paper", Tag.TAG_COMPOUND.toInt())) ItemStack.of(tag.getCompound("Paper")) else ItemStack.EMPTY,
                    if (tag.contains("Origin")) tag.getInt("Origin") else tag.getInt("Index"),
                )
            }
        }
    }

    var blank: ItemStack = ItemStack.EMPTY
        private set

    var glyphs: List<Element> = emptyList()
        private set

    var ready: ItemStack = ItemStack.EMPTY
        private set

    /** Oldest first. */
    var events: List<Event> = emptyList()
        private set

    var inkDropPending: Boolean = false
        private set

    val hasScroll: Boolean get() = !blank.isEmpty

    /** Glyphs already on the altar stay. */
    fun placeBlank(paper: ItemStack) {
        blank = paper.copyWithCount(1)
        changed()
    }

    /** Null when the scroll is full. */
    fun inscribe(shard: Element): Inscription.Outcome? {
        val before = glyphs
        val result = Inscription.inscribe(glyphs, shard) ?: return null
        glyphs = result.glyphs
        val time = level?.gameTime ?: 0L
        when (val outcome = result.outcome) {
            is Inscription.Outcome.Fused ->
                record(Event(Event.Kind.FUSE, time, shard, result.index, before[result.index], outcome.into))
            is Inscription.Outcome.Annihilated ->
                record(Event(Event.Kind.ANNIHILATE, time, shard, result.index, outcome.hit, outcome.left))
            Inscription.Outcome.Inscribed -> {}
        }
        changed()
        return result.outcome
    }

    /** The ink goes later, when the scroll has risen ([riseTicks] from now). */
    fun finish(ink: Int) {
        check(glyphs.isNotEmpty() && ready.isEmpty)
        val scroll = ItemStack(EKItems.MYSTIC_SCROLL.get())
        scroll.orCreateTag.put(Spell.NBT_GLYPHS, Spell(glyphs).save())
        record(Event(Event.Kind.FINISH, level?.gameTime ?: 0L, glyphs = glyphs, inkBefore = ink, paper = blank.copy()))
        ready = scroll
        blank = ItemStack.EMPTY
        glyphs = emptyList()
        inkDropPending = true
        changed()
    }

    /** A new event on a glyph ends the older animation of that glyph. A finish clears all
     *  other events, and stays while its scroll waits. */
    private fun record(new: Event) {
        val kept = ArrayList<Event>()
        for (e in events) {
            if (new.kind == Event.Kind.FINISH) break
            if (e.kind == Event.Kind.FINISH) {
                if (!ready.isEmpty) kept += e
                continue
            }
            if (new.time - e.time > EVENT_TICKS) continue
            when {
                e.index == new.index -> {}
                new.removes && e.index > new.index -> kept += e.movedTo(e.index - 1)
                else -> kept += e
            }
        }
        kept += new
        events = kept
    }

    fun takeReady(): ItemStack {
        val out = ready
        ready = ItemStack.EMPTY
        changed()
        return out
    }

    /** True only on the first call after a finish. */
    fun takeInkDrop(): Boolean {
        if (!inkDropPending) return false
        inkDropPending = false
        setChanged()
        return true
    }

    /** Inscribed shards are spent and go with the altar. */
    fun drops(): List<ItemStack> = listOfNotNull(blank.copy().takeIf { hasScroll }, ready.copy().takeIf { !ready.isEmpty })

    private fun changed() {
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        blank = if (tag.contains(NBT_BLANK, Tag.TAG_COMPOUND.toInt())) ItemStack.of(tag.getCompound(NBT_BLANK)) else ItemStack.EMPTY
        glyphs = Spell.load(tag, Spell.NBT_GLYPHS)?.glyphs ?: emptyList()
        ready = if (tag.contains(NBT_READY, Tag.TAG_COMPOUND.toInt())) ItemStack.of(tag.getCompound(NBT_READY)) else ItemStack.EMPTY
        events = tag.getList(NBT_EVENTS, Tag.TAG_COMPOUND.toInt()).mapNotNull { Event.load(it as CompoundTag) }
        inkDropPending = tag.getBoolean(NBT_INK_DROP)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!blank.isEmpty) tag.put(NBT_BLANK, blank.save(CompoundTag()))
        tag.put(Spell.NBT_GLYPHS, Spell(glyphs).save())
        if (!ready.isEmpty) tag.put(NBT_READY, ready.save(CompoundTag()))
        if (events.isNotEmpty()) tag.put(NBT_EVENTS, ListTag().apply { events.forEach { add(it.save()) } })
        if (inkDropPending) tag.putBoolean(NBT_INK_DROP, true)
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private const val NBT_BLANK = "Blank"
        private const val NBT_READY = "Ready"
        private const val NBT_EVENTS = "Events"
        /** Longer than any shard animation plays. */
        private const val EVENT_TICKS = 60
        private const val NBT_INK_DROP = "InkDropPending"

        /** Finish animation beats in ticks. The renderer uses the same values. */
        const val DIVE_STAGGER = 5
        const val DIVE_TICKS = 11
        const val RISE_PAUSE = 8

        fun riseTicks(glyphs: Int): Int = (glyphs - 1).coerceAtLeast(0) * DIVE_STAGGER + DIVE_TICKS + RISE_PAUSE
    }
}
