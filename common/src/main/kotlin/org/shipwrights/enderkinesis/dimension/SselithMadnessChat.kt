package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.ChatEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.sselith.Sselith

/**
 * Sselith Madness chat corruption: a player under the effect has random words in
 * their chat messages replaced with their Sselith translation (via [Sselith]). The
 * share of words replaced scales with the madness level — a couple at level 1, up
 * to *every* word at level 5 — so the player's speech slips further into Sselith
 * the longer the madness holds.
 *
 * The original (signed) message is suppressed and a system message with the
 * corrupted text is broadcast in its place, formatted exactly like normal chat
 * (`chat.type.text`). Using a system message sidesteps secure-chat signature
 * trouble — we're not modifying a signed payload, we're replacing it.
 *
 * Registered *after* [SselithChatTeleport] so the teleport phrase is still detected
 * before this handler suppresses the original message.
 */
object SselithMadnessChat {

    private const val MAX_LEVEL = 5

    fun init() {
        ChatEvent.RECEIVED.register(
            ChatEvent.Received { player, message ->
                if (player == null) EventResult.pass() else handle(player, message.string)
            },
        )
    }

    private fun handle(player: ServerPlayer, original: String): EventResult {
        val level = (player.getEffect(EKEffects.SSELITH_MADNESS.get())?.amplifier ?: return EventResult.pass()) + 1
        val corrupted = corrupt(original, level)
        if (corrupted == original) return EventResult.pass()

        // The madness can garble a number (e.g. "34.5") into the holy invocation —
        // honour it. Only when the original *didn't* already contain the phrase, so
        // we don't double-fire with SselithChatTeleport's own (earlier) handler.
        if (!SselithChatTeleport.containsInvocation(original)) {
            SselithChatTeleport.tryInvoke(player, corrupted)
        }

        val formatted = Component.translatable("chat.type.text", player.displayName, Component.literal(corrupted))
        player.server.playerList.broadcastSystemMessage(formatted, false)
        // Suppress the original so only the corrupted line shows.
        return EventResult.interruptFalse()
    }

    /** Replace a level-scaled random subset of [text]'s words with their Sselith
     *  translation. Level 5 replaces all; lower levels replace `round(n·level/5)`
     *  words (at least one), so level 1 is a couple words on a typical message. */
    private fun corrupt(text: String, level: Int): String {
        val words = text.split(" ")
        // Eligible: any token with a letter OR a digit. Digit tokens matter — they
        // route through [Sselith.translate], which converts numeric literals to the
        // base-6 Sselith numeral system; excluding them left numbers untranslated.
        val translatable = words.indices.filter { idx -> words[idx].any(Char::isLetterOrDigit) }
        if (translatable.isEmpty()) return text

        val count =
            if (level >= MAX_LEVEL) translatable.size
            else maxOf(1, Math.round(translatable.size * (level.toDouble() / MAX_LEVEL)).toInt())

        val chosen = translatable.shuffled().take(count).toHashSet()
        val out = words.toMutableList()
        for (i in chosen) out[i] = translateWord(out[i])
        return out.joinToString(" ")
    }

    /** Translate a single whitespace-delimited token (punctuation rides along via
     *  [Sselith]'s own tokenizer), matching the original token's leading case so a
     *  mid-sentence word doesn't come back capitalised. */
    private fun translateWord(word: String): String {
        val translated = Sselith.translate(word).trim()
        if (translated.isEmpty()) return word
        // De-capitalise when the source token isn't a capitalised word — including
        // pure numbers (no letters) — so a translated numeral/word blends mid-sentence
        // instead of carrying the translator's sentence-case capital.
        val firstLetter = word.firstOrNull(Char::isLetter)
        return if (firstLetter == null || firstLetter.isLowerCase()) {
            translated.replaceFirstChar(Char::lowercaseChar)
        } else {
            translated
        }
    }
}
