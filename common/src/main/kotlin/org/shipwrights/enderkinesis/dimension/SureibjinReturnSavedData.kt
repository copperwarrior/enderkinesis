package org.shipwrights.enderkinesis.dimension

import java.util.UUID
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData

/**
 * Disk-backed store of [SureibjinEntry.ReturnSnapshot]s keyed by player
 * UUID. Attached to the overworld's `dataStorage` so the entries survive
 * server restarts — the previous in-memory `ConcurrentHashMap` was lost
 * on restart, leaving any player who was mid-dream when the server quit
 * stranded in Sureibjin with no way home.
 *
 * The snapshot's [SureibjinEntry.ReturnSnapshot.playerStateTag] is the
 * tag produced by vanilla's `Player.addAdditionalSaveData`, so the entry
 * holds a complete restorable copy of the player at the moment they fell
 * asleep — inventory, ender chest, food, XP, abilities, active effects.
 *
 * Single-use per entry: [take] removes and returns. `wakeUp` consumes the
 * snapshot, after which the player is back in their source dim with
 * their original state restored.
 */
internal class SureibjinReturnSavedData : SavedData() {

    internal val snapshots: MutableMap<UUID, SureibjinEntry.ReturnSnapshot> = mutableMapOf()

    internal fun put(uuid: UUID, snapshot: SureibjinEntry.ReturnSnapshot) {
        snapshots[uuid] = snapshot
        setDirty()
    }

    internal fun take(uuid: UUID): SureibjinEntry.ReturnSnapshot? {
        val snap = snapshots.remove(uuid) ?: return null
        setDirty()
        return snap
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((uuid, snap) in snapshots) {
            val entry = CompoundTag()
            entry.putUUID("UUID", uuid)
            entry.putString("Dim", snap.dimension.location().toString())
            entry.putDouble("X", snap.x)
            entry.putDouble("Y", snap.y)
            entry.putDouble("Z", snap.z)
            entry.putFloat("YRot", snap.yRot)
            entry.putFloat("XRot", snap.xRot)
            entry.put("State", snap.playerStateTag)
            list.add(entry)
        }
        tag.put("Snapshots", list)
        return tag
    }

    companion object {
        private const val NAME = "enderkinesis_sureibjin_returns"

        fun load(tag: CompoundTag): SureibjinReturnSavedData {
            val data = SureibjinReturnSavedData()
            val list = tag.getList("Snapshots", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val entry = list.getCompound(i)
                val uuid = entry.getUUID("UUID")
                val dimLoc = ResourceLocation.tryParse(entry.getString("Dim")) ?: continue
                val dim = ResourceKey.create(Registries.DIMENSION, dimLoc)
                data.snapshots[uuid] = SureibjinEntry.ReturnSnapshot(
                    dimension = dim,
                    x = entry.getDouble("X"),
                    y = entry.getDouble("Y"),
                    z = entry.getDouble("Z"),
                    yRot = entry.getFloat("YRot"),
                    xRot = entry.getFloat("XRot"),
                    playerStateTag = entry.getCompound("State"),
                )
            }
            return data
        }

        /** SavedData lives on the overworld's `dataStorage` so it's
         *  reachable regardless of which dimension the player is in. */
        fun get(server: MinecraftServer): SureibjinReturnSavedData =
            server.overworld().dataStorage.computeIfAbsent(
                ::load, ::SureibjinReturnSavedData, NAME,
            )
    }
}
