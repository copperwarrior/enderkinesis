package org.shipwrights.enderkinesis.block

import dev.architectury.event.events.common.ChunkEvent
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/** Per-chunk store of "wogor target [BlockState] for the bud at this position."
 *  [HeartOfTheWildManager.onBlockDestroyed] resolves the variant via
 *  [WogorVariantPicker] at destroy-time and stashes it here; the bud's
 *  maturation tick ([WogorBudBlock.tick]) reads it back and places the target.
 *
 *  Server-only. The bud → target transition is a normal block-update, so the
 *  client picks up the change through vanilla block-update broadcast without
 *  any custom sync. Persisted across save/load via Architectury
 *  [ChunkEvent.SAVE_DATA] / [LOAD_DATA] under `enderkinesis:wogor_bud_targets`. */
object WogorBudTargetData {

    private const val TAG: String = "enderkinesis:wogor_bud_targets"
    private const val ENTRY_LIST: String = "E"
    private const val ENTRY_POS: String = "P"
    private const val ENTRY_STATE: String = "S"

    private val perDim: MutableMap<
        ResourceKey<Level>,
        ConcurrentHashMap<Long, Long2ObjectOpenHashMap<BlockState>>
    > = ConcurrentHashMap()

    fun init() {
        ChunkEvent.SAVE_DATA.register(ChunkEvent.SaveData { chunk, level, tag ->
            if (level == null) return@SaveData
            val map = chunkMapIfPresent(level.dimension(), chunk.pos) ?: return@SaveData
            synchronized(map) {
                if (map.isEmpty()) return@SaveData
                val list = ListTag()
                for ((posLong, state) in map) {
                    val entry = CompoundTag()
                    entry.putLong(ENTRY_POS, posLong)
                    entry.put(ENTRY_STATE, NbtUtils.writeBlockState(state))
                    list.add(entry)
                }
                val wrap = CompoundTag()
                wrap.put(ENTRY_LIST, list)
                tag.put(TAG, wrap)
            }
        })
        ChunkEvent.LOAD_DATA.register(ChunkEvent.LoadData { chunk, level, tag ->
            if (level == null) return@LoadData
            if (!tag.contains(TAG)) return@LoadData
            val list = tag.getCompound(TAG).getList(ENTRY_LIST, Tag.TAG_COMPOUND.toInt())
            if (list.isEmpty()) return@LoadData
            val blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK)
            val map = chunkMapOrCreate(level.dimension(), chunk.pos)
            synchronized(map) {
                map.clear()
                for (i in 0 until list.size) {
                    val entry = list.getCompound(i)
                    val state = NbtUtils.readBlockState(blockLookup, entry.getCompound(ENTRY_STATE))
                    map.put(entry.getLong(ENTRY_POS), state)
                }
            }
        })
    }

    fun put(level: Level, pos: BlockPos, target: BlockState) {
        val map = chunkMapOrCreate(level.dimension(), ChunkPos(pos))
        synchronized(map) { map.put(pos.asLong(), target) }
    }

    fun take(level: Level, pos: BlockPos): BlockState? {
        val map = chunkMapIfPresent(level.dimension(), ChunkPos(pos)) ?: return null
        synchronized(map) { return map.remove(pos.asLong()) }
    }

    fun peek(level: Level, pos: BlockPos): BlockState? {
        val map = chunkMapIfPresent(level.dimension(), ChunkPos(pos)) ?: return null
        synchronized(map) { return map.get(pos.asLong()) }
    }

    private fun chunkMapOrCreate(
        dim: ResourceKey<Level>, chunkPos: ChunkPos,
    ): Long2ObjectOpenHashMap<BlockState> {
        val perChunk = perDim.computeIfAbsent(dim) { ConcurrentHashMap() }
        return perChunk.computeIfAbsent(chunkPos.toLong()) { Long2ObjectOpenHashMap() }
    }

    private fun chunkMapIfPresent(
        dim: ResourceKey<Level>, chunkPos: ChunkPos,
    ): Long2ObjectOpenHashMap<BlockState>? {
        val perChunk = perDim[dim] ?: return null
        return perChunk[chunkPos.toLong()]
    }
}
