package org.shipwrights.enderkinesis.item

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.TagParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.WrittenBookItem
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Compiled predicate driven by an orb's filter [ItemStack].
 *
 * The filter is parsed once when the orb's filter slot changes (via
 * [org.shipwrights.enderkinesis.item.TomeOfFilteringItem]) and cached on
 * the BE; tomes that consult the filter call [matchesItem],
 * [matchesBlock], or [matchesEntityDrops] each tick on the cached
 * instance instead of re-parsing.
 *
 * **Source shape determines rules.**
 *
 *  - **Plain item / block item** → one [ItemRule] for that item.
 *  - **Written book** (`minecraft:written_book`) or **book and quill**
 *    (`minecraft:writable_book`) → every page is split on `\n`, every
 *    non-blank line is parsed independently. The rules are
 *    **disjunctive** — a stack / block / entity passes if **any** line
 *    matches it. (The user describes this as "each line is an AND";
 *    a single item can't simultaneously *be* two distinct things, so
 *    "AND" reads as "the filter additively includes each line as an
 *    accepted pattern" — set union of allowed values, i.e. OR.)
 *
 * **Per-line syntax.**
 *
 *  - `minecraft:iron_sword` — exact item id (or block id).
 *  - `#minecraft:arrows` — tag prefix. The line is registered as
 *    BOTH an [ItemTagRule] and a [BlockTagRule] against the same
 *    `ResourceLocation`, so the same `#minecraft:foo` line works
 *    whether `minecraft:foo` is an item tag, a block tag, or both
 *    (the rule for the registry that doesn't carry that tag is just
 *    inert).
 *  - `minecraft:diamond_sword{nbt}` — vanilla NBT brace literal,
 *    parsed via [TagParser]; the stack must be `is(item)` AND its NBT
 *    must contain every field/value the spec declares
 *    (subset-match via [NbtUtils.compareNbt]).
 *  - `minecraft:diamond_sword[enchantments={sharpness:4}]` — bracket
 *    syntax (1.20.5+-style item components). Mapped into 1.20.1 NBT:
 *    `enchantments={a:N,b:M}` → `{Enchantments:[{id:"minecraft:a",lvl:N},…]}`.
 *  - `minecraft:creeper` — entity id. The same line, in addition to
 *    any matching item / block parses, also registers an [EntityRule]
 *    against the [EntityType] of that id; the Disintegration entity
 *    gate matches a [LivingEntity] when its type *is* that entity
 *    type (direct match — no loot-table reverse lookup).
 *  - `#minecraft:undead` — tag prefix. In addition to item / block
 *    tag rules, also registers an [EntityTypeTagRule] against the
 *    entity-type tag of the same `ResourceLocation` so e.g.
 *    `#minecraft:undead` directly matches every entity in the
 *    `undead` entity tag.
 *
 * **Block destruction filter.** Block rules compare against the
 * destroyed [BlockState]'s [Block]. An [ItemRule] backed by a
 * [BlockItem] also acts as a block rule for that block.
 *
 * **Entity damage filter.** Three flavours stack into the same
 * `matchesEntity` predicate:
 *  1. **Direct type / tag** — [EntityRule] and [EntityTypeTagRule]
 *     match the entity's [EntityType] head-on (`minecraft:creeper`,
 *     `#minecraft:undead`). No loot table involved.
 *  2. **Drop reverse-lookup** — [ItemRule] / [ItemTagRule] /
 *     [BlockTagRule] check, via [OrbFilterLootIndex] (cached pre-walk
 *     of every entity's loot table at first query / loot-data reload),
 *     whether the candidate's [EntityType] drops the matching item.
 *
 * Multiple-rule filters (books) return true if any rule matches the
 * candidate, on each of the three predicates.
 */
class OrbFilter private constructor(val rules: List<Rule>) {

    fun isEmpty(): Boolean = rules.isEmpty()

    /** True iff [stack] matches the filter. Empty filter matches all
     *  stacks; otherwise at least one rule must approve. */
    fun matchesItem(stack: ItemStack): Boolean {
        if (rules.isEmpty()) return true
        for (rule in rules) if (rule.matchesItem(stack)) return true
        return false
    }

    /** True iff [state] matches the filter. Used by Disintegration's
     *  block-mining gate. Empty filter matches all blocks. */
    fun matchesBlock(state: BlockState): Boolean {
        if (rules.isEmpty()) return true
        for (rule in rules) if (rule.matchesBlock(state)) return true
        return false
    }

    /** True iff [entity] is allowed by the filter. Used by
     *  Disintegration's beam-damage gate. A rule can pass for two
     *  reasons:
     *
     *   1. **Direct entity match** — an [EntityRule] or
     *      [EntityTypeTagRule] line in the filter targets this
     *      entity's [EntityType] (e.g. `minecraft:creeper`,
     *      `#minecraft:undead`).
     *   2. **Drop match** — an item / tag rule names something the
     *      entity's loot table can produce (e.g. filter = `bone` →
     *      skeletons drop bone → skeleton passes).
     *
     *  Either form on either side of the link lets the entity
     *  through. Empty filter matches all entities. */
    fun matchesEntity(entity: LivingEntity, level: ServerLevel): Boolean {
        if (rules.isEmpty()) return true
        for (rule in rules) if (rule.matchesEntity(entity, level)) return true
        return false
    }

    sealed interface Rule {
        fun matchesItem(stack: ItemStack): Boolean
        fun matchesBlock(state: BlockState): Boolean
        fun matchesEntity(entity: LivingEntity, level: ServerLevel): Boolean
    }

    /** Exact-item rule. [requiredNbt] is an optional subset of NBT the
     *  stack must contain (per [NbtUtils.compareNbt] semantics) — null
     *  means "any NBT". If [item] is a [BlockItem] the rule also acts
     *  as a block rule for the underlying block. */
    class ItemRule(val item: Item, val requiredNbt: CompoundTag?) : Rule {
        override fun matchesItem(stack: ItemStack): Boolean {
            if (!stack.`is`(item)) return false
            val required = requiredNbt ?: return true
            val tag = stack.tag ?: return false
            return NbtUtils.compareNbt(required, tag, true)
        }

        override fun matchesBlock(state: BlockState): Boolean {
            // NBT-qualified item rules don't apply to plain block
            // states — the destroyed block has no item-form NBT to
            // match against. Drop the NBT requirement for block
            // mining and just compare the underlying block.
            val it = item
            return it is BlockItem && it.block === state.block
        }

        override fun matchesEntity(entity: LivingEntity, level: ServerLevel): Boolean {
            return OrbFilterLootIndex.entityCanDrop(level, entity, item)
        }
    }

    /** Item-tag rule — `#minecraft:arrows`. Matches any item / block-
     *  item-backed block / entity-drop within the tag. */
    class ItemTagRule(val tag: TagKey<Item>) : Rule {
        override fun matchesItem(stack: ItemStack): Boolean = stack.`is`(tag)

        override fun matchesBlock(state: BlockState): Boolean {
            // A block matches an item tag iff the block's item form
            // is in the tag (e.g. `#minecraft:planks` covers every
            // plank block).
            val itemForm = state.block.asItem()
            if (itemForm === Items.AIR) return false
            return itemForm.builtInRegistryHolder().`is`(tag)
        }

        override fun matchesEntity(entity: LivingEntity, level: ServerLevel): Boolean {
            return OrbFilterLootIndex.entityCanDropFromItemTag(level, entity, tag)
        }
    }

    /** Block-tag rule — `#minecraft:sand`. Matches blocks in the tag;
     *  for items only matches BlockItems whose underlying block is in
     *  the tag. */
    class BlockTagRule(val tag: TagKey<Block>) : Rule {
        override fun matchesItem(stack: ItemStack): Boolean {
            val it = stack.item
            if (it !is BlockItem) return false
            return it.block.builtInRegistryHolder().`is`(tag)
        }

        override fun matchesBlock(state: BlockState): Boolean = state.`is`(tag)

        override fun matchesEntity(entity: LivingEntity, level: ServerLevel): Boolean {
            return OrbFilterLootIndex.entityCanDropFromBlockTag(level, entity, tag)
        }
    }

    /** Direct entity-type rule — `minecraft:creeper`. Matches a
     *  [LivingEntity] iff `entity.type === entityType`. Does not
     *  apply to items or blocks (entity ids and item / block ids
     *  share a registry namespace but the type-token is separate;
     *  the parser also emits an [ItemRule] when the same id also
     *  resolves to an item, so e.g. `minecraft:item_frame` ends up
     *  matching both forms). */
    class EntityRule(val entityType: EntityType<*>) : Rule {
        override fun matchesItem(stack: ItemStack): Boolean = false
        override fun matchesBlock(state: BlockState): Boolean = false
        override fun matchesEntity(entity: LivingEntity, level: ServerLevel): Boolean =
            entity.type === entityType
    }

    /** Entity-type tag rule — `#minecraft:undead`,
     *  `#minecraft:skeletons`, etc. Matches a [LivingEntity] iff its
     *  type is in [tag]. Does not apply to items or blocks. */
    class EntityTypeTagRule(val tag: TagKey<EntityType<*>>) : Rule {
        override fun matchesItem(stack: ItemStack): Boolean = false
        override fun matchesBlock(state: BlockState): Boolean = false
        override fun matchesEntity(entity: LivingEntity, level: ServerLevel): Boolean =
            entity.type.`is`(tag)
    }

    companion object {
        val EMPTY: OrbFilter = OrbFilter(emptyList())

        /** Parse [filterStack] into a compiled [OrbFilter]. Always
         *  succeeds — unparseable lines are silently dropped, an
         *  empty list of rules gives an [EMPTY] filter (matches all). */
        fun from(filterStack: ItemStack): OrbFilter {
            if (filterStack.isEmpty) return EMPTY
            if (filterStack.`is`(Items.WRITTEN_BOOK)) {
                return fromBook(extractWrittenPages(filterStack))
            }
            if (filterStack.`is`(Items.WRITABLE_BOOK)) {
                return fromBook(extractWritablePages(filterStack))
            }
            // Plain item form → one rule for the item type, no NBT
            // requirement. Stored book-NBT on a non-book item type
            // would never match anyway, so dropping the NBT here is
            // safe.
            return OrbFilter(listOf(ItemRule(filterStack.item, null)))
        }

        /** Build a filter from the concatenated text of a book's
         *  pages. Splits every page on `\n`, parses every non-blank
         *  line as a rule. */
        private fun fromBook(lineStream: Sequence<String>): OrbFilter {
            val rules = ArrayList<Rule>()
            for (raw in lineStream) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                parseLine(trimmed, rules)
            }
            return if (rules.isEmpty()) EMPTY else OrbFilter(rules)
        }

        /** Written books store each page as a JSON text Component.
         *  We need the *plain* text — strip the component wrapper
         *  via [WrittenBookItem.getPlayerPageCount]'s sibling helper
         *  doesn't exist publicly, so we parse the page tag
         *  ourselves. */
        private fun extractWrittenPages(stack: ItemStack): Sequence<String> {
            val tag = stack.tag ?: return emptySequence()
            if (!tag.contains("pages", 9)) return emptySequence()
            val pagesList = tag.getList("pages", 8)
            return sequence {
                for (i in 0 until pagesList.size) {
                    val pageJson = pagesList.getString(i)
                    val text = jsonToPlainText(pageJson)
                    for (line in text.split('\n')) yield(line)
                }
            }
        }

        /** Writable (book-and-quill) pages are stored as plain strings,
         *  no JSON wrapper. */
        private fun extractWritablePages(stack: ItemStack): Sequence<String> {
            val tag = stack.tag ?: return emptySequence()
            if (!tag.contains("pages", 9)) return emptySequence()
            val pagesList = tag.getList("pages", 8)
            return sequence {
                for (i in 0 until pagesList.size) {
                    val pageText = pagesList.getString(i)
                    for (line in pageText.split('\n')) yield(line)
                }
            }
        }

        /** Minimal `{"text":"..."}` extractor. Written books in the
         *  vanilla writing UI always end up as the basic text
         *  component, so we just pluck the `text` field; if the page
         *  is a richer component or a raw string we fall back to the
         *  raw payload. Avoids dragging the full Component parser
         *  for this one-shot text extraction. */
        private fun jsonToPlainText(pageJson: String): String {
            val trimmed = pageJson.trim()
            // Plain-string page → use as-is.
            if (!trimmed.startsWith("{") && !trimmed.startsWith("\"")) return trimmed
            // {"text":"..."} — find and unescape the `text` value.
            val key = "\"text\""
            val keyIdx = trimmed.indexOf(key)
            if (keyIdx < 0) return trimmed
            val colonIdx = trimmed.indexOf(':', keyIdx + key.length)
            if (colonIdx < 0) return trimmed
            val quoteOpen = trimmed.indexOf('"', colonIdx + 1)
            if (quoteOpen < 0) return trimmed
            val sb = StringBuilder()
            var i = quoteOpen + 1
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (c == '\\' && i + 1 < trimmed.length) {
                    when (val esc = trimmed[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"', '\\', '/' -> sb.append(esc)
                        else -> sb.append(esc)
                    }
                    i += 2
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        // ----------------------------------------------------------
        // Per-line parser
        // ----------------------------------------------------------

        private fun parseLine(line: String, sink: MutableList<Rule>) {
            // Tag prefix → emit item-tag, block-tag, AND entity-type
            // tag rules from the same id. Whichever registry doesn't
            // carry the tag stays inert; one matches when at least
            // one form is registered. So `#minecraft:wool` matches
            // wool items / wool blocks / wool-dropping mobs, while
            // `#minecraft:undead` matches undead entities (the item
            // and block tag rules are simply empty).
            if (line.startsWith("#")) {
                val loc = ResourceLocation.tryParse(line.substring(1).trim()) ?: return
                sink.add(ItemTagRule(TagKey.create(Registries.ITEM, loc)))
                sink.add(BlockTagRule(TagKey.create(Registries.BLOCK, loc)))
                sink.add(EntityTypeTagRule(TagKey.create(Registries.ENTITY_TYPE, loc)))
                return
            }

            // Split into "<id>" and an optional trailing qualifier
            // (`{nbt…}` or `[bracket…]`). Whichever introducer comes
            // first wins — splice the rest verbatim and dispatch on
            // its delimiter.
            val braceIdx = line.indexOf('{')
            val bracketIdx = line.indexOf('[')
            val cut = when {
                braceIdx < 0 && bracketIdx < 0 -> line.length
                braceIdx < 0 -> bracketIdx
                bracketIdx < 0 -> braceIdx
                else -> Math.min(braceIdx, bracketIdx)
            }
            val idText = line.substring(0, cut).trim()
            val loc = ResourceLocation.tryParse(idText) ?: return

            var requiredNbt: CompoundTag? = null
            if (cut < line.length) {
                val qualifier = line.substring(cut).trim()
                requiredNbt = when {
                    qualifier.startsWith("{") -> parseNbtBraces(qualifier)
                    qualifier.startsWith("[") -> parseComponentBrackets(qualifier)
                    else -> null
                }
            }

            // Same id can be both an Item registry entry (BlockItem
            // covers most blocks) and an entry the user wants
            // treated as a Block. For items, register an ItemRule;
            // for blocks-without-an-item-form we also register a
            // BlockTagRule-style direct block rule via the item
            // tag's underlying block — but for simplicity we only
            // emit ItemRule (it already matches BlockItem-backed
            // blocks via the `matchesBlock` override).
            val item = BuiltInRegistries.ITEM.getOptional(loc).orElse(null)
            if (item != null && item !== Items.AIR) {
                sink.add(ItemRule(item, requiredNbt))
            }
            // Same id may also be an EntityType — emit an EntityRule
            // so `minecraft:creeper` matches creepers, `minecraft:zombie`
            // matches zombies, etc. Items / entities share a registry
            // namespace but separate type registries; entries that
            // resolve to both (rare — `minecraft:item_frame` is the
            // obvious one) get both rules added, which is what the
            // player would intuitively expect.
            val entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(loc).orElse(null)
            if (entityType != null) {
                sink.add(EntityRule(entityType))
            }
            // Blocks with no BlockItem (e.g. fire, end_portal,
            // technical blocks) still want to be matchable by id.
            // Wrap as a one-element tag rule by reusing the item-tag
            // ResourceLocation — too noisy. Skip for now; the user
            // can use `#minecraft:foo` block tags if they need to
            // match technical blocks.
        }

        /** Parse a `{...}` NBT compound literal using vanilla's
         *  [TagParser]. Returns null on parse failure. */
        private fun parseNbtBraces(text: String): CompoundTag? {
            return try {
                TagParser.parseTag(text)
            } catch (_: Exception) {
                null
            }
        }

        /** Parse a `[key=value, key2=value2]` component-bracket
         *  qualifier and translate the recognised keys into 1.20.1
         *  NBT. Currently supports `enchantments={ench:lvl,…}` →
         *  `Enchantments` list tag (covers the user's stated
         *  example). Unknown keys are silently ignored. */
        private fun parseComponentBrackets(text: String): CompoundTag? {
            if (!text.startsWith("[") || !text.endsWith("]")) return null
            val inner = text.substring(1, text.length - 1).trim()
            if (inner.isEmpty()) return null
            val nbt = CompoundTag()
            for (part in splitBalanced(inner, ',')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val key = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                when (key) {
                    "enchantments" -> {
                        val list = parseEnchantmentMap(value) ?: continue
                        nbt.put("Enchantments", list)
                    }
                    // More component-style keys can be added here
                    // as users need them (e.g. damage=, custom_name=).
                }
            }
            return if (nbt.isEmpty) null else nbt
        }

        /** Parse `{ench:lvl, ench2:lvl2}` into an `Enchantments` list
         *  tag of `{id:"minecraft:ench",lvl:Ns}` entries. */
        private fun parseEnchantmentMap(value: String): ListTag? {
            if (!value.startsWith("{") || !value.endsWith("}")) return null
            val inner = value.substring(1, value.length - 1).trim()
            if (inner.isEmpty()) return null
            val list = ListTag()
            for (part in splitBalanced(inner, ',')) {
                val colon = part.indexOf(':')
                if (colon < 0) continue
                val id = part.substring(0, colon).trim().trim('"')
                val lvl = part.substring(colon + 1).trim().toIntOrNull() ?: continue
                val ench = CompoundTag()
                ench.putString("id", if (id.contains(':')) id else "minecraft:$id")
                ench.putShort("lvl", lvl.toShort())
                list.add(ench)
            }
            return if (list.isEmpty()) null else list
        }

        /** Split [text] on [delim] only at top-level — nested `{}`,
         *  `[]`, `()` are skipped over. Lets the bracket parser
         *  handle `enchantments={a:1,b:2}` without splitting inside
         *  the inner braces. */
        private fun splitBalanced(text: String, delim: Char): List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            var start = 0
            for (i in text.indices) {
                when (text[i]) {
                    '{', '[', '(' -> depth++
                    '}', ']', ')' -> if (depth > 0) depth--
                    delim -> if (depth == 0) {
                        parts.add(text.substring(start, i))
                        start = i + 1
                    }
                }
            }
            if (start < text.length) parts.add(text.substring(start))
            return parts
        }
    }
}
