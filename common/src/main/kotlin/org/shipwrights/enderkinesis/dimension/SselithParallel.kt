package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import java.math.BigInteger
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.sselith.BookshelfPage

/**
 * Per-player "parallel" of Sselith's Repertory: which floor of the endless library the player reads from.
 * It goes up by one on each [SselithVoidWrap] fall and resets to zero when the player enters or leaves Sselith.
 * A [Sureibjin] dream from a Sselith bed keeps it.
 */
object SselithParallel {

    val SYNC: ResourceLocation = EnderkinesisMod.id("sselith_parallel/sync")

    private const val DATA_NAME = "enderkinesis_sselith_parallel"

    // A wrap takes 1024 blocks of fall, far more than this many ticks; a second wrap inside it is the same
    // fall reported twice (a player carried by a wrapped ship, then caught by the entity wrap).
    private const val WRAP_DEBOUNCE_TICKS = 100L
    private const val MAX_ENTRIES = 10_000
    private const val MAX_PARALLEL_BYTES = 1_024

    // Server thread only.
    private val lastWrapTick = HashMap<UUID, Long>()

    fun init() {
        LifecycleEvent.SERVER_LEVEL_LOAD.register { if (it.dimension() == SselithRepertory.LEVEL_KEY) BookshelfPage.acquire() }
        LifecycleEvent.SERVER_LEVEL_UNLOAD.register { if (it.dimension() == SselithRepertory.LEVEL_KEY) BookshelfPage.release() }
        PlayerEvent.PLAYER_JOIN.register(::onJoin)
        PlayerEvent.PLAYER_QUIT.register { lastWrapTick.remove(it.uuid) }
        PlayerEvent.CHANGE_DIMENSION.register { player, from, to ->
            // A dream from a Sselith bed keeps the parallel: the player wakes at the same bed. A dream
            // that ends in a different dimension is a leave.
            val dream = (from == SselithRepertory.LEVEL_KEY && to == Sureibjin.LEVEL_KEY) ||
                (from == Sureibjin.LEVEL_KEY && to == SselithRepertory.LEVEL_KEY)
            val crossesSselith = from == SselithRepertory.LEVEL_KEY || to == SselithRepertory.LEVEL_KEY ||
                from == Sureibjin.LEVEL_KEY
            if (crossesSselith && !dream) set(player, BigInteger.ZERO)
            if (to == SselithRepertory.LEVEL_KEY) sendTable(player)
        }
        // A respawn always moves the player out of the level they died in, so it counts as a leave or an enter.
        PlayerEvent.PLAYER_RESPAWN.register { player, _ -> set(player, BigInteger.ZERO) }
        // Players on other parallels meet only as Catalogers, which never take or deal damage.
        EntityEvent.LIVING_HURT.register { victim, source, _ ->
            val attacker = source.entity
            if (victim is ServerPlayer && attacker is ServerPlayer && attacker !== victim && get(victim) != get(attacker)) {
                EventResult.interruptFalse()
            } else {
                EventResult.pass()
            }
        }
    }

    fun get(player: ServerPlayer): BigInteger = data(player.server).byPlayer[player.uuid] ?: BigInteger.ZERO

    fun set(player: ServerPlayer, parallel: BigInteger) {
        require(parallel.signum() >= 0) { "Parallel must not be negative" }
        val data = data(player.server)
        val old = if (parallel.signum() == 0) data.byPlayer.remove(player.uuid) else data.byPlayer.put(player.uuid, parallel)
        if ((old ?: BigInteger.ZERO) == parallel) return
        data.setDirty()
        val update = mapOf(player.uuid to parallel)
        for (recipient in (sselithPlayers(player.server) + player).distinct()) {
            NetworkManager.sendToPlayer(recipient, SYNC, packet(replaceAll = false, update))
        }
    }

    fun onWrapped(players: Collection<ServerPlayer>, gameTime: Long) {
        for (player in players) {
            val last = lastWrapTick[player.uuid]
            if (last != null && gameTime - last < WRAP_DEBOUNCE_TICKS) continue
            lastWrapTick[player.uuid] = gameTime
            set(player, get(player) + BigInteger.ONE)
        }
    }

    // `set` announces a parallel only on a change, so a stored value needs its own announcement.
    private fun onJoin(player: ServerPlayer) {
        sendTable(player)
        val parallel = get(player)
        if (parallel.signum() == 0) return
        val update = mapOf(player.uuid to parallel)
        for (recipient in sselithPlayers(player.server)) {
            if (recipient !== player) NetworkManager.sendToPlayer(recipient, SYNC, packet(replaceAll = false, update))
        }
    }

    /** Replace [player]'s whole table with the parallels of everyone in Sselith. */
    private fun sendTable(player: ServerPlayer) {
        val entries = (sselithPlayers(player.server) + player).associate { it.uuid to get(it) }
        NetworkManager.sendToPlayer(player, SYNC, packet(replaceAll = true, entries))
    }

    private fun sselithPlayers(server: MinecraftServer): List<ServerPlayer> =
        server.getLevel(SselithRepertory.LEVEL_KEY)?.players().orEmpty()

    private fun packet(replaceAll: Boolean, entries: Map<UUID, BigInteger>): FriendlyByteBuf {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBoolean(replaceAll)
        buf.writeVarInt(entries.size)
        for ((player, parallel) in entries) {
            buf.writeUUID(player)
            buf.writeByteArray(parallel.toByteArray())
        }
        return buf
    }

    fun readPacket(buf: FriendlyByteBuf): Pair<Boolean, Map<UUID, BigInteger>> {
        val replaceAll = buf.readBoolean()
        val count = buf.readVarInt()
        require(count in 0..MAX_ENTRIES) { "Parallel table too large: $count" }
        val entries = HashMap<UUID, BigInteger>(count)
        repeat(count) { entries[buf.readUUID()] = BigInteger(buf.readByteArray(MAX_PARALLEL_BYTES)) }
        return replaceAll to entries
    }

    private fun data(server: MinecraftServer): ParallelData =
        server.overworld().dataStorage.computeIfAbsent(ParallelData::load, ::ParallelData, DATA_NAME)

    private class ParallelData : SavedData() {
        val byPlayer = HashMap<UUID, BigInteger>()

        override fun save(tag: CompoundTag): CompoundTag {
            val list = ListTag()
            for ((player, parallel) in byPlayer) {
                val entry = CompoundTag()
                entry.putUUID("player", player)
                entry.putByteArray("parallel", parallel.toByteArray())
                list.add(entry)
            }
            tag.put("players", list)
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): ParallelData {
                val data = ParallelData()
                for (entry in tag.getList("players", Tag.TAG_COMPOUND.toInt())) {
                    entry as CompoundTag
                    data.byPlayer[entry.getUUID("player")] = BigInteger(entry.getByteArray("parallel"))
                }
                return data
            }
        }
    }
}
