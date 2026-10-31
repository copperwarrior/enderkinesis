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
 * The Altar of Illumination's scroll: the paper it was made from and the glyphs inscribed on
 * it so far, and a finished scroll waiting to be taken ([ready]). The stored ink is the block's
 * `ink` state, so the basin's fill is a block model.
 *
 * Glyphs can never be taken back out. The only way to undo one is to add its opposite.
 *
 * What has just happened ([events]: fusions, annihilations, a finish) goes to clients with the
 * rest, for the altar's renderer to play out, each on its own, so several can play at once. A finished scroll rises out of the ink once its
 * glyphs have gone into it, and the bottle of ink it used is only taken then ([inkDropPending],
 * a scheduled block tick).
 */
// Not a Container: hoppers and other automation must find no inventory here.
class AltarOfIlluminationBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ALTAR_OF_ILLUMINATION.get(), pos, state) {

    /** What happened at the altar, at [time] (game ticks), for clients to animate.
     *
     * - A fusion: [shard] fused with [before], the glyph at [index], into [after].
     * - An annihilation: [shard] annihilated with [before], the glyph at [index]; [after] is
     *   what's left of it in its place (a broken fusion's other half), or null if it's gone.
     * - A finish: [glyphs] went into the scroll made from [paper], the basin holding
     *   [inkBefore] bottles.
     *
     * [origin] is the glyph's place when it happened; [index] where it is now, as later events
     * move it along. */
    class Event(
        val kind: Kind, val time: Long,
        val shard: Element? = null, val index: Int = -1, val before: Element? = null, val after: Element? = null,
        val glyphs: List<Element> = emptyList(), val inkBefore: Int = 0, val paper: ItemStack = ItemStack.EMPTY,
        val origin: Int = index,
    ) {
        enum class Kind { FUSE, ANNIHILATE, FINISH }

        /** An annihilation that left nothing in the glyph's place: those after it move up. */
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

    /** The paper the blank scroll was made from, or empty when there's no scroll. */
    var blank: ItemStack = ItemStack.EMPTY
        private set

    var glyphs: List<Element> = emptyList()
        private set

    /** A finished scroll, floating over the altar until someone takes it; empty when none. */
    var ready: ItemStack = ItemStack.EMPTY
        private set

    /** What has just happened, oldest first. */
    var events: List<Event> = emptyList()
        private set

    /** The finish's bottle of ink is still to be taken from the basin. */
    var inkDropPending: Boolean = false
        private set

    val hasScroll: Boolean get() = !blank.isEmpty

    /** Lays down the paper; glyphs already gathered on the altar stay. */
    fun placeBlank(paper: ItemStack) {
        blank = paper.copyWithCount(1)
        changed()
    }

    /** Resolves [shard] against the glyphs; null when the scroll is full. */
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

    /** Finishes the scroll with the basin holding [ink] bottles, clearing the altar: the scroll
     *  waits over it ([ready]) and the ink is taken once it has risen ([riseTicks] from now).
     *  Call only with at least one glyph and no scroll already waiting. */
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

    /** Adds [new] to what's happening, keeping the rest in step with the glyphs: a glyph that
     *  [new] changes or takes away ends its own animation, and those after a glyph it takes
     *  away move up a place. A finish clears the altar, and everything but itself with it. Old
     *  events go, but a finish stays while its scroll waits. */
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

    /** Takes the waiting scroll. */
    fun takeReady(): ItemStack {
        val out = ready
        ready = ItemStack.EMPTY
        changed()
        return out
    }

    /** Whether the finish's ink was still to be taken; it is now. */
    fun takeInkDrop(): Boolean {
        if (!inkDropPending) return false
        inkDropPending = false
        setChanged()
        return true
    }

    /** The paper and any waiting scroll. Inscribed shards are spent and lost with the altar. */
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
        /** How long a shard's animation is kept: longer than any plays. */
        private const val EVENT_TICKS = 60
        private const val NBT_INK_DROP = "InkDropPending"

        /** The finish animation's beats, in ticks: one glyph after another dropping into the
         *  ink, each taking [DIVE_TICKS], [DIVE_STAGGER] apart; then, [RISE_PAUSE] after the
         *  last, the scroll rising. Shared with the renderer. */
        const val DIVE_STAGGER = 5
        const val DIVE_TICKS = 11
        const val RISE_PAUSE = 8

        /** Ticks from a finish of [glyphs] glyphs to the scroll rising. */
        fun riseTicks(glyphs: Int): Int = (glyphs - 1).coerceAtLeast(0) * DIVE_STAGGER + DIVE_TICKS + RISE_PAUSE
    }
}
