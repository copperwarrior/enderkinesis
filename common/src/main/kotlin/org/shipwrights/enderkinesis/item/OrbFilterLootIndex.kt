package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase
import net.minecraft.world.level.storage.loot.entries.LootItem
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer
import net.minecraft.world.level.storage.loot.entries.TagEntry
import org.shipwrights.enderkinesis.mixin.CompositeEntryBaseAccessor
import org.shipwrights.enderkinesis.mixin.LootItemAccessor
import org.shipwrights.enderkinesis.mixin.LootPoolAccessor
import org.shipwrights.enderkinesis.mixin.LootTableAccessor
import org.shipwrights.enderkinesis.mixin.TagEntryAccessor
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Reverse index: which [EntityType]s drop a given [Item], built by
 * walking every entity's default loot table once per server (or once
 * per loot-data reload). Used by [OrbFilter.matchesEntityDrops] to
 * answer "does the Disintegration filter's allow-list cover this
 * mob?" without iterating every loot table on every beam tick.
 *
 * **Build:** lazy, on first query against a level's server. We walk
 *   - every `EntityType` in [BuiltInRegistries.ENTITY_TYPE]
 *   - look up its `defaultLootTable()` via the server's `LootDataManager`
 *   - recurse the table's pools → entries:
 *       - [LootItem] → add `item → entityType`
 *       - [CompositeEntryBase] (alternatives / sequential / group) →
 *         recurse into `children`
 *       - [TagEntry] (`#minecraft:wool`) → expand the tag's items and
 *         add each `item → entityType`
 *       - anything else (loot-table references, conditions-only) → ignore
 *
 * **Invalidation:** keyed by [MinecraftServer] via [WeakHashMap], so a
 * server shutdown drops the cache. Loot-data reloads on a live server
 * are rare; if a user `/reload`s and the filter starts behaving stale,
 * they can clear/re-set the filter to invalidate the orb's cached
 * [OrbFilter] (rebuilding the index on next query).
 *
 * Tag-keyed lookups are computed once per (server, tag) and stored in
 * a secondary cache so a tag rule with many entries doesn't re-iterate
 * every membership on every beam tick.
 */
object OrbFilterLootIndex {

    private val LOG = LogUtils.getLogger()

    private class Index(
        /** Reverse map: item → entity types whose loot table can drop it. */
        val itemToEntities: Map<Item, Set<EntityType<*>>>,
    ) {
        /** Lazy `(itemTag → entity types whose loot table can drop ANY
         *  item in the tag)`. Filled on demand because a filter using
         *  a particular tag may never see most others. */
        val itemTagToEntities: ConcurrentHashMap<TagKey<Item>, Set<EntityType<*>>> =
            ConcurrentHashMap()
        /** Lazy `(blockTag → entity types whose loot table can drop the
         *  item form of ANY block in the tag)`. */
        val blockTagToEntities: ConcurrentHashMap<TagKey<Block>, Set<EntityType<*>>> =
            ConcurrentHashMap()
    }

    /** Per-server cache. Weak so server shutdown clears it. */
    private val byServer: WeakHashMap<MinecraftServer, Index> = WeakHashMap()

    @Synchronized
    private fun getOrBuild(level: ServerLevel): Index {
        val server = level.server
        var index = byServer[server]
        if (index == null) {
            index = build(server)
            byServer[server] = index
        }
        return index
    }

    /** True iff [entity]'s default loot table is known to be able to
     *  drop [item]. */
    fun entityCanDrop(level: ServerLevel, entity: LivingEntity, item: Item): Boolean {
        val ents = getOrBuild(level).itemToEntities[item] ?: return false
        return entity.type in ents
    }

    /** True iff [entity]'s default loot table is known to be able to
     *  drop *any* item in [tag]. */
    fun entityCanDropFromItemTag(
        level: ServerLevel, entity: LivingEntity, tag: TagKey<Item>,
    ): Boolean {
        val index = getOrBuild(level)
        val ents = index.itemTagToEntities.computeIfAbsent(tag) {
            computeItemTagEntities(level, index, tag)
        }
        return entity.type in ents
    }

