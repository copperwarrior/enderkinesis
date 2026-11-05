package org.shipwrights.enderkinesis.item

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.state.BlockState

/** What a sketch draws. */
sealed interface SketchSubject {
    data class Block(val state: BlockState) : SketchSubject
    data class Entity(val type: EntityType<*>, val nbt: CompoundTag?) : SketchSubject
    data class Structure(val id: ResourceLocation) : SketchSubject

    /** A drawn picture that the client holds under [key]; see SketchPictures. */
    data class Picture(val key: String) : SketchSubject
}

data class SketchSpec(val subject: SketchSubject, val seed: Int, val size: Int, val notes: Boolean) {
    /** Book lines the sketch fills. */
    val lines: Int get() = (size + SketchTag.LINE_HEIGHT - 1) / SketchTag.LINE_HEIGHT
}

/** A tag at [start] until [end] (exclusive) in page text; [spec] is null when the tag does not parse. */
class FoundSketchTag(val start: Int, val end: Int, val spec: SketchSpec?)

/**
 * Sketch tags on book pages:
 * `[sketch=minecraft:oak_stairs[facing=north]]`, `[sketch:entity=minecraft:sheep{Color:14b}]`,
 * `[sketch:structure=minecraft:igloo/top]`, each with optional `size=`, `seed=` and `notes=false`.
 */
object SketchTag {

    const val LINE_HEIGHT = 9
    private const val OPEN = "[sketch"
    private const val MIN_SIZE = 16
    private const val MAX_SIZE = 126
    private const val DEFAULT_SIZE = 114
    private val OFF = setOf("false", "off", "no", "0")

    /** The tag that [findAll] reads back as [spec]; a structure tag needs its id only. */
    fun of(spec: SketchSpec): String {
        val subject = when (val s = spec.subject) {
            is SketchSubject.Block -> "=" + BlockStateParser.serialize(s.state)
            is SketchSubject.Entity -> ":entity=" + BuiltInRegistries.ENTITY_TYPE.getKey(s.type) + (s.nbt?.toString() ?: "")
            is SketchSubject.Structure -> ":structure=" + s.id
            is SketchSubject.Picture -> ":picture=" + s.key
        }
        val notes = if (spec.notes) "" else " notes=false"
        return "[sketch$subject size=${spec.size} seed=${spec.seed}$notes]"
    }

    fun mayContain(text: String): Boolean = text.contains(OPEN)

    fun findAll(text: String): List<FoundSketchTag> = scanAll(text).map {
        FoundSketchTag(it.start, it.end, spec(it.kind, it.value, it.settings, text.substring(it.start, it.end)))
    }

    /** Where each tag is, without parsing its subject, so it needs no registries. */
    fun spans(text: String): List<IntRange> = scanAll(text).map { it.start until it.end }

    private class Scanned(val start: Int, val end: Int, val kind: String, val value: String, val settings: Map<String, String>)

    private fun scanAll(text: String): List<Scanned> {
        val found = ArrayList<Scanned>()
        var from = 0
        while (from < text.length) {
            val start = text.indexOf(OPEN, from)
            if (start < 0) break
            val tag = scan(text, start)
            if (tag == null) {
                from = start + OPEN.length
            } else {
                found.add(tag)
                from = tag.end
            }
        }
        return found
    }

    private fun scan(text: String, start: Int): Scanned? {
        var i = start + OPEN.length
        val kind = when {
            text.startsWith("=", i) -> "block".also { i++ }
            text.startsWith(":", i) -> {
                val eq = text.indexOf('=', i)
                if (eq < 0) return null
                text.substring(i + 1, eq).also { i = eq + 1 }
            }
            else -> return null
        }
        val valueEnd = valueEnd(text, i) ?: return null
        val value = text.substring(i, valueEnd)
        i = valueEnd
        val settings = HashMap<String, String>()
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            if (text[i] == ']') return Scanned(start, i + 1, kind, value, settings)
            var tokenEnd = i
            while (tokenEnd < text.length && !text[tokenEnd].isWhitespace() && text[tokenEnd] != ']') tokenEnd++
            val token = text.substring(i, tokenEnd)
            val eq = token.indexOf('=')
            if (eq <= 0) return null
            settings[token.substring(0, eq).lowercase()] = token.substring(eq + 1)
            i = tokenEnd
        }
        return null
    }

    /** The end of a value: block-state brackets, NBT braces and quoted strings may hold spaces and brackets. */
    private fun valueEnd(text: String, from: Int): Int? {
        var depth = 0
        var quote: Char? = null
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++ else if (c == quote) quote = null
            } else {
                when {
                    c == '"' || c == '\'' -> quote = c
                    c == '[' || c == '{' -> depth++
                    c == ']' || c == '}' -> {
                        if (depth == 0) return if (c == ']') i else null
                        depth--
                    }
                    depth == 0 && c.isWhitespace() -> return i
                }
            }
            i++
        }
        return null
    }

    private fun spec(kind: String, value: String, settings: Map<String, String>, raw: String): SketchSpec? {
        val subject = when (kind) {
            "block" -> block(value)
            "entity" -> entity(value)
            "structure" -> ResourceLocation.tryParse(value)?.let { SketchSubject.Structure(it) }
            "picture" -> SketchSubject.Picture(value)
            else -> null
        } ?: return null
        return SketchSpec(
            subject = subject,
            seed = settings["seed"]?.toIntOrNull() ?: raw.hashCode(),
            size = (settings["size"]?.toIntOrNull() ?: DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE),
            notes = settings["notes"]?.lowercase() !in OFF,
        )
    }

    private fun block(value: String): SketchSubject? = try {
        SketchSubject.Block(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), value, false).blockState())
    } catch (_: CommandSyntaxException) {
        null
    }

    private fun entity(value: String): SketchSubject? {
        val brace = value.indexOf('{')
        val id = ResourceLocation.tryParse(if (brace < 0) value else value.substring(0, brace)) ?: return null
        val type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) ?: return null
        val nbt = if (brace < 0) {
            null
        } else {
            try {
                TagParser.parseTag(value.substring(brace))
            } catch (_: CommandSyntaxException) {
                return null
            }
        }
        return SketchSubject.Entity(type, nbt)
    }
}
