package org.shipwrights.enderkinesis.scrying

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

/**
 * Per-dimension persistent index of every placed scrying orb. Vanilla [SavedData] writes
 * to disk in the dimension folder, so the registry survives chunk unload and server
 * restart without holding any of the orbs' chunks in memory.
 *
 * **Why this exists instead of the previous chunkloader-style index.** The earlier
 * `ScryingChunkManager` doubled as a chunk-force registry: every placed orb force-loaded
 * its own chunk so its block-entity stayed alive and re-registered an in-memory set on
 * each tick. That was expensive — one always-on `TicketType.FORCED` per orb — and it
 * left orphan forced chunks in `ForcedChunksSavedData` for anything that wasn't broken
 * through `Block.onRemove`. Persisting the position set directly removes both costs:
 * orbs are findable from disk, their chunks load only on-demand (via the session-scoped
 * [ScryingTicketType.SCRYING] tickets during an active scry), and the registry's NBT is
 * trivially small (one [BlockPos] tag per orb).
 *
 * Server-thread only. Mutations are gated through [add] / [remove]; both call
 * [setDirty] only on a real change so vanilla doesn't write the file every tick when
 * the same chunk reloads and the block-entity re-registers an already-known orb.
 */
class ScryingOrbRegistry : SavedData() {

    private val orbs: MutableSet<BlockPos> = HashSet()

    /** Idempotent. Returns true if [pos] was newly added. */
    fun add(pos: BlockPos): Boolean {
        val added = orbs.add(pos.immutable())
        if (added) setDirty()
        return added
    }

    /** Idempotent. Returns true if [pos] was previously present. */
    fun remove(pos: BlockPos): Boolean {
        val removed = orbs.remove(pos)
        if (removed) setDirty()
        return removed
    }

    /** Snapshot of every registered orb position. Safe to iterate even if callers
     *  mutate the registry mid-iteration — common case is [OrbOfScryingBlock.use]
     *  iterating to find a target while no one is placing or breaking orbs. */
    fun all(): Collection<BlockPos> = orbs.toList()

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (pos in orbs) {
            list.add(NbtUtils.writeBlockPos(pos))
        }
        tag.put("orbs", list)
        return tag
    }

    companion object {
        /** SavedData filename (per-dimension, lives under `data/` inside the dimension
         *  folder). Vanilla appends `.dat`. */
        const val NAME: String = "enderkinesis_scrying_orbs"

        @JvmStatic
        fun load(tag: CompoundTag): ScryingOrbRegistry {
            val registry = ScryingOrbRegistry()
            val list = tag.getList("orbs", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                registry.orbs.add(NbtUtils.readBlockPos(list.getCompound(i)))
            }
            return registry
        }

        /** Get or create the registry for [level]'s dimension. First call after server
         *  start loads the NBT from disk; subsequent calls return the cached instance. */
        @JvmStatic
        fun get(level: ServerLevel): ScryingOrbRegistry =
            level.dataStorage.computeIfAbsent(::load, ::ScryingOrbRegistry, NAME)
    }
}
