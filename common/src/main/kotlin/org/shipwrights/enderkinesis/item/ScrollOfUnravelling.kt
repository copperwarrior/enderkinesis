package org.shipwrights.enderkinesis.item

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.InteractionEvent
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import org.shipwrights.enderkinesis.dimension.SselithMadness
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.sselith.Sselith

/**
 * Server-side handler for the Scroll of Unravelling.
 *
 * Vanilla's `state.use(LecternBlock)` runs before `Item.useOn` on a
 * non-sneak right-click, and `LecternBlock.use` unconditionally opens the
 * book screen when `HAS_BOOK` is true — so the scroll's own `useOn` would
 * never get the chance. [InteractionEvent.RIGHT_CLICK_BLOCK] fires
 * *before* `state.use`, so intercepting here steps in front of the lectern
 * UI without needing a `LecternBlock.use` mixin.
 *
 * On a successful intercept (player holds the scroll, target is a
 * book-bearing lectern), the scroll always: consumes itself, applies +1
 * Sselith madness, emits the Cataloger's glyph column + page-turn SFX,
 * and shows the player an action-bar line confirming what happened.
 *
 * The decipher is stateless: each call runs the current page text through
 * [Sselith.translateToEnglish] at the holy-number fraction (34.5%) —
 * recognised Sselith tokens flip to English independently per call, and
 * already-English tokens pass through. Repeat uses converge to fully
 * English naturally, no per-book counters or NBT bookkeeping required.
 */
object ScrollOfUnravelling {

    /** Sselith holy fraction; see `sselith_dictionary.json#numerals.holyNumber`. */
    private const val HOLY_NUMBER_FRACTION = 0.345

    fun init() {
        InteractionEvent.RIGHT_CLICK_BLOCK.register(::onRightClickBlock)
    }

    private fun onRightClickBlock(
        player: Player,
        hand: InteractionHand,
        pos: BlockPos,
        face: Direction,
    ): EventResult {
        val held = player.getItemInHand(hand)
        if (held.item !is ScrollOfUnravellingItem) return EventResult.pass()
        val level = player.level() as? ServerLevel ?: return EventResult.pass()
        val state = level.getBlockState(pos)

        val rng = Random(level.gameTime xor pos.asLong() xor player.uuid.leastSignificantBits)
        when {
            state.block is net.minecraft.world.level.block.LecternBlock -> {
                val be = level.getBlockEntity(pos) as? LecternBlockEntity ?: return EventResult.pass()
                val book = be.book
                if (book.isEmpty) return EventResult.pass()
                if (revealPages(book, rng)) {
                    be.setChanged()
                    level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
                    // Comparator fires off the BE's page count, which doesn't care
                    // about specific block identity. Pass the actual block so vanilla
                    // and Sselith lecterns both notify correctly.
                    level.updateNeighbourForOutputSignal(pos, state.block)
                }
            }
            state.`is`(BlockTags.ALL_SIGNS) -> {
                val be = level.getBlockEntity(pos) as? SignBlockEntity ?: return EventResult.pass()
                if (revealSign(be, rng)) {
                    be.setChanged()
                    level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
                }
            }
            else -> return EventResult.pass()
        }

        // Particles + sound fire even with zero flips — the act of
        // breaking the seal is the same either way.
        spawnGlyphColumn(level, pos)
        level.playSound(
            null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS,
            1.0f, 0.7f + level.random.nextFloat() * 0.2f,
        )
        if (player is ServerPlayer) SselithMadness.addLevel(player, 1)
        held.shrink(1)

        // interruptFalse halts vanilla so the lectern/sign UI never opens
        // — same shape YgannAbyssGuard uses for its bucket gate.
        return EventResult.interruptFalse()
    }

    /**
     * Reverse-translate each page of [book] at the holy fraction. Writes
     * back in place. Returns true iff at least one page actually changed
     * — the caller uses that to decide whether to fan a BE update out.
     */
    private fun revealPages(book: ItemStack, rng: Random): Boolean {
        val written = book.`is`(Items.WRITTEN_BOOK)
        val writable = book.`is`(Items.WRITABLE_BOOK)
        if (!written && !writable) return false
        val tag = book.tag ?: return false
        val pages = tag.getList("pages", Tag.TAG_STRING.toInt())
        if (pages.isEmpty()) return false

        var changed = false
        val updated = ListTag()
        for (i in 0 until pages.size) {
            val raw = pages.getString(i)
            val plain = if (written) pageText(raw) else raw
            val translated = Sselith.translateToEnglish(plain, HOLY_NUMBER_FRACTION, rng)
            if (translated != plain) changed = true
            val encoded = if (written) {
                Component.Serializer.toJson(Component.literal(translated))
            } else {
                translated
            }
            updated.add(StringTag.valueOf(encoded))
        }
        if (!changed) return false
        tag.put("pages", updated)
        return true
    }

    /** Plain display text of a written-book page (a JSON component),
     *  falling back to the raw string if it won't parse. */
    private fun pageText(json: String): String =
        try {
            Component.Serializer.fromJson(json)?.string ?: json
        } catch (_: Exception) {
            json
        }

    /**
     * Reverse-translate both faces of [be] in place. Returns true iff at
     * least one line on either face actually changed. [SignText] is
     * immutable — each [SignText.setMessage] returns a new instance, so
     * we accumulate via fold and let `updateText` install the result.
     */
    private fun revealSign(be: SignBlockEntity, rng: Random): Boolean {
        var changed = false
        for (isFront in booleanArrayOf(true, false)) {
            be.updateText({ original ->
                var next = original
                for (line in 0 until SIGN_LINES) {
                    val current = original.getMessage(line, false)
                    val plain = current.string
                    if (plain.isEmpty()) continue
                    val translated = Sselith.translateToEnglish(plain, HOLY_NUMBER_FRACTION, rng)
                    if (translated != plain) {
                        next = next.setMessage(line, Component.literal(translated))
                        changed = true
                    }
                }
                next
            }, isFront)
        }
        return changed
    }

    /** Vanilla `SignText` carries exactly 4 lines (`Component.empty()` for blanks). */
    private const val SIGN_LINES = 4

    /** Same cylindrical glyph column the Cataloger raises when filing a
     *  lectern — visual continuity ties the two events together. */
    private fun spawnGlyphColumn(level: ServerLevel, pos: BlockPos) {
        val cx = pos.x + 0.5
        val cz = pos.z + 0.5
        val baseY = pos.y + 1.0
        val rng = level.random
        for (i in 0 until GLYPH_COUNT) {
            val angle = i.toDouble() / GLYPH_COUNT * (PI * 2.0) + (rng.nextDouble() - 0.5) * 0.25
            val r = GLYPH_RADIUS + (rng.nextDouble() - 0.5) * 0.08
            val px = cx + cos(angle) * r
            val pz = cz + sin(angle) * r
            val py = baseY + rng.nextDouble() * GLYPH_HEIGHT
            level.sendParticles(EKParticles.sselithGlyph(), px, py, pz, 0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private const val GLYPH_COUNT = 26
    private const val GLYPH_RADIUS = 0.45
    private const val GLYPH_HEIGHT = 1.6
}