    /** Same as [entityCanDropFromItemTag] but for block tags — the
     *  "item form" of every block in the tag is the lookup key. */
    fun entityCanDropFromBlockTag(
        level: ServerLevel, entity: LivingEntity, tag: TagKey<Block>,
    ): Boolean {
        val index = getOrBuild(level)
        val ents = index.blockTagToEntities.computeIfAbsent(tag) {
            computeBlockTagEntities(level, index, tag)
        }
        return entity.type in ents
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    private fun build(server: MinecraftServer): Index {
        val lootData = server.lootData
        val itemToEntities = HashMap<Item, MutableSet<EntityType<*>>>()
        val visitedTables: MutableSet<LootTable> =
            java.util.Collections.newSetFromMap(IdentityHashMap())
        val visitedEntries: MutableSet<LootPoolEntryContainer> =
            java.util.Collections.newSetFromMap(IdentityHashMap())
        var entitiesScanned = 0
        var entriesAdded = 0
        for (type in BuiltInRegistries.ENTITY_TYPE) {
            val lootTableId = type.defaultLootTable
            val table = lootData.getLootTable(lootTableId)
            if (table === LootTable.EMPTY) continue
            entitiesScanned++
            // Collect every item the table could emit.
            val tableItems = HashSet<Item>()
            visitedTables.clear()
            visitedEntries.clear()
            collectItemsFromTable(server, table, tableItems, visitedTables, visitedEntries)
            for (item in tableItems) {
                val set = itemToEntities.getOrPut(item) { HashSet() }
                if (set.add(type)) entriesAdded++
            }
        }
        LOG.info(
            "OrbFilterLootIndex built: {} entities scanned, {} item→entity mappings",
            entitiesScanned, entriesAdded,
        )
        return Index(itemToEntities)
    }

    private fun collectItemsFromTable(
        server: MinecraftServer,
        table: LootTable,
        sink: MutableSet<Item>,
        visitedTables: MutableSet<LootTable>,
        visitedEntries: MutableSet<LootPoolEntryContainer>,
    ) {
        if (!visitedTables.add(table)) return
        val pools = (table as LootTableAccessor).`enderkinesis$getPools`() ?: return
        for (pool in pools) {
            val entries = (pool as LootPoolAccessor).`enderkinesis$getEntries`() ?: continue
            for (entry in entries) {
                collectItemsFromEntry(server, entry, sink, visitedTables, visitedEntries)
            }
        }
    }

    private fun collectItemsFromEntry(
        server: MinecraftServer,
        entry: LootPoolEntryContainer,
        sink: MutableSet<Item>,
        visitedTables: MutableSet<LootTable>,
        visitedEntries: MutableSet<LootPoolEntryContainer>,
    ) {
        if (!visitedEntries.add(entry)) return
        when (entry) {
            is LootItem -> {
                val item = (entry as LootItemAccessor).`enderkinesis$getItem`()
                sink.add(item)
            }
            is CompositeEntryBase -> {
                val children = (entry as CompositeEntryBaseAccessor).`enderkinesis$getChildren`()
                if (children != null) {
                    for (child in children) {
                        collectItemsFromEntry(server, child, sink, visitedTables, visitedEntries)
                    }
                }
            }
            is TagEntry -> {
                val tag = (entry as TagEntryAccessor).`enderkinesis$getTag`()
                val itemRegistry = server.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.ITEM)
                for (holder in itemRegistry.getTagOrEmpty(tag)) {
                    sink.add(holder.value())
                }
            }
            // Other entry types (LootTableReference, EmptyLootItem,
            // DynamicLoot like "minecraft:contents") don't carry a
            // statically known item — skip. DynamicLoot is largely
            // chest/container content which isn't entity drops.
        }
    }

    // ------------------------------------------------------------------
    // Tag → entity-type expansion
    // ------------------------------------------------------------------

    private fun computeItemTagEntities(
        level: ServerLevel, index: Index, tag: TagKey<Item>,
    ): Set<EntityType<*>> {
        val itemRegistry = level.server.registryAccess()
            .registryOrThrow(net.minecraft.core.registries.Registries.ITEM)
        val tagHolders = itemRegistry.getTagOrEmpty(tag)
        if (tagHolders.iterator().hasNext().not()) return emptySet()
        val result = HashSet<EntityType<*>>()
        for (holder in tagHolders) {
            val ents = index.itemToEntities[holder.value()] ?: continue
            result.addAll(ents)
        }
        return result
    }

    private fun computeBlockTagEntities(
        level: ServerLevel, index: Index, tag: TagKey<Block>,
    ): Set<EntityType<*>> {
        val blockRegistry = level.server.registryAccess()
            .registryOrThrow(net.minecraft.core.registries.Registries.BLOCK)
        val tagHolders = blockRegistry.getTagOrEmpty(tag)
        if (tagHolders.iterator().hasNext().not()) return emptySet()
        val result = HashSet<EntityType<*>>()
        for (holder in tagHolders) {
            val itemForm = holder.value().asItem()
            val ents = index.itemToEntities[itemForm] ?: continue
            result.addAll(ents)
        }
        return result
    }
}
